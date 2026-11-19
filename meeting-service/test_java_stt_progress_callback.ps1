param(
    [int]$MeetingId = 12,
    [string]$JavaBaseUrl = "http://localhost:8080",
    [string]$InternalApiKey = $env:AI_INTERNAL_API_KEY,

    [string]$DbHost = "localhost",
    [int]$DbPort = 5432,
    [string]$DbName = "meeting_db",
    [string]$DbUser = "meeting_user",
    [string]$DbPassword = "meeting_password",

    # Optional. If omitted, script auto-detects PostgreSQL psql.exe under Program Files.
    [string]$PsqlPath = ""
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Fail-Test {
    param([string]$Message)
    Write-Host ""
    Write-Host "============================================"
    Write-Host " JAVA STT PROGRESS CALLBACK TEST FAILED"
    Write-Host "============================================"
    Write-Host $Message
    exit 1
}

function Find-Psql {
    if (-not [string]::IsNullOrWhiteSpace($PsqlPath)) {
        if (-not (Test-Path $PsqlPath)) {
            Fail-Test "PsqlPath does not exist: $PsqlPath"
        }
        return $PsqlPath
    }

    $candidate = Get-ChildItem "C:\Program Files\PostgreSQL" `
        -Filter psql.exe `
        -Recurse `
        -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName

    if ([string]::IsNullOrWhiteSpace($candidate)) {
        Fail-Test "Could not find psql.exe under C:\Program Files\PostgreSQL. Pass -PsqlPath explicitly."
    }

    return $candidate
}

function Invoke-PsqlScalarRow {
    param(
        [string]$Sql
    )

    $env:PGPASSWORD = $DbPassword

    $raw = & $script:Psql `
        -h $DbHost `
        -p $DbPort `
        -U $DbUser `
        -d $DbName `
        -t `
        -A `
        -F "|" `
        -c $Sql

    if ($LASTEXITCODE -ne 0) {
        Fail-Test "psql command failed."
    }

    $line = $raw |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -First 1

    return $line
}

function Get-SttJob {
    $sql = @"
SELECT
    id,
    meeting_id,
    status,
    stage,
    progress_percent,
    COALESCE(stt_processing_id, ''),
    COALESCE(error_message, '')
FROM meeting_minutes_jobs
WHERE meeting_id = $MeetingId
ORDER BY id DESC
LIMIT 1;
"@

    $line = Invoke-PsqlScalarRow -Sql $sql

    if ([string]::IsNullOrWhiteSpace($line)) {
        return $null
    }

    $parts = $line.Split("|", 7)
    if ($parts.Count -lt 7) {
        Fail-Test "Unexpected DB row format: $line"
    }

    return [PSCustomObject]@{
        id              = [int]$parts[0]
        meetingId       = [int]$parts[1]
        status          = $parts[2]
        stage           = $parts[3]
        progressPercent = [int]$parts[4]
        processingId    = $parts[5]
        errorMessage    = $parts[6]
    }
}

function Assert-JobState {
    param(
        [int]$ExpectedProgress,
        [string]$Label
    )

    Start-Sleep -Milliseconds 200
    $job = Get-SttJob

    if ($null -eq $job) {
        Fail-Test "$Label - no Minutes job found."
    }

    Write-Host (
        "{0}: STATUS={1} STAGE={2} PROGRESS={3}% PROCESSING_ID={4}" -f `
        $Label,
        $job.status,
        $job.stage,
        $job.progressPercent,
        $job.processingId
    )

    if ($job.status -ne "PROCESSING") {
        Fail-Test "$Label - expected status PROCESSING, actual=$($job.status)"
    }

    if ($job.stage -ne "TRANSCRIBING") {
        Fail-Test "$Label - expected stage TRANSCRIBING, actual=$($job.stage)"
    }

    if ($job.progressPercent -ne $ExpectedProgress) {
        Fail-Test "$Label - expected progress=$ExpectedProgress, actual=$($job.progressPercent)"
    }

    return $job
}

function Invoke-ProgressCallback {
    param(
        [string]$ProcessingId,
        [int]$ProcessedChunks,
        [int]$TotalChunks,
        [double]$ProgressPercent,
        [string]$Key
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Key)) {
        $headers["X-Internal-Api-Key"] = $Key
    }

    $body = @{
        meetingId = $MeetingId
        processedChunks = $ProcessedChunks
        totalChunks = $TotalChunks
        progressPercent = $ProgressPercent
    } | ConvertTo-Json -Compress

    try {
        $response = Invoke-WebRequest `
            -Method Post `
            -Uri "$JavaBaseUrl/internal/api/minutes/stt/jobs/$ProcessingId/progress" `
            -Headers $headers `
            -ContentType "application/json; charset=utf-8" `
            -Body $body `
            -UseBasicParsing

        return [int]$response.StatusCode
    } catch {
        try {
            return [int]$_.Exception.Response.StatusCode
        } catch {
            throw
        }
    }
}

Write-Host "============================================"
Write-Host " JAVA STT PROGRESS CALLBACK RUNTIME TEST"
Write-Host "============================================"
Write-Host "MeetingId : $MeetingId"
Write-Host "Java      : $JavaBaseUrl"

if ([string]::IsNullOrWhiteSpace($InternalApiKey)) {
    Fail-Test @"
InternalApiKey is blank.

Pass it explicitly:
  -InternalApiKey "YOUR_INTERNAL_KEY"

or set:
  `$env:AI_INTERNAL_API_KEY="YOUR_INTERNAL_KEY"
"@
}

$script:Psql = Find-Psql
Write-Host "psql      : $script:Psql"

Write-Host ""
Write-Host "=== 1. CHECK JAVA HEALTH ==="

try {
    $health = Invoke-RestMethod -Method Get -Uri "$JavaBaseUrl/actuator/health"
    Write-Host "Java health:" $health.status
} catch {
    Fail-Test "Java is not reachable at $JavaBaseUrl"
}

Write-Host ""
Write-Host "=== 2. LOAD CURRENT STT JOB FROM DB ==="

$job = Get-SttJob

if ($null -eq $job) {
    Fail-Test "No Minutes job found for meeting $MeetingId."
}

Write-Host "jobId        :" $job.id
Write-Host "status       :" $job.status
Write-Host "stage        :" $job.stage
Write-Host "progress     :" $job.progressPercent
Write-Host "processingId :" $job.processingId

if ($job.status -ne "PROCESSING" -or $job.stage -ne "TRANSCRIBING") {
    Fail-Test "Job must already be PROCESSING / TRANSCRIBING before testing progress callback."
}

if ([string]::IsNullOrWhiteSpace($job.processingId)) {
    Fail-Test "stt_processing_id is blank."
}

# This runtime test is deterministic only from the current checkpoint 10%.
if ($job.progressPercent -ne 10) {
    Fail-Test @"
Current Java progress is $($job.progressPercent)%, not 10%.

This test intentionally does NOT reset DB state.
Use a fresh Java->Python STT job at TRANSCRIBING/10%, or manually restore only your local test job to 10 before running.
"@
}

$processingId = $job.processingId

Write-Host ""
Write-Host "=== 3. SECURITY TEST: WRONG INTERNAL KEY ==="

$wrongKey = "$InternalApiKey-wrong"
$statusCode = Invoke-ProgressCallback `
    -ProcessingId $processingId `
    -ProcessedChunks 1 `
    -TotalChunks 4 `
    -ProgressPercent 25 `
    -Key $wrongKey

Write-Host "HTTP:" $statusCode

if ($statusCode -ne 401) {
    Fail-Test "Wrong internal key must return HTTP 401, actual=$statusCode"
}

Assert-JobState -ExpectedProgress 10 -Label "After wrong key" | Out-Null
Write-Host "PASS: wrong internal key rejected and DB unchanged."

Write-Host ""
Write-Host "=== 4. VALID CALLBACK WITHOUT CSRF: STT 0% -> JAVA 10% ==="

$statusCode = Invoke-ProgressCallback `
    -ProcessingId $processingId `
    -ProcessedChunks 0 `
    -TotalChunks 4 `
    -ProgressPercent 0 `
    -Key $InternalApiKey

Write-Host "HTTP:" $statusCode

if ($statusCode -ne 204) {
    Fail-Test "Valid callback should return HTTP 204 without CSRF token, actual=$statusCode"
}

Assert-JobState -ExpectedProgress 10 -Label "STT 0%" | Out-Null
Write-Host "PASS: CSRF exemption + 0% -> 10% mapping."

Write-Host ""
Write-Host "=== 5. STT 50% -> JAVA 40% ==="

$statusCode = Invoke-ProgressCallback `
    -ProcessingId $processingId `
    -ProcessedChunks 2 `
    -TotalChunks 4 `
    -ProgressPercent 50 `
    -Key $InternalApiKey

if ($statusCode -ne 204) {
    Fail-Test "STT 50% callback returned HTTP $statusCode"
}

Assert-JobState -ExpectedProgress 40 -Label "STT 50%" | Out-Null
Write-Host "PASS: 50% STT mapped to 40% Minutes progress."

Write-Host ""
Write-Host "=== 6. STT 100% -> JAVA 70%, BUT NOT COMPLETED ==="

$statusCode = Invoke-ProgressCallback `
    -ProcessingId $processingId `
    -ProcessedChunks 4 `
    -TotalChunks 4 `
    -ProgressPercent 100 `
    -Key $InternalApiKey

if ($statusCode -ne 204) {
    Fail-Test "STT 100% callback returned HTTP $statusCode"
}

Assert-JobState -ExpectedProgress 70 -Label "STT 100%" | Out-Null
Write-Host "PASS: 100% STT mapped to 70%; Java remains PROCESSING / TRANSCRIBING."

Write-Host ""
Write-Host "=== 7. OUT-OF-ORDER CALLBACK MUST NOT MOVE PROGRESS BACKWARD ==="

$statusCode = Invoke-ProgressCallback `
    -ProcessingId $processingId `
    -ProcessedChunks 1 `
    -TotalChunks 4 `
    -ProgressPercent 20 `
    -Key $InternalApiKey

if ($statusCode -ne 204) {
    Fail-Test "Out-of-order callback returned HTTP $statusCode"
}

Assert-JobState -ExpectedProgress 70 -Label "After old 20% callback" | Out-Null
Write-Host "PASS: progress stayed at 70%."

Write-Host ""
Write-Host "============================================"
Write-Host " JAVA STT PROGRESS CALLBACK TEST PASS"
Write-Host "============================================"
Write-Host "Confirmed:"
Write-Host "  Wrong internal key -> 401"
Write-Host "  Valid internal callback needs no browser CSRF"
Write-Host "  STT 0%   -> Java 10%"
Write-Host "  STT 50%  -> Java 40%"
Write-Host "  STT 100% -> Java 70%"
Write-Host "  100% STT does NOT mark Minutes COMPLETED"
Write-Host "  Old/out-of-order callback cannot reduce progress"
Write-Host ""
Write-Host "Java side for Python progress -> Java is ready."
Write-Host "Next step after this PASS: implement callback sender in meeting-fastapi."
