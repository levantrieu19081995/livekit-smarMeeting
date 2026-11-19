param(
    [int]$MeetingId = 12,

    [string]$JavaBaseUrl = "http://localhost:8080",
    [string]$PythonBaseUrl = "http://localhost:8000",

    [string]$InternalApiKey = "dev-ai-key",

    [string]$DbHost = "localhost",
    [int]$DbPort = 5432,
    [string]$DbName = "meeting_db",
    [string]$DbUser = "meeting_user",
    [string]$DbPassword = "meeting_password",

    [string]$PsqlPath = "",

    # LOCAL DEV ONLY:
    # reset latest Minutes job to QUEUED so Java dispatches a fresh STT job.
    [switch]$ResetExistingJob,

    # Runs the targeted Java tests that prove the newly added
    # SttClient.getResult + MeetingMinutesSttResultFetchService layer.
    [switch]$SkipJavaGetResultTests,

    [int]$TimeoutSeconds = 300,
    [int]$PollMilliseconds = 250
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Fail-Test {
    param([string]$Message)

    Write-Host ""
    Write-Host "======================================================"
    Write-Host " STT -> JAVA GET RESULT CHECKPOINT TEST FAILED"
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
        Fail-Test "Could not verify V10 completion metadata columns."
    }

    if ([int]$line -ne 8) {
        Fail-Test "Expected 8 STT completion metadata columns, found $line. Restart Java so Flyway applies V10."
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
        Fail-Test "Unexpected meeting_minutes_jobs row: $line"
    }

    return [PSCustomObject]@{
        id              = [long]$parts[0]
        meetingId       = [long]$parts[1]
        status          = $parts[2]
        stage           = $parts[3]
        progress        = [int]$parts[4]
        processingId    = $parts[5]
        sttStatus       = $parts[6]
        totalManifests  = [int]$parts[7]
        totalSegments   = [int]$parts[8]
        totalChunks     = [int]$parts[9]
        processedChunks = [int]$parts[10]
        succeededChunks = [int]$parts[11]
        failedChunks    = [int]$parts[12]
        sttError        = $parts[13]
        jobError        = $parts[14]
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
        $Job.progress,
        $Job.sttStatus,
        $Job.processingId
    )
}

function Reset-LatestJob {
    param([long]$JobId)

    Write-Host ""
    Write-Host "=== RESET LOCAL TEST JOB ==="
    Write-Host "Reset ONLY job id=$JobId for meeting=$MeetingId"
    Write-Host "LOCAL DEV ONLY"

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
    Write-Host "PASS: job reset."
}

function Invoke-PythonJson {
    param(
        [string]$Url
    )

    $headers = @{}

    if (-not [string]::IsNullOrWhiteSpace($InternalApiKey)) {
        $headers["X-Internal-Api-Key"] = $InternalApiKey
    }

    return Invoke-RestMethod `
        -Method Get `
        -Uri $Url `
        -Headers $headers
}

function Get-PythonJobStatus {
    param([string]$ProcessingId)

    try {
        return Invoke-PythonJson `
            -Url "$PythonBaseUrl/api/postprocessing/stt/jobs/$ProcessingId"
    }
    catch {
        return $null
    }
}

function Get-PythonResult {
    param([string]$ProcessingId)

    try {
        return Invoke-PythonJson `
            -Url "$PythonBaseUrl/api/postprocessing/stt/jobs/$ProcessingId/result"
    }
    catch {
        Fail-Test "Python GET result failed: $($_.Exception.Message)"
    }
}

function Assert-PythonResultMatchesJava {
    param(
        $PythonOuter,
        $JavaJob,
        [string]$ProcessingId
    )

    if ($null -eq $PythonOuter) {
        Fail-Test "Python result response is null."
    }

    if ($PythonOuter.processingId -ne $ProcessingId) {
        Fail-Test "Python outer processingId mismatch. expected=$ProcessingId actual=$($PythonOuter.processingId)"
    }

    if ($null -eq $PythonOuter.result) {
        Fail-Test "Python result body is null."
    }

    $result = $PythonOuter.result

    if ([long]$result.meetingId -ne $MeetingId) {
        Fail-Test "meetingId mismatch. Java/test=$MeetingId Python=$($result.meetingId)"
    }

    if ($result.status -ne $JavaJob.sttStatus) {
        Fail-Test "terminal status mismatch. Java=$($JavaJob.sttStatus) Python=$($result.status)"
    }

    $checks = @(
        @("totalManifests",  [int]$JavaJob.totalManifests,  [int]$result.totalManifests),
        @("totalSegments",   [int]$JavaJob.totalSegments,   [int]$result.totalSegments),
        @("totalChunks",     [int]$JavaJob.totalChunks,     [int]$result.totalChunks),
        @("processedChunks", [int]$JavaJob.processedChunks, [int]$result.processedChunks),
        @("succeededChunks", [int]$JavaJob.succeededChunks, [int]$result.succeededChunks),
        @("failedChunks",    [int]$JavaJob.failedChunks,    [int]$result.failedChunks)
    )

    foreach ($check in $checks) {
        $name = $check[0]
        $javaValue = [int]$check[1]
        $pythonValue = [int]$check[2]

        if ($javaValue -ne $pythonValue) {
            Fail-Test "$name mismatch. Java=$javaValue Python=$pythonValue"
        }
    }

    $transcripts = @($result.transcripts)
    $failures = @($result.failures)

    if ($transcripts.Count -ne [int]$result.succeededChunks) {
        Fail-Test "Python transcript list size mismatch. list=$($transcripts.Count) succeededChunks=$($result.succeededChunks)"
    }

    if ($failures.Count -ne [int]$result.failedChunks) {
        Fail-Test "Python failures list size mismatch. list=$($failures.Count) failedChunks=$($result.failedChunks)"
    }

    if ($result.status -eq "COMPLETED") {
        if ([int]$result.failedChunks -ne 0) {
            Fail-Test "COMPLETED result has failedChunks=$($result.failedChunks)"
        }

        if ($failures.Count -ne 0) {
            Fail-Test "COMPLETED result has non-empty failures list."
        }
    }

    return $result
}

function Run-JavaGetResultTargetedTests {
    Write-Host ""
    Write-Host "=== JAVA GET RESULT LAYER TARGETED TESTS ==="
    Write-Host "These tests verify the Java SttClient.getResult DTO/validation layer."
    Write-Host "They do NOT create a production runtime fetch trigger."

    & mvn `
        '-Dtest=SttClientImplTest,MeetingMinutesSttResultFetchServiceTest,MeetingMinutesSttJobStateServiceTest' `
        test

    if ($LASTEXITCODE -ne 0) {
        Fail-Test "Java GET-result targeted tests failed."
    }

    Write-Host "PASS: Java GET-result client/service targeted tests."
}

Write-Host "======================================================"
Write-Host " STT -> JAVA GET RESULT CHECKPOINT TEST"
Write-Host "======================================================"
Write-Host "MeetingId :" $MeetingId
Write-Host "Java      :" $JavaBaseUrl
Write-Host "Python    :" $PythonBaseUrl
Write-Host ""
Write-Host "This script verifies the pipeline from a fresh Java STT dispatch through:"
Write-Host "  Java -> Python POST job"
Write-Host "  Python progress -> Java"
Write-Host "  Python completion -> Java"
Write-Host "  Python result READY and consistent with Java completion metadata"
Write-Host "  Java GET-result client/service targeted tests"
Write-Host ""
Write-Host "IMPORTANT:"
Write-Host "  Current production Java code has NO scheduler/controller that invokes"
Write-Host "  MeetingMinutesSttResultFetchService.fetchResult(jobId) yet."
Write-Host "  Therefore this script does NOT falsely claim a runtime Java GET."
Write-Host "  Runtime Java GET will be proven when transcript persistence is wired."

$script:Psql = Find-Psql
Write-Host "psql      :" $script:Psql

Write-Host ""
Write-Host "=== 1. HEALTH CHECK ==="

try {
    $javaHealth = Invoke-RestMethod -Method Get -Uri "$JavaBaseUrl/actuator/health"
    if ($javaHealth.status -ne "UP") {
        Fail-Test "Java health is not UP."
    }
    Write-Host "Java health  : UP"
}
catch {
    Fail-Test "Java is not reachable at $JavaBaseUrl"
}

try {
    [void](Invoke-RestMethod -Method Get -Uri "$PythonBaseUrl/health")
    Write-Host "Python health: OK"
}
catch {
    Fail-Test "Python is not reachable at $PythonBaseUrl"
}

Write-Host ""
Write-Host "=== 2. VERIFY JAVA COMPLETION SCHEMA ==="

Assert-CompletionColumnsExist
Write-Host "PASS: completion metadata columns exist."

Write-Host ""
Write-Host "=== 3. LOAD/RESET MINUTES JOB ==="

$job = Get-LatestMinutesJob

if ($null -eq $job) {
    Fail-Test "No meeting_minutes_jobs row found for meeting=$MeetingId."
}

Show-Job -Prefix "Current" -Job $job

if ($ResetExistingJob) {
    Reset-LatestJob -JobId $job.id
}
else {
    if (
        $job.status -ne "QUEUED" -and
        -not (
            $job.status -eq "PROCESSING" -and
            $job.stage -eq "TRANSCRIBING" -and
            [string]::IsNullOrWhiteSpace($job.sttStatus)
        )
    ) {
        Fail-Test @"
Latest job is not a fresh STT test job.

Run again with:
  -ResetExistingJob

on LOCAL DEV.
"@
    }
}

Write-Host ""
Write-Host "=== 4. JAVA -> PYTHON STT DISPATCH ==="

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$processingId = ""

while ((Get-Date) -lt $deadline) {
    $job = Get-LatestMinutesJob

    if ($job.status -eq "FAILED") {
        Fail-Test "Java job failed before dispatch/completion. error=$($job.jobError)"
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
    Fail-Test "Timed out waiting for Java -> Python STT dispatch."
}

Write-Host "PASS: Java persisted Python processingId=$processingId"

Write-Host ""
Write-Host "=== 5. PROGRESS + COMPLETION ==="

$lastJavaState = ""
$lastPythonState = ""
$pythonTerminal = $false
$pythonFinalStatus = ""

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
    $job = Get-LatestMinutesJob

    if ($job.processingId -ne $processingId) {
        Fail-Test "stt_processing_id changed unexpectedly."
    }

    $javaState = "$($job.status)|$($job.stage)|$($job.progress)|$($job.sttStatus)"
    if ($javaState -ne $lastJavaState) {
        Write-Host (
            "JAVA   STATUS={0} STAGE={1} PROGRESS={2}% STT_STATUS={3}" -f `
            $job.status,
            $job.stage,
            $job.progress,
            $job.sttStatus
        )
        $lastJavaState = $javaState
    }

    if ($job.status -eq "FAILED") {
        Fail-Test @"
Java Minutes job FAILED.
sttStatus=$($job.sttStatus)
sttError=$($job.sttError)
jobError=$($job.jobError)
"@
    }

    $pythonJob = Get-PythonJobStatus -ProcessingId $processingId

    if ($null -ne $pythonJob) {
        $pythonState = "$($pythonJob.status)|$($pythonJob.progressPercent)"
        if ($pythonState -ne $lastPythonState) {
            Write-Host (
                "PYTHON STATUS={0} PROGRESS={1}%" -f `
                $pythonJob.status,
                $pythonJob.progressPercent
            )
            $lastPythonState = $pythonState
        }

        if (
            $pythonJob.status -eq "COMPLETED" -or
            $pythonJob.status -eq "PARTIAL"
        ) {
            $pythonTerminal = $true
            $pythonFinalStatus = $pythonJob.status
        }

        if ($pythonJob.status -eq "FAILED") {
            Fail-Test "Python STT job FAILED."
        }
    }

    if (
        $pythonTerminal -and
        $job.status -eq "PROCESSING" -and
        $job.stage -eq "BUILDING_TRANSCRIPT" -and
        $job.progress -eq 70 -and
        (
            $job.sttStatus -eq "COMPLETED" -or
            $job.sttStatus -eq "PARTIAL"
        )
    ) {
        break
    }

    Start-Sleep -Milliseconds $PollMilliseconds
}

if (-not $pythonTerminal) {
    Fail-Test "Python did not reach COMPLETED/PARTIAL."
}

$job = Get-LatestMinutesJob

if ($job.stage -ne "BUILDING_TRANSCRIPT") {
    Fail-Test "Expected Java stage BUILDING_TRANSCRIPT, actual=$($job.stage)"
}

if ($job.progress -ne 70) {
    Fail-Test "Expected Java progress 70, actual=$($job.progress)"
}

if ($job.sttStatus -ne $pythonFinalStatus) {
    Fail-Test "Java/Python terminal status mismatch. Java=$($job.sttStatus) Python=$pythonFinalStatus"
}

Write-Host "PASS: Python completion -> Java reached BUILDING_TRANSCRIPT / 70."

Write-Host ""
Write-Host "=== 6. VERIFY PYTHON RESULT IS READY ==="

$pythonOuter = Get-PythonResult -ProcessingId $processingId

if ($null -eq $pythonOuter.result) {
    Fail-Test "Python result endpoint returned no result."
}

Write-Host "PASS: Python GET /result returned data."

Write-Host ""
Write-Host "=== 7. VERIFY RESULT CONSISTENCY AGAINST JAVA DB ==="

$result = Assert-PythonResultMatchesJava `
    -PythonOuter $pythonOuter `
    -JavaJob $job `
    -ProcessingId $processingId

Write-Host "PASS: Python result matches Java persisted completion metadata."
Write-Host ""
Write-Host "Result summary:"
Write-Host "  processingId    =" $processingId
Write-Host "  meetingId       =" $result.meetingId
Write-Host "  status          =" $result.status
Write-Host "  totalManifests  =" $result.totalManifests
Write-Host "  totalSegments   =" $result.totalSegments
Write-Host "  totalChunks     =" $result.totalChunks
Write-Host "  processedChunks =" $result.processedChunks
Write-Host "  succeededChunks =" $result.succeededChunks
Write-Host "  failedChunks    =" $result.failedChunks
Write-Host "  transcripts     =" @($result.transcripts).Count
Write-Host "  failures        =" @($result.failures).Count

if (-not $SkipJavaGetResultTests) {
    Run-JavaGetResultTargetedTests
}
else {
    Write-Host ""
    Write-Host "=== 8. JAVA GET RESULT TESTS ==="
    Write-Host "SKIPPED by -SkipJavaGetResultTests"
}

Write-Host ""
Write-Host "======================================================"
Write-Host " STT -> JAVA GET RESULT CHECKPOINT TEST PASS"
Write-Host "======================================================"
Write-Host "Confirmed:"
Write-Host "  Java -> Python POST STT                 PASS"
Write-Host "  Python progress -> Java                 PASS"
Write-Host "  Python completion -> Java               PASS"
Write-Host "  Java DB completion summary              PASS"
Write-Host "  Python GET result READY                 PASS"
Write-Host "  Result matches Java completion metadata PASS"

if (-not $SkipJavaGetResultTests) {
    Write-Host "  Java SttClient.getResult tests           PASS"
    Write-Host "  Java ResultFetchService tests            PASS"
}

Write-Host ""
Write-Host "Current Java production state:"
Write-Host "  STATUS      = PROCESSING"
Write-Host "  STAGE       = BUILDING_TRANSCRIPT"
Write-Host "  PROGRESS    = 70"
Write-Host "  STT_STATUS  = $($job.sttStatus)"
Write-Host ""
Write-Host "IMPORTANT:"
Write-Host "  This is the maximum honest checkpoint before transcript persistence."
Write-Host "  Java GET-result capability exists, but no runtime dispatcher invokes it yet."
Write-Host ""
Write-Host "NEXT:"
Write-Host "  Java persist transcript"
Write-Host "  -> dispatcher calls MeetingMinutesSttResultFetchService.fetchResult(jobId)"
Write-Host "  -> real runtime Java GET /result"
Write-Host "  -> persist transcript"
Write-Host "  -> READY / 80"
