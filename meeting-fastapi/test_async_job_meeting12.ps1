$ErrorActionPreference = "Stop"

# UTF-8 console output
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$baseUrl = "http://localhost:8000"
$meetingId = 12
$manifestPath = "meeting-12/session-8c819676-bba1-4463-bb02-2bc9074b488d/manifest.json"

Write-Host "=== 1. START ASYNC STT JOB ==="

$body = @{
    meetingId = $meetingId
    manifestPath = $manifestPath
} | ConvertTo-Json -Compress

$job = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/api/postprocessing/stt/jobs" `
    -ContentType "application/json" `
    -Body $body

$job | Format-List

$processingId = $job.processingId

if (-not $processingId) {
    throw "POST /jobs did not return processingId"
}

Write-Host ""
Write-Host "processingId = $processingId"
Write-Host ""
Write-Host "=== 2. POLL STATUS ==="

do {
    $status = Invoke-RestMethod `
        -Method Get `
        -Uri "$baseUrl/api/postprocessing/stt/jobs/$processingId"

    Write-Host (
        "STATUS={0} STAGE={1} PROGRESS={2}% CHUNKS={3}/{4}" -f `
        $status.status,
        $status.stage,
        $status.progressPercent,
        $status.processedChunks,
        $status.totalChunks
    )

    if ($status.status -in @("COMPLETED", "PARTIAL", "FAILED")) {
        break
    }

    Start-Sleep -Seconds 2
} while ($true)

if ($status.status -eq "FAILED") {
    Write-Host ""
    Write-Host "=== JOB FAILED ==="
    Write-Host "Error: $($status.error)"
    exit 1
}

Write-Host ""
Write-Host "=== 3. GET RESULT ==="

$result = Invoke-RestMethod `
    -Method Get `
    -Uri "$baseUrl/api/postprocessing/stt/jobs/$processingId/result"

Write-Host ""
Write-Host "=== SUMMARY ==="

$result.result | Select-Object `
    meetingId,
    sessionId,
    status,
    totalSegments,
    totalChunks,
    processedChunks,
    succeededChunks,
    failedChunks | Format-List

Write-Host ""
Write-Host "=== TRANSCRIPTS ==="

$result.result.transcripts | ForEach-Object {
    Write-Host "----------------------------------------"
    Write-Host "Speaker :" $_.speakerName
    Write-Host "Segment :" $_.segmentId
    Write-Host "Chunk   :" $_.chunkIndex
    Write-Host "Time    :" $_.absoluteStartTime
    Write-Host "Text    :" $_.text
}

if ($result.result.failures.Count -gt 0) {
    Write-Host ""
    Write-Host "=== FAILURES ==="

    $result.result.failures | ForEach-Object {
        Write-Host "----------------------------------------"
        Write-Host "Segment :" $_.segmentId
        Write-Host "Chunk   :" $_.chunkIndex
        Write-Host "Attempts:" $_.attempts
        Write-Host "Error   :" $_.error
    }
}

Write-Host ""
Write-Host "=== DONE ==="
