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

    # LOCAL DEV ONLY.
    # Resets the latest Minutes job for this meeting so Java dispatches
    # a fresh Python STT job and we can observe a REAL completion callback.
    [switch]$ResetExistingJob,

    [int]$TimeoutSeconds = 240,
    [int]$PollMilliseconds = 250
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Fail-Test {
    param([string]$Message)

    Write-Host ""
    Write-Host "======================================================"
    Write-Host " PYTHON COMPLETION -> JAVA E2E TEST FAILED"
    Write-Host "======================================================"
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

function Assert-CompletionColumnsExist {
    $sql = @"
SELECT COUNT(*)
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'meeting_minutes_jobs'
  AND column_name IN (
      'stt_status',
      'stt_total_manifests',
      'stt_total_segments',
      'stt_total_chunks',
      'stt_processed_chunks',
      'stt_succeeded_chunks',
      'stt_failed_chunks',
      'stt_error_message'
  );
"@

    $line = Invoke-PsqlRow -Sql $sql

    if ([string]::IsNullOrWhiteSpace($line)) {
        Fail-Test "Could not verify STT completion metadata columns."
    }

    $count = [int]$line

    if ($count -ne 8) {
        Fail-Test @"
Expected 8 STT completion metadata columns in meeting_minutes_jobs, found $count.

Restart Java so Flyway applies the new completion migration before running this E2E test.
"@
    }
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
    COALESCE(stt_status, ''),
    COALESCE(stt_total_manifests, 0),
    COALESCE(stt_total_segments, 0),
    COALESCE(stt_total_chunks, 0),
    COALESCE(stt_processed_chunks, 0),
    COALESCE(stt_succeeded_chunks, 0),
    COALESCE(stt_failed_chunks, 0),
    COALESCE(stt_error_message, ''),
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

    $parts = $line.Split("|", 15)

    if ($parts.Count -lt 15) {
        Fail-Test "Unexpected meeting_minutes_jobs row format: $line"
    }

    return [PSCustomObject]@{
        id                  = [int]$parts[0]
        meetingId           = [int]$parts[1]
        status              = $parts[2]
        stage               = $parts[3]
        progressPercent     = [int]$parts[4]
        processingId        = $parts[5]
        sttStatus           = $parts[6]
        totalManifests      = [int]$parts[7]
        totalSegments       = [int]$parts[8]
        totalChunks         = [int]$parts[9]
        processedChunks     = [int]$parts[10]
        succeededChunks     = [int]$parts[11]
        failedChunks        = [int]$parts[12]
        sttErrorMessage     = $parts[13]
        errorMessage        = $parts[14]
    }
}

function Show-Job {
    param(
        [string]$Prefix,
        $Job
    )

    Write-Host (
        "{0} JOB={1} STATUS={2} STAGE={3} PROGRESS={4}% STT_STATUS={5} PROCESSING_ID={6}" -f `
        $Prefix,
        $Job.id,
        $Job.status,
        $Job.stage,
        $Job.progressPercent,
        $Job.sttStatus,
        $Job.processingId
    )
}

function Reset-LatestJobForLocalTest {
    param([int]$JobId)

    Write-Host ""
    Write-Host "=== RESET LOCAL TEST JOB ==="
    Write-Host "Resetting ONLY latest Minutes job id=$JobId for meeting $MeetingId."
    Write-Host "LOCAL DEV ONLY."

    $sql = @"
UPDATE meeting_minutes_jobs
SET
    status = 'QUEUED',
    stage = 'VALIDATING',
    progress_percent = 0,
    stt_processing_id = NULL,
    stt_status = NULL,
    stt_total_manifests = NULL,
    stt_total_segments = NULL,
    stt_total_chunks = NULL,
    stt_processed_chunks = NULL,
    stt_succeeded_chunks = NULL,
    stt_failed_chunks = NULL,
    stt_error_message = NULL,
    error_message = NULL
WHERE id = $JobId;
"@

    [void](Invoke-Psql -Sql $sql)
    Write-Host "PASS: latest local test job reset."
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

function Get-PythonResult {
    param([string]$ProcessingId)

    try {
        $response = Invoke-WebRequest `
            -Method Get `
            -Uri "$PythonBaseUrl/api/postprocessing/stt/jobs/$ProcessingId/result" `
            -UseBasicParsing

        return [PSCustomObject]@{
            StatusCode = [int]$response.StatusCode
            Content = $response.Content
        }
    }
    catch {
        try {
            return [PSCustomObject]@{
                StatusCode = [int]$_.Exception.Response.StatusCode
                Content = ""
            }
        }
        catch {
            return $null
        }
    }
}

Write-Host "======================================================"
Write-Host " PYTHON COMPLETION -> JAVA E2E RUNTIME TEST"
Write-Host "======================================================"
Write-Host "MeetingId :" $MeetingId
Write-Host "Java      :" $JavaBaseUrl
Write-Host "Python    :" $PythonBaseUrl
Write-Host ""
Write-Host "IMPORTANT:"
Write-Host "  This script NEVER POSTs /completion manually."
Write-Host "  BUILDING_TRANSCRIPT + stt_status=COMPLETED can only be reached"
Write-Host "  if the running Python code sends the real completion callback."

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
    [void](Invoke-RestMethod `
        -Method Get `
        -Uri "$PythonBaseUrl/health")

    Write-Host "Python health: OK"
}
catch {
    Fail-Test "Python is not reachable at $PythonBaseUrl. Restart meeting-fastapi with completion sender code first."
}

Write-Host ""
Write-Host "=== 3. CHECK JAVA COMPLETION MIGRATION ==="

Assert-CompletionColumnsExist
Write-Host "PASS: 8 STT completion metadata columns exist."

Write-Host ""
Write-Host "=== 4. LOAD CURRENT JAVA MINUTES JOB ==="

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
            (
                $job.stage -eq "TRANSCRIBING" -or
                $job.stage -eq "BUILDING_TRANSCRIPT"
            ) -and
            [string]::IsNullOrWhiteSpace($job.sttStatus)
        )
    ) {
        Fail-Test @"
Latest job is not a fresh completion-test job.

Use:
  -ResetExistingJob

on your LOCAL DEV database to dispatch a new Python STT job.
"@
    }
}

Write-Host ""
Write-Host "=== 5. WAIT FOR JAVA -> PYTHON DISPATCH ==="

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$processingId = $null
$lastJavaProgress = -1

while ((Get-Date) -lt $deadline) {
    $job = Get-LatestMinutesJob

    if ($null -eq $job) {
        Fail-Test "Minutes job disappeared."
    }

    if ($job.status -eq "FAILED") {
        Fail-Test "Java Minutes job became FAILED before completion. error_message=$($job.errorMessage)"
    }

    if (
        $job.status -eq "PROCESSING" -and
        -not [string]::IsNullOrWhiteSpace($job.processingId)
    ) {
        $processingId = $job.processingId
        Show-Job -Prefix "Dispatched" -Job $job
        break
    }

    Start-Sleep -Milliseconds $PollMilliseconds
}

if ([string]::IsNullOrWhiteSpace($processingId)) {
    Fail-Test "Timed out waiting for Java to dispatch a Python STT job and persist stt_processing_id."
}

Write-Host ""
Write-Host "=== 6. OBSERVE PROGRESS + REAL COMPLETION CALLBACK ==="
Write-Host "Python processingId:" $processingId

$pythonTerminal = $false
$pythonFinalStatus = $null
$javaCompletionObserved = $false
$lastJavaStage = ""
$lastJavaSttStatus = ""
$lastPythonProgress = $null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
    $job = Get-LatestMinutesJob

    if ($null -eq $job) {
        Fail-Test "Minutes job disappeared during E2E observation."
    }

    if ($job.processingId -ne $processingId) {
        Fail-Test "Java stt_processing_id changed unexpectedly from $processingId to $($job.processingId)."
    }

    if ($job.progressPercent -lt $lastJavaProgress) {
        Fail-Test "Java progress regressed: $lastJavaProgress% -> $($job.progressPercent)%"
    }

    if (
        $job.progressPercent -ne $lastJavaProgress -or
        $job.stage -ne $lastJavaStage -or
        $job.sttStatus -ne $lastJavaSttStatus
    ) {
        Write-Host (
            "JAVA   STATUS={0} STAGE={1} PROGRESS={2}% STT_STATUS={3}" -f `
            $job.status,
            $job.stage,
            $job.progressPercent,
            $job.sttStatus
        )

        $lastJavaProgress = $job.progressPercent
        $lastJavaStage = $job.stage
        $lastJavaSttStatus = $job.sttStatus
    }

    if ($job.status -eq "FAILED") {
        Fail-Test @"
Java Minutes job became FAILED.
stt_status=$($job.sttStatus)
stt_error_message=$($job.sttErrorMessage)
error_message=$($job.errorMessage)
"@
    }

    $pythonJob = Get-PythonJobStatus -ProcessingId $processingId

    if ($null -ne $pythonJob) {
        $pyProgress = 0.0
        if ($null -ne $pythonJob.progressPercent) {
            $pyProgress = [double]$pythonJob.progressPercent
        }

        if ($null -eq $lastPythonProgress -or $pyProgress -ne $lastPythonProgress) {
            Write-Host (
                "PYTHON STATUS={0} PROGRESS={1}%" -f `
                $pythonJob.status,
                $pyProgress
            )
            $lastPythonProgress = $pyProgress
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
        $job.status -eq "PROCESSING" -and
        $job.stage -eq "BUILDING_TRANSCRIPT" -and
        $job.progressPercent -eq 70 -and
        $job.sttStatus -eq "COMPLETED"
    ) {
        $javaCompletionObserved = $true

        if ($pythonTerminal) {
            break
        }
    }

    Start-Sleep -Milliseconds $PollMilliseconds
}

if (-not $pythonTerminal) {
    Fail-Test "Python did not reach COMPLETED/PARTIAL within timeout."
}

if ($pythonFinalStatus -ne "COMPLETED") {
    Fail-Test "This real meeting test expected Python COMPLETED, actual=$pythonFinalStatus"
}

if (-not $javaCompletionObserved) {
    Fail-Test @"
Python reached COMPLETED, but Java did not reach:
  PROCESSING / BUILDING_TRANSCRIPT / 70%
  stt_status = COMPLETED

Because this script NEVER POSTs /completion manually,
the real Python completion -> Java callback has not been proven.
"@
}

Write-Host ""
Write-Host "=== 7. VERIFY COMPLETION METADATA IN JAVA DB ==="

$finalJob = Get-LatestMinutesJob
Show-Job -Prefix "Final" -Job $finalJob

if ($finalJob.status -ne "PROCESSING") {
    Fail-Test "Expected Java status PROCESSING, actual=$($finalJob.status)"
}

if ($finalJob.stage -ne "BUILDING_TRANSCRIPT") {
    Fail-Test "Expected Java stage BUILDING_TRANSCRIPT, actual=$($finalJob.stage)"
}

if ($finalJob.progressPercent -ne 70) {
    Fail-Test "Expected Java progress 70%, actual=$($finalJob.progressPercent)%"
}

if ($finalJob.sttStatus -ne "COMPLETED") {
    Fail-Test "Expected Java stt_status COMPLETED, actual=$($finalJob.sttStatus)"
}

if ($finalJob.totalManifests -le 0) {
    Fail-Test "Expected stt_total_manifests > 0, actual=$($finalJob.totalManifests)"
}

if ($finalJob.totalChunks -lt 0) {
    Fail-Test "Invalid stt_total_chunks=$($finalJob.totalChunks)"
}

if ($finalJob.processedChunks -ne $finalJob.totalChunks) {
    Fail-Test "COMPLETED invariant failed: processedChunks != totalChunks"
}

if ($finalJob.failedChunks -ne 0) {
    Fail-Test "COMPLETED invariant failed: failedChunks=$($finalJob.failedChunks), expected 0"
}

if ($finalJob.succeededChunks -ne $finalJob.processedChunks) {
    Fail-Test "COMPLETED invariant failed: succeededChunks != processedChunks"
}

Write-Host "Persisted STT summary:"
Write-Host "  totalManifests  =" $finalJob.totalManifests
Write-Host "  totalSegments   =" $finalJob.totalSegments
Write-Host "  totalChunks     =" $finalJob.totalChunks
Write-Host "  processedChunks =" $finalJob.processedChunks
Write-Host "  succeededChunks =" $finalJob.succeededChunks
Write-Host "  failedChunks    =" $finalJob.failedChunks

Write-Host ""
Write-Host "=== 8. VERIFY PYTHON RESULT IS ALREADY READY ==="
Write-Host "This checks the required ordering:"
Write-Host "  Python result ready -> completion callback -> Java BUILDING_TRANSCRIPT"

$resultResponse = Get-PythonResult -ProcessingId $processingId

if ($null -eq $resultResponse) {
    Fail-Test "Could not query Python result endpoint."
}

Write-Host "Python result HTTP:" $resultResponse.StatusCode

if ($resultResponse.StatusCode -ne 200) {
    Fail-Test @"
Java already received COMPLETED, but Python result endpoint returned HTTP $($resultResponse.StatusCode).

This violates the required ordering:
result must be ready BEFORE completion callback is sent.
"@
}

if ([string]::IsNullOrWhiteSpace($resultResponse.Content)) {
    Fail-Test "Python result endpoint returned 200 but empty body."
}

Write-Host "PASS: Python result endpoint is ready after Java completion transition."

Write-Host ""
Write-Host "======================================================"
Write-Host " PYTHON COMPLETION -> JAVA E2E TEST PASS"
Write-Host "======================================================"
Write-Host "Confirmed:"
Write-Host "  Java dispatched a real Python STT job"
Write-Host "  Python completed STT"
Write-Host "  Progress callback path still worked"
Write-Host "  This script NEVER manually POSTed /completion"
Write-Host "  Python automatically sent terminal completion"
Write-Host "  Java persisted stt_status=COMPLETED"
Write-Host "  Java persisted terminal STT summary counts"
Write-Host "  Java moved to PROCESSING / BUILDING_TRANSCRIPT / 70%"
Write-Host "  Java did NOT mark Minutes COMPLETED"
Write-Host "  Python result endpoint is already READY"
Write-Host ""
Write-Host "Checkpoint complete:"
Write-Host "  Python completion -> Java  ✅"
Write-Host ""
Write-Host "NEXT:"
Write-Host "  Java GET Python result"
