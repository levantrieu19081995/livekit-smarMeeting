param(
    [int]$MeetingId = 12,
    [string]$Username = "",
    [string]$Password = "",
    [string]$JavaBaseUrl = "http://localhost:8080",
    [string]$PythonBaseUrl = "http://localhost:8000",
    [string]$InternalApiKey = $env:AI_INTERNAL_API_KEY,
    [int]$TimeoutSeconds = 45,
    [int]$PollSeconds = 1,

    # DB defaults match the current meeting-service dev source.
    [string]$DbContainer = "meeting-postgres",
    [string]$DbName = "meeting_db",
    [string]$DbUser = "meeting_user",
    [string]$DbPassword = "meeting_password",

    # If an existing FAILED Minutes job is found, automatically POST /retry.
    [switch]$AutoRetryFailed
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Fail-Test {
    param([string]$Message)
    Write-Host ""
    Write-Host "============================================"
    Write-Host " JAVA -> PYTHON INTEGRATION TEST FAILED"
    Write-Host "============================================"
    Write-Host $Message
    exit 1
}

function Get-StatusCodeFromError {
    param($ErrorRecord)
    try {
        return [int]$ErrorRecord.Exception.Response.StatusCode
    } catch {
        return -1
    }
}

function Get-Csrf {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session
    )

    return Invoke-RestMethod `
        -Method Get `
        -Uri "$JavaBaseUrl/api/auth/csrf" `
        -WebSession $Session
}

function Get-PostHeaders {
    param($Csrf)

    $headers = @{}
    $headers[$Csrf.headerName] = $Csrf.token
    return $headers
}

function Get-Processing {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session
    )

    try {
        return Invoke-RestMethod `
            -Method Get `
            -Uri "$JavaBaseUrl/api/meetings/$MeetingId/minutes/processing" `
            -WebSession $Session
    } catch {
        $code = Get-StatusCodeFromError $_
        if ($code -eq 404) {
            return $null
        }
        throw
    }
}

function Query-LatestJobFromDocker {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        return $null
    }

    $containerRunning = docker ps --format "{{.Names}}" | Where-Object { $_ -eq $DbContainer }
    if (-not $containerRunning) {
        return $null
    }

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
ORDER BY attempt DESC, id DESC
LIMIT 1;
"@

    $raw = docker exec `
        -e "PGPASSWORD=$DbPassword" `
        $DbContainer `
        psql `
        -U $DbUser `
        -d $DbName `
        -t `
        -A `
        -F "|" `
        -c $sql

    if ($LASTEXITCODE -ne 0) {
        return $null
    }

    $line = ($raw | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)

    if ([string]::IsNullOrWhiteSpace($line)) {
        return $null
    }

    $parts = $line.Split("|", 7)

    if ($parts.Count -lt 7) {
        return $null
    }

    return [PSCustomObject]@{
        id              = $parts[0]
        meetingId       = $parts[1]
        status          = $parts[2]
        stage           = $parts[3]
        progressPercent = $parts[4]
        processingId    = $parts[5]
        errorMessage    = $parts[6]
    }
}

function Assert-PythonJobExists {
    param([string]$ProcessingId)

    if ([string]::IsNullOrWhiteSpace($ProcessingId)) {
        Fail-Test "DB stt_processing_id is blank."
    }

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($InternalApiKey)) {
        $headers["X-Internal-Api-Key"] = $InternalApiKey
    }

    try {
        return Invoke-RestMethod `
            -Method Get `
            -Uri "$PythonBaseUrl/api/postprocessing/stt/jobs/$ProcessingId" `
            -Headers $headers
    } catch {
        $code = Get-StatusCodeFromError $_
        Fail-Test "Java stored processingId '$ProcessingId' but Python GET status failed. HTTP=$code"
    }
}

Write-Host "============================================"
Write-Host " JAVA -> PYTHON REAL STT JOB INTEGRATION TEST"
Write-Host "============================================"
Write-Host "MeetingId : $MeetingId"
Write-Host "Java      : $JavaBaseUrl"
Write-Host "Python    : $PythonBaseUrl"
Write-Host ""

if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
    Fail-Test @"
Username/Password are required because /minutes/generate and /minutes/processing are authenticated.

Run for example:
powershell -ExecutionPolicy Bypass -File .\test_java_to_python_stt_job.ps1 `
  -MeetingId 12 `
  -Username "YOUR_USERNAME_OR_EMAIL" `
  -Password "YOUR_PASSWORD"
"@
}

Write-Host "=== 1. CHECK JAVA / PYTHON HEALTH ==="

try {
    $javaHealth = Invoke-RestMethod -Method Get -Uri "$JavaBaseUrl/actuator/health"
    Write-Host "Java health   :" $javaHealth.status
} catch {
    Fail-Test "Java is not reachable at $JavaBaseUrl"
}

try {
    $pythonHealth = Invoke-RestMethod -Method Get -Uri "$PythonBaseUrl/health"
    Write-Host "Python health : OK"
} catch {
    Fail-Test "Python is not reachable at $PythonBaseUrl"
}

Write-Host ""
Write-Host "=== 2. LOGIN JAVA AND GET CSRF ==="

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$csrf = Get-Csrf -Session $session
$headers = Get-PostHeaders -Csrf $csrf

$loginBody = @{
    usernameOrEmail = $Username
    password = $Password
} | ConvertTo-Json -Compress

try {
    $login = Invoke-RestMethod `
        -Method Post `
        -Uri "$JavaBaseUrl/api/auth/login" `
        -WebSession $session `
        -Headers $headers `
        -ContentType "application/json; charset=utf-8" `
        -Body $loginBody
} catch {
    Fail-Test "Java login failed. Check username/password."
}

# Refresh CSRF after login/session fixation.
$csrf = Get-Csrf -Session $session
$headers = Get-PostHeaders -Csrf $csrf

Write-Host "Login: PASS"

Write-Host ""
Write-Host "=== 3. CHECK / CREATE MINUTES JOB ==="

$current = Get-Processing -Session $session

if ($null -eq $current) {
    Write-Host "No existing Minutes job. Creating a new job..."

    try {
        $created = Invoke-RestMethod `
            -Method Post `
            -Uri "$JavaBaseUrl/api/meetings/$MeetingId/minutes/generate" `
            -WebSession $session `
            -Headers $headers `
            -ContentType "application/json; charset=utf-8"

        Write-Host (
            "CREATED: JOB={0} STATUS={1} STAGE={2} PROGRESS={3}%" -f `
            $created.jobId,
            $created.status,
            $created.stage,
            $created.progressPercent
        )
    } catch {
        Fail-Test "POST /minutes/generate failed: $($_.Exception.Message)"
    }
}
elseif ($current.status -eq "FAILED") {
    Write-Host (
        "Existing job is FAILED: JOB={0} ERROR={1}" -f `
        $current.jobId,
        $current.errorMessage
    )

    if (-not $AutoRetryFailed) {
        Fail-Test "Existing latest job is FAILED. Re-run with -AutoRetryFailed to POST /minutes/retry."
    }

    Write-Host "Retrying failed Minutes job..."

    try {
        $created = Invoke-RestMethod `
            -Method Post `
            -Uri "$JavaBaseUrl/api/meetings/$MeetingId/minutes/retry" `
            -WebSession $session `
            -Headers $headers `
            -ContentType "application/json; charset=utf-8"

        Write-Host (
            "RETRY CREATED: JOB={0} STATUS={1} STAGE={2} PROGRESS={3}%" -f `
            $created.jobId,
            $created.status,
            $created.stage,
            $created.progressPercent
        )
    } catch {
        Fail-Test "POST /minutes/retry failed: $($_.Exception.Message)"
    }
}
else {
    Write-Host (
        "Existing latest job: JOB={0} STATUS={1} STAGE={2} PROGRESS={3}%" -f `
        $current.jobId,
        $current.status,
        $current.stage,
        $current.progressPercent
    )
    Write-Host "Will monitor this job instead of creating a duplicate."
}

Write-Host ""
Write-Host "=== 4. POLL JAVA UNTIL TRANSCRIBING ==="

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$javaReachedTranscribing = $false
$latest = $null

while ((Get-Date) -lt $deadline) {
    $latest = Get-Processing -Session $session

    if ($null -eq $latest) {
        Start-Sleep -Seconds $PollSeconds
        continue
    }

    Write-Host (
        "JAVA JOB={0} STATUS={1} STAGE={2} PROGRESS={3}% MESSAGE={4}" -f `
        $latest.jobId,
        $latest.status,
        $latest.stage,
        $latest.progressPercent,
        $latest.message
    )

    if ($latest.status -eq "FAILED") {
        Fail-Test "Java Minutes job FAILED: $($latest.errorMessage)"
    }

    if (
        $latest.status -eq "PROCESSING" -and
        $latest.stage -eq "TRANSCRIBING" -and
        [int]$latest.progressPercent -ge 10
    ) {
        $javaReachedTranscribing = $true
        break
    }

    Start-Sleep -Seconds $PollSeconds
}

if (-not $javaReachedTranscribing) {
    Fail-Test "Java did not reach PROCESSING / TRANSCRIBING / >=10% within $TimeoutSeconds seconds."
}

Write-Host ""
Write-Host "PASS: Java reached PROCESSING / TRANSCRIBING / 10%."
Write-Host "This state is only set after Java receives a valid Python STT job response."

Write-Host ""
Write-Host "=== 5. VERIFY stt_processing_id IN POSTGRES ==="

$dbJob = Query-LatestJobFromDocker

if ($null -eq $dbJob) {
    Write-Host "WARNING: Could not query PostgreSQL through Docker container '$DbContainer'."
    Write-Host "Java state transition PASSed, but independent DB processingId verification was skipped."
    Write-Host ""
    Write-Host "To fully verify, make sure Docker container '$DbContainer' is running,"
    Write-Host "or check meeting_minutes_jobs.stt_processing_id manually."
    Write-Host ""
    Write-Host "============================================"
    Write-Host " CORE JAVA -> PYTHON POST TEST PASS"
    Write-Host " DB PROCESSING-ID CHECK SKIPPED"
    Write-Host "============================================"
    exit 0
}

Write-Host "DB jobId        :" $dbJob.id
Write-Host "DB meetingId    :" $dbJob.meetingId
Write-Host "DB status       :" $dbJob.status
Write-Host "DB stage        :" $dbJob.stage
Write-Host "DB progress     :" $dbJob.progressPercent
Write-Host "DB processingId :" $dbJob.processingId

if ($dbJob.status -ne "PROCESSING") {
    Fail-Test "DB status is not PROCESSING."
}

if ($dbJob.stage -ne "TRANSCRIBING") {
    Fail-Test "DB stage is not TRANSCRIBING."
}

if ([int]$dbJob.progressPercent -lt 10) {
    Fail-Test "DB progress is below 10."
}

if ([string]::IsNullOrWhiteSpace($dbJob.processingId)) {
    Fail-Test "DB stt_processing_id is blank."
}

Write-Host ""
Write-Host "PASS: Java persisted Python processingId."

Write-Host ""
Write-Host "=== 6. VERIFY SAME JOB EXISTS IN PYTHON ==="

$pythonStatus = Assert-PythonJobExists -ProcessingId $dbJob.processingId

Write-Host "Python processingId :" $dbJob.processingId
Write-Host "Python meetingId    :" $pythonStatus.meetingId
Write-Host "Python status       :" $pythonStatus.status
Write-Host "Python stage        :" $pythonStatus.stage
Write-Host "Python progress     :" $pythonStatus.progressPercent
Write-Host "Python chunks       :" "$($pythonStatus.processedChunks)/$($pythonStatus.totalChunks)"

if ([int]$pythonStatus.meetingId -ne $MeetingId) {
    Fail-Test "Python job meetingId does not match Java meetingId."
}

if ($pythonStatus.status -notin @("QUEUED", "PROCESSING", "COMPLETED", "PARTIAL")) {
    Fail-Test "Unexpected Python job status: $($pythonStatus.status)"
}

Write-Host ""
Write-Host "============================================"
Write-Host " JAVA -> PYTHON POST JOB TEST PASS"
Write-Host "============================================"
Write-Host "Confirmed:"
Write-Host "  Java Minutes job -> dispatcher"
Write-Host "  Java -> Python POST /stt/jobs"
Write-Host "  Python returned processingId"
Write-Host "  Java persisted processingId"
Write-Host "  Java moved to TRANSCRIBING / 10%"
Write-Host "  Same processingId exists in Python"
Write-Host ""
Write-Host "NOTE:"
Write-Host "At this checkpoint Java is expected to remain TRANSCRIBING / 10%"
Write-Host "even if Python later reaches COMPLETED, because progress/completion"
Write-Host "integration has not been implemented yet."
