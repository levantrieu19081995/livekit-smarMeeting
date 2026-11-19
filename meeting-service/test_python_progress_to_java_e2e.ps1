param(
    [int]$MeetingId = 12,

    [string]$JavaBaseUrl = "http://localhost:8080",
    [string]$PythonBaseUrl = "http://localhost:8000",

    [string]$DbHost = "localhost",
    [int]$DbPort = 5432,
    [string]$DbName = "meeting_db",
    [string]$DbUser = "meeting_user",
    [string]$DbPassword = "meeting_password",

    [string]$PsqlPath = "",

    # Current meeting 12 job is already at 70% from the manual Java callback test.
    # Use this switch in LOCAL DEV to reset the latest job to QUEUED/VALIDATING/0
    # so Java dispatcher submits a NEW Python STT job.
    [switch]$ResetExistingJob,

    [int]$TimeoutSeconds = 180,
    [int]$PollMilliseconds = 250
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Fail-Test {
    param([string]$Message)

    Write-Host ""
    Write-Host "===================================================="
    Write-Host " PYTHON PROGRESS -> JAVA E2E TEST FAILED"
    Write-Host "===================================================="
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
        Fail-Test "Could not find psql.exe. Pass -PsqlPath explicitly."
    }

    return $candidate
}

function Invoke-Psql {
    param([string]$Sql)

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

    return $raw
}

function Invoke-PsqlRow {
    param([string]$Sql)

    $raw = Invoke-Psql -Sql $Sql

    return $raw |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -First 1
}

function Get-LatestMinutesJob {
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

    $line = Invoke-PsqlRow -Sql $sql

    if ([string]::IsNullOrWhiteSpace($line)) {
        return $null
    }

    $parts = $line.Split("|", 7)

    if ($parts.Count -lt 7) {
        Fail-Test "Unexpected meeting_minutes_jobs row: $line"
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

function Reset-LatestJobForLocalTest {
    param([int]$JobId)

    Write-Host ""
    Write-Host "=== RESET LOCAL TEST JOB ==="
    Write-Host "Resetting jobId=$JobId to QUEUED / VALIDATING / 0 and clearing stt_processing_id."
    Write-Host "This is intended ONLY for the local development test database."

    $sql = @"
UPDATE meeting_minutes_jobs
SET
    status = 'QUEUED',
    stage = 'VALIDATING',
    progress_percent = 0,
    stt_processing_id = NULL,
    error_message = NULL
WHERE id = $JobId;
"@

    [void](Invoke-Psql -Sql $sql)

    Write-Host "PASS: local test job reset."
}

function Get-PythonJobStatus {
    param([string]$ProcessingId)

    try {
        return Invoke-RestMethod `
            -Method Get `
            -Uri "$PythonBaseUrl/api/postprocessing/stt/jobs/$ProcessingId"
    }
    catch {
        return $null
    }
}

function Show-Job {
    param(
        [string]$Prefix,
        $Job
    )

    Write-Host (
        "{0} JOB={1} STATUS={2} STAGE={3} PROGRESS={4}% PROCESSING_ID={5}" -f `
        $Prefix,
        $Job.id,
        $Job.status,
        $Job.stage,
        $Job.progressPercent,
        $Job.processingId
    )
}

Write-Host "===================================================="
Write-Host " PYTHON PROGRESS -> JAVA E2E RUNTIME TEST"
Write-Host "===================================================="
Write-Host "MeetingId :" $MeetingId
Write-Host "Java      :" $JavaBaseUrl
Write-Host "Python    :" $PythonBaseUrl
Write-Host ""
Write-Host "IMPORTANT:"
Write-Host "  This script NEVER POSTs the Java progress callback endpoint manually."
Write-Host "  Java can reach 70% only if the running Python code sends callbacks itself."

$script:Psql = Find-Psql
Write-Host "psql      :" $script:Psql

Write-Host ""
Write-Host "=== 1. CHECK JAVA ==="

try {
    $javaHealth = Invoke-RestMethod `
        -Method Get `
        -Uri "$JavaBaseUrl/actuator/health"

    Write-Host "Java health:" $javaHealth.status

    if ($javaHealth.status -ne "UP") {
        Fail-Test "Java actuator health is not UP."
    }
}
catch {
    Fail-Test "Java is not reachable at $JavaBaseUrl"
}

Write-Host ""
Write-Host "=== 2. CHECK PYTHON ==="

try {
    $pythonHealth = Invoke-RestMethod `
        -Method Get `
        -Uri "$PythonBaseUrl/health"

    Write-Host "Python health: OK"
}
catch {
    Fail-Test "Python is not reachable at $PythonBaseUrl. Restart meeting-fastapi with the new callback code first."
}

Write-Host ""
Write-Host "=== 3. LOAD JAVA MINUTES JOB ==="

$job = Get-LatestMinutesJob

if ($null -eq $job) {
    Fail-Test "No meeting_minutes_jobs row found for meeting $MeetingId."
}

Show-Job -Prefix "Current" -Job $job

if ($ResetExistingJob) {
    Reset-LatestJobForLocalTest -JobId $job.id
}
else {
    if (
        $job.status -ne "QUEUED" -and
        -not (
            $job.status -eq "PROCESSING" -and
            $job.stage -eq "TRANSCRIBING" -and
            $job.progressPercent -le 10
        )
    ) {
        Fail-Test @"
Latest job is not fresh enough for an automatic E2E progress test.

Current:
  status   = $($job.status)
  stage    = $($job.stage)
  progress = $($job.progressPercent)%

For the existing local meeting 12 job that is already at 70%, rerun with:

  -ResetExistingJob

This resets ONLY the latest meeting_minutes_jobs row for this meeting to:
QUEUED / VALIDATING / 0, clears stt_processing_id, and lets the Java dispatcher submit a new Python job.
"@
    }
}

Write-Host ""
Write-Host "=== 4. WAIT FOR JAVA -> PYTHON DISPATCH ==="

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$processingId = $null
$javaObservedProgress = New-Object System.Collections.Generic.List[int]
$pythonObservedProgress = New-Object System.Collections.Generic.List[double]
$lastJavaProgress = -1
$lastPrintedPythonProgress = $null

while ((Get-Date) -lt $deadline) {
    $job = Get-LatestMinutesJob

    if ($null -eq $job) {
        Fail-Test "Minutes job disappeared."
    }

    if ($job.status -eq "FAILED") {
        Fail-Test "Java Minutes job became FAILED. error_message=$($job.errorMessage)"
    }

    if (
        $job.status -eq "PROCESSING" -and
        $job.stage -eq "TRANSCRIBING" -and
        -not [string]::IsNullOrWhiteSpace($job.processingId)
    ) {
        $processingId = $job.processingId
        Show-Job -Prefix "Dispatched" -Job $job
        break
    }

    Start-Sleep -Milliseconds $PollMilliseconds
}

if ([string]::IsNullOrWhiteSpace($processingId)) {
    Fail-Test "Timed out waiting for Java dispatcher to store stt_processing_id and reach PROCESSING / TRANSCRIBING."
}

Write-Host ""
Write-Host "=== 5. OBSERVE REAL PYTHON CALLBACKS UPDATING JAVA ==="
Write-Host "Python processingId:" $processingId

$reached70 = $false
$pythonTerminal = $false
$pythonFinalStatus = $null
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
    $job = Get-LatestMinutesJob

    if ($null -eq $job) {
        Fail-Test "Minutes job disappeared during callback observation."
    }

    if ($job.processingId -ne $processingId) {
        Fail-Test "Java stt_processing_id changed unexpectedly from $processingId to $($job.processingId)."
    }

    if ($job.status -eq "FAILED") {
        Fail-Test "Java Minutes job became FAILED. error_message=$($job.errorMessage)"
    }

    if ($job.progressPercent -lt $lastJavaProgress) {
        Fail-Test "Java progress regressed: $lastJavaProgress% -> $($job.progressPercent)%"
    }

    if ($job.progressPercent -gt 70) {
        Fail-Test "Java progress exceeded 70 during STT progress-only stage: $($job.progressPercent)%"
    }

    if ($job.progressPercent -ne $lastJavaProgress) {
        $javaObservedProgress.Add($job.progressPercent)
        Write-Host (
            "JAVA   STATUS={0} STAGE={1} PROGRESS={2}%" -f `
            $job.status,
            $job.stage,
            $job.progressPercent
        )
        $lastJavaProgress = $job.progressPercent
    }

    $pythonJob = Get-PythonJobStatus -ProcessingId $processingId

    if ($null -ne $pythonJob) {
        $pyProgress = 0.0

        if ($null -ne $pythonJob.progressPercent) {
            $pyProgress = [double]$pythonJob.progressPercent
        }

        if (
            $null -eq $lastPrintedPythonProgress -or
            $pyProgress -ne $lastPrintedPythonProgress
        ) {
            $pythonObservedProgress.Add($pyProgress)
            Write-Host (
                "PYTHON STATUS={0} PROGRESS={1}%" -f `
                $pythonJob.status,
                $pyProgress
            )
            $lastPrintedPythonProgress = $pyProgress
        }

        if (
            $pythonJob.status -eq "COMPLETED" -or
            $pythonJob.status -eq "PARTIAL"
        ) {
            $pythonTerminal = $true
            $pythonFinalStatus = $pythonJob.status
        }

        if ($pythonJob.status -eq "FAILED") {
            Fail-Test "Python STT job became FAILED."
        }
    }

    if (
        $job.progressPercent -eq 70 -and
        $job.status -eq "PROCESSING" -and
        $job.stage -eq "TRANSCRIBING"
    ) {
        $reached70 = $true

        # Python completion and the background 100% callback can finish very close
        # together. Give the Python status endpoint a short grace period.
        if ($pythonTerminal) {
            break
        }
    }

    Start-Sleep -Milliseconds $PollMilliseconds
}

if (-not $reached70) {
    Fail-Test @"
Java did not reach 70%.

Because this script does NOT manually POST the callback endpoint,
failure here means the real Python -> Java progress callback path has not been proven.
"@
}

if (-not $pythonTerminal) {
    # Final short verification after Java already reached 70.
    for ($i = 0; $i -lt 20; $i++) {
        $pythonJob = Get-PythonJobStatus -ProcessingId $processingId

        if ($null -ne $pythonJob) {
            if (
                $pythonJob.status -eq "COMPLETED" -or
                $pythonJob.status -eq "PARTIAL"
            ) {
                $pythonTerminal = $true
                $pythonFinalStatus = $pythonJob.status
                break
            }

            if ($pythonJob.status -eq "FAILED") {
                Fail-Test "Python STT job became FAILED after Java reached 70%."
            }
        }

        Start-Sleep -Milliseconds 250
    }
}

if (-not $pythonTerminal) {
    Fail-Test "Java reached 70%, but Python job did not reach COMPLETED/PARTIAL within the verification window."
}

Write-Host ""
Write-Host "=== 6. FINAL JAVA STATE ==="

$finalJob = Get-LatestMinutesJob
Show-Job -Prefix "Final" -Job $finalJob

if ($finalJob.status -ne "PROCESSING") {
    Fail-Test "Expected final Java status PROCESSING, actual=$($finalJob.status)"
}

if ($finalJob.stage -ne "TRANSCRIBING") {
    Fail-Test "Expected final Java stage TRANSCRIBING, actual=$($finalJob.stage)"
}

if ($finalJob.progressPercent -ne 70) {
    Fail-Test "Expected final Java progress 70%, actual=$($finalJob.progressPercent)%"
}

if ($finalJob.processingId -ne $processingId) {
    Fail-Test "Final stt_processing_id changed unexpectedly."
}

Write-Host ""
Write-Host "===================================================="
Write-Host " PYTHON PROGRESS -> JAVA E2E TEST PASS"
Write-Host "===================================================="
Write-Host "Confirmed:"
Write-Host "  Java dispatcher created/submitted a real Python STT job"
Write-Host "  Python processingId was persisted by Java"
Write-Host "  Python ran STT and reported progress automatically"
Write-Host "  This script did NOT manually call the Java progress endpoint"
Write-Host "  Java progress was monotonic"
Write-Host "  Java reached PROCESSING / TRANSCRIBING / 70%"
Write-Host "  Python final status: $pythonFinalStatus"
Write-Host "  Java did NOT move to COMPLETED"
Write-Host ""
Write-Host "Observed Java progress:"
Write-Host ("  " + (($javaObservedProgress | ForEach-Object { "$_%" }) -join " -> "))
Write-Host ""
Write-Host "Observed Python progress:"
Write-Host ("  " + (($pythonObservedProgress | ForEach-Object { "$_%" }) -join " -> "))
Write-Host ""
Write-Host "Checkpoint complete:"
Write-Host "  Python progress -> Java  ✅"
Write-Host ""
Write-Host "NEXT:"
Write-Host "  Python completion -> Java"
