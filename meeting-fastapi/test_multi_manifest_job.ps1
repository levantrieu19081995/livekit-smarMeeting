param(
    [int]$MeetingId = 12
)

$ErrorActionPreference = "Stop"

# =========================
# CONFIG - EDIT THIS SECTION
# =========================

$BaseUrl = "http://localhost:8000"

# Add ALL real manifest paths that belong to the SAME meeting.
# IMPORTANT:
# - Use at least 2 different manifests to really test Multi-manifest.
# - Do not put the same manifest twice; the API de-duplicates duplicate paths.
$ManifestPaths = @(
    "meeting-12/session-8c819676-bba1-4463-bb02-2bc9074b488d/manifest.json",
    "meeting-12/session-ef8ea6b7-94e1-4aa0-ad76-35a0095d0b0e/manifest.json"
)

# Leave blank when REQUIRE_INTERNAL_API_KEY=false.
# If REQUIRE_INTERNAL_API_KEY=true, put the internal key here for local testing.
$InternalApiKey = ""

# Poll interval
$PollSeconds = 2

# Optional JSON output file for easier inspection.
$ResultFile = ".\multi_manifest_result_meeting_$MeetingId.json"

# =========================
# END CONFIG
# =========================

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Fail-Test {
    param([string]$Message)
    Write-Host ""
    Write-Host "TEST FAILED: $Message"
    exit 1
}

function Assert-Equal {
    param(
        $Actual,
        $Expected,
        [string]$Message
    )

    if ($Actual -ne $Expected) {
        Fail-Test "$Message | expected=$Expected actual=$Actual"
    }
}

function Get-Headers {
    $headers = @{}

    if (-not [string]::IsNullOrWhiteSpace($InternalApiKey)) {
        $headers["X-Internal-Api-Key"] = $InternalApiKey
    }

    return $headers
}

Write-Host "============================================"
Write-Host " MULTI-MANIFEST / MEETING-LEVEL STT TEST"
Write-Host "============================================"
Write-Host "MeetingId: $MeetingId"
Write-Host ""

# Validate manifest config before calling the API.
$normalizedManifestPaths = @()

foreach ($path in $ManifestPaths) {
    if ([string]::IsNullOrWhiteSpace($path)) {
        Fail-Test "ManifestPaths contains a blank path."
    }

    if ($path -like "CHANGE_ME*") {
        Fail-Test "Please replace '$path' with a real SECOND manifest path for meeting $MeetingId."
    }

    if ($normalizedManifestPaths -notcontains $path) {
        $normalizedManifestPaths += $path
    }
}

if ($normalizedManifestPaths.Count -lt 2) {
    Fail-Test "Need at least 2 DIFFERENT manifest paths to test Multi-manifest."
}

Write-Host "Manifests:"
for ($i = 0; $i -lt $normalizedManifestPaths.Count; $i++) {
    Write-Host ("  [{0}] {1}" -f ($i + 1), $normalizedManifestPaths[$i])
}

$headers = Get-Headers

$body = @{
    meetingId = $MeetingId
    manifestPaths = $normalizedManifestPaths
} | ConvertTo-Json -Depth 5 -Compress

Write-Host ""
Write-Host "=== 1. POST MEETING-LEVEL STT JOB ==="

$job = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/postprocessing/stt/jobs" `
    -Headers $headers `
    -ContentType "application/json; charset=utf-8" `
    -Body $body

$job | Format-List

if (-not $job.processingId) {
    Fail-Test "POST /jobs did not return processingId."
}

Assert-Equal $job.meetingId $MeetingId "POST /jobs returned wrong meetingId"
Assert-Equal $job.status "QUEUED" "New job should initially return QUEUED"

$ProcessingId = $job.processingId

Write-Host ""
Write-Host "processingId = $ProcessingId"

Write-Host ""
Write-Host "=== 2. POLL GLOBAL MEETING PROGRESS ==="

$lastProcessed = -1
$lastTotal = -1

do {
    $status = Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/postprocessing/stt/jobs/$ProcessingId" `
        -Headers $headers

    $processed = 0
    $total = 0
    $progress = 0

    if ($null -ne $status.processedChunks) {
        $processed = [int]$status.processedChunks
    }

    if ($null -ne $status.totalChunks) {
        $total = [int]$status.totalChunks
    }

    if ($null -ne $status.progressPercent) {
        $progress = $status.progressPercent
    }

    Write-Host (
        "STATUS={0} STAGE={1} PROGRESS={2}% CHUNKS={3}/{4}" -f `
        $status.status,
        $status.stage,
        $progress,
        $processed,
        $total
    )

    # Progress must never go backwards.
    if ($lastProcessed -ge 0 -and $processed -lt $lastProcessed) {
        Fail-Test "processedChunks went backwards: $lastProcessed -> $processed"
    }

    if ($lastTotal -gt 0 -and $total -gt 0 -and $total -ne $lastTotal) {
        Fail-Test "totalChunks changed while processing: $lastTotal -> $total"
    }

    if ($processed -gt $total -and $total -gt 0) {
        Fail-Test "processedChunks is greater than totalChunks."
    }

    $lastProcessed = $processed

    if ($total -gt 0) {
        $lastTotal = $total
    }

    if ($status.status -in @("COMPLETED", "PARTIAL", "FAILED")) {
        break
    }

    Start-Sleep -Seconds $PollSeconds

} while ($true)

if ($status.status -eq "FAILED") {
    Write-Host ""
    Write-Host "Job error:"
    Write-Host $status.error
    Fail-Test "Meeting-level STT job ended with FAILED."
}

Write-Host ""
Write-Host "=== 3. GET MEETING-LEVEL RESULT ==="

$resultEnvelope = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/postprocessing/stt/jobs/$ProcessingId/result" `
    -Headers $headers

$result = $resultEnvelope.result

if ($null -eq $result) {
    Fail-Test "GET /result did not return result."
}

# Save the full result for later review.
$json = $resultEnvelope | ConvertTo-Json -Depth 20
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText(
    [System.IO.Path]::GetFullPath($ResultFile),
    $json,
    $utf8NoBom
)

Write-Host ""
Write-Host "=== 4. CONTRACT ASSERTIONS ==="

Assert-Equal $result.meetingId $MeetingId "Result meetingId mismatch"
Assert-Equal $result.totalManifests $normalizedManifestPaths.Count "totalManifests mismatch"

if ($null -eq $result.sessionIds) {
    Fail-Test "Result does not contain sessionIds."
}

if ($result.sessionIds.Count -lt 2) {
    Fail-Test "Expected at least 2 sessionIds for a real Multi-manifest test."
}

if ($result.totalChunks -lt 1) {
    Fail-Test "totalChunks must be greater than 0."
}

Assert-Equal $result.processedChunks $result.totalChunks "processedChunks must equal totalChunks when job is terminal"

$calculatedTerminal = [int]$result.succeededChunks + [int]$result.failedChunks
Assert-Equal $calculatedTerminal $result.totalChunks "succeededChunks + failedChunks must equal totalChunks"

if ($result.status -notin @("COMPLETED", "PARTIAL")) {
    Fail-Test "Unexpected result status: $($result.status)"
}

Write-Host "PASS: one processingId was used for the whole meeting."
Write-Host "PASS: totalManifests = $($result.totalManifests)"
Write-Host "PASS: sessionIds = $($result.sessionIds -join ', ')"
Write-Host "PASS: processedChunks = totalChunks = $($result.totalChunks)"
Write-Host "PASS: terminal status = $($result.status)"

Write-Host ""
Write-Host "=== 5. SUMMARY ==="

$result | Select-Object `
    meetingId,
    status,
    totalManifests,
    totalSegments,
    totalChunks,
    processedChunks,
    succeededChunks,
    failedChunks | Format-List

Write-Host "sessionIds:"
$result.sessionIds | ForEach-Object {
    Write-Host "  - $_"
}

Write-Host ""
Write-Host "=== 6. TRANSCRIPT SESSION CHECK ==="

$transcriptSessionIds = @(
    $result.transcripts |
        ForEach-Object { $_.sessionId } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique
)

if ($transcriptSessionIds.Count -gt 0) {
    Write-Host "Sessions that produced transcript:"
    $transcriptSessionIds | ForEach-Object {
        Write-Host "  - $_"
    }
} else {
    Write-Host "WARNING: No transcript sessionId found."
}

Write-Host ""
Write-Host "=== 7. TRANSCRIPTS ==="

$result.transcripts | ForEach-Object {
    Write-Host "--------------------------------------------------"
    Write-Host "Session :" $_.sessionId
    Write-Host "Speaker :" $_.speakerName
    Write-Host "Segment :" $_.segmentId
    Write-Host "Chunk   :" $_.chunkIndex
    Write-Host "Time    :" $_.absoluteStartTime
    Write-Host "Text    :" $_.text
}

if ($result.failures.Count -gt 0) {
    Write-Host ""
    Write-Host "=== 8. FAILURES ==="

    $result.failures | ForEach-Object {
        Write-Host "--------------------------------------------------"
        Write-Host "Session :" $_.sessionId
        Write-Host "Segment :" $_.segmentId
        Write-Host "Chunk   :" $_.chunkIndex
        Write-Host "Attempts:" $_.attempts
        Write-Host "File    :" $_.filePath
        Write-Host "Error   :" $_.error
    }
}

Write-Host ""
Write-Host "Full JSON result saved to:"
Write-Host "  $ResultFile"

Write-Host ""
if ($result.status -eq "COMPLETED") {
    Write-Host "============================================"
    Write-Host " MULTI-MANIFEST TEST PASS - COMPLETED"
    Write-Host "============================================"
    exit 0
}

Write-Host "============================================"
Write-Host " MULTI-MANIFEST TEST PASS - PARTIAL"
Write-Host " Review failures above."
Write-Host "============================================"
exit 2
