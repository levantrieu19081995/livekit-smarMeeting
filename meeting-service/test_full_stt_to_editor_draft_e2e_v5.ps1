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

    # LOCAL DEV ONLY.
    # Deletes transcript rows for the latest Minutes job, then resets that SAME job
    # to QUEUED so the full pipeline can be rerun without duplicate-key failures.
    [switch]$ResetExistingJob,

    # By default this E2E requires at least one semantic CHAT row so that
    # VOICE + CHAT is genuinely exercised. Use -AllowNoChat only for voice-only testing.
    [switch]$AllowNoChat,

    # Skip the temporary real-DB Java MeetingMinutesTimelineService probe.
    [switch]$SkipJavaTimelineLiveProbe,

    # Skip targeted Java regression tests at the end.
    [switch]$SkipTargetedTests,

    [string]$TimelineZoneId = "Asia/Ho_Chi_Minh",

    [int]$TimeoutSeconds = 300,
    [int]$PollMilliseconds = 250
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:PGCLIENTENCODING = "UTF8"

function Fail-Test {
    param([string]$Message)

    Write-Host ""
    Write-Host "======================================================"
    Write-Host " STT -> VOICE + CHAT -> EDITOR DRAFT E2E FAILED"
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
        -v ON_ERROR_STOP=1 `
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

function Invoke-PsqlScalar {
    param([string]$Sql)

    $line = Invoke-PsqlRow -Sql $Sql

    if ($null -eq $line) {
        return ""
    }

    return [string]$line
}

function Assert-TranscriptSchemaExists {
    $columnSql = @"
SELECT COUNT(*)
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'meeting_minutes_jobs'
  AND column_name IN (
      'transcript_status',
      'transcript_attempts',
      'transcript_last_error',
      'transcript_persisted_at'
  );
"@

    $columnCount = Invoke-PsqlScalar -Sql $columnSql

    if ([int]$columnCount -ne 4) {
        Fail-Test "Expected 4 transcript-state columns on meeting_minutes_jobs, found $columnCount. Restart Java so Flyway V11 is applied."
    }

    $tableSql = @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
      'meeting_transcript_chunks',
      'meeting_transcript_failures'
  );
"@

    $tableCount = Invoke-PsqlScalar -Sql $tableSql

    if ([int]$tableCount -ne 2) {
        Fail-Test "Expected meeting_transcript_chunks and meeting_transcript_failures tables, found $tableCount."
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
    COALESCE(transcript_status, ''),
    COALESCE(transcript_attempts, 0),
    COALESCE(transcript_last_error, ''),
    COALESCE(transcript_persisted_at::text, ''),
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

    $parts = $line.Split("|", 19)

    if ($parts.Count -lt 19) {
        Fail-Test "Unexpected meeting_minutes_jobs row: $line"
    }

    return [PSCustomObject]@{
        id                    = [long]$parts[0]
        meetingId             = [long]$parts[1]
        status                = $parts[2]
        stage                 = $parts[3]
        progress              = [int]$parts[4]
        processingId          = $parts[5]
        sttStatus             = $parts[6]
        totalManifests        = [int]$parts[7]
        totalSegments         = [int]$parts[8]
        totalChunks           = [int]$parts[9]
        processedChunks       = [int]$parts[10]
        succeededChunks       = [int]$parts[11]
        failedChunks          = [int]$parts[12]
        sttError              = $parts[13]
        transcriptStatus      = $parts[14]
        transcriptAttempts    = [int]$parts[15]
        transcriptLastError   = $parts[16]
        transcriptPersistedAt = $parts[17]
        jobError              = $parts[18]
    }
}

function Show-Job {
    param(
        [string]$Prefix,
        $Job
    )

    Write-Host (
        "{0} JOB={1} STATUS={2} STAGE={3} PROGRESS={4}% STT={5} TRANSCRIPT={6} T_ATTEMPTS={7} PROCESSING_ID={8}" -f `
        $Prefix,
        $Job.id,
        $Job.status,
        $Job.stage,
        $Job.progress,
        $Job.sttStatus,
        $Job.transcriptStatus,
        $Job.transcriptAttempts,
        $Job.processingId
    )
}

function Reset-LatestJob {
    param([long]$JobId)

    Write-Host ""
    Write-Host "=== CLEAN RESET LOCAL TEST JOB ==="
    Write-Host "JobId=$JobId MeetingId=$MeetingId"
    Write-Host "LOCAL DEV ONLY"
    Write-Host "Deleting old transcript rows first so rerun cannot violate V11 unique constraints."

    $sql = @"
BEGIN;

DELETE FROM meeting_transcript_failures
WHERE minutes_job_id = $JobId;

DELETE FROM meeting_transcript_chunks
WHERE minutes_job_id = $JobId;

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

    transcript_status = NULL,
    transcript_attempts = 0,
    transcript_last_error = NULL,
    transcript_persisted_at = NULL,

    started_at = NULL,
    finished_at = NULL,
    error_message = NULL
WHERE id = $JobId;

COMMIT;
"@

    [void](Invoke-Psql -Sql $sql)

    $chunkCount = [int](Invoke-PsqlScalar -Sql "SELECT COUNT(*) FROM meeting_transcript_chunks WHERE minutes_job_id = $JobId;")
    $failureCount = [int](Invoke-PsqlScalar -Sql "SELECT COUNT(*) FROM meeting_transcript_failures WHERE minutes_job_id = $JobId;")

    if ($chunkCount -ne 0 -or $failureCount -ne 0) {
        Fail-Test "Reset did not clean transcript rows. chunks=$chunkCount failures=$failureCount"
    }

    Write-Host "PASS: old transcript rows deleted and job reset to QUEUED."
}

function Invoke-PythonJson {
    param([string]$Url)

    # Windows PowerShell 5.1 can decode application/json incorrectly when the
    # response Content-Type does not include charset=UTF-8. That produces
    # mojibake such as "Rá»i..." even though Python and PostgreSQL both contain
    # correct Vietnamese UTF-8.
    #
    # Read raw response bytes and decode them explicitly as UTF-8 before
    # ConvertFrom-Json so comparisons are byte/character-correct.
    Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue

    $handler = New-Object System.Net.Http.HttpClientHandler
    $client = New-Object System.Net.Http.HttpClient($handler)

    try {
        if (-not [string]::IsNullOrWhiteSpace($InternalApiKey)) {
            [void]$client.DefaultRequestHeaders.Add(
                "X-Internal-Api-Key",
                $InternalApiKey
            )
        }

        $response = $client.GetAsync($Url).GetAwaiter().GetResult()

        if (-not $response.IsSuccessStatusCode) {
            $status = [int]$response.StatusCode
            $reason = $response.ReasonPhrase
            throw "HTTP $status $reason"
        }

        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $json = [System.Text.Encoding]::UTF8.GetString($bytes)

        return ConvertFrom-Json -InputObject $json
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
    }
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

    if ($null -eq $PythonOuter -or $null -eq $PythonOuter.result) {
        Fail-Test "Python result response/result is null."
    }

    if ($PythonOuter.processingId -ne $ProcessingId) {
        Fail-Test "Python processingId mismatch. expected=$ProcessingId actual=$($PythonOuter.processingId)"
    }

    $result = $PythonOuter.result

    if ([long]$result.meetingId -ne $MeetingId) {
        Fail-Test "meetingId mismatch. expected=$MeetingId actual=$($result.meetingId)"
    }

    if ($result.status -ne $JavaJob.sttStatus) {
        Fail-Test "STT status mismatch. Java=$($JavaJob.sttStatus) Python=$($result.status)"
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
        if ([int]$check[1] -ne [int]$check[2]) {
            Fail-Test "$($check[0]) mismatch. Java=$($check[1]) Python=$($check[2])"
        }
    }

    if (@($result.transcripts).Count -ne [int]$result.succeededChunks) {
        Fail-Test "Python transcripts.size does not equal succeededChunks."
    }

    if (@($result.failures).Count -ne [int]$result.failedChunks) {
        Fail-Test "Python failures.size does not equal failedChunks."
    }

    return $result
}

function Get-DbTranscriptJson {
    param([long]$JobId)

    $sql = @"
SELECT COALESCE(
    json_agg(
        json_build_object(
            'sessionId', session_id,
            'attendeeId', attendee_id,
            'speakerName', speaker_name,
            'segmentId', segment_id,
            'segmentIndex', segment_index,
            'chunkIndex', chunk_index,
            'chunkStartMs', chunk_start_ms,
            'chunkDurationMs', chunk_duration_ms,
            'segmentStartedAt', segment_started_at,
            'absoluteStartTime', absolute_start_time,
            'filePath', file_path,
            'text', text
        )
        ORDER BY result_order
    )::text,
    '[]'
)
FROM meeting_transcript_chunks
WHERE minutes_job_id = $JobId;
"@

    return Invoke-PsqlScalar -Sql $sql
}

function Get-DbFailureJson {
    param([long]$JobId)

    $sql = @"
SELECT COALESCE(
    json_agg(
        json_build_object(
            'sessionId', session_id,
            'segmentId', segment_id,
            'chunkIndex', chunk_index,
            'filePath', file_path,
            'attempts', attempts,
            'error', error
        )
        ORDER BY failure_order
    )::text,
    '[]'
)
FROM meeting_transcript_failures
WHERE minutes_job_id = $JobId;
"@

    return Invoke-PsqlScalar -Sql $sql
}

function Convert-JsonArrayItems {
    param([string]$Json)

    if ([string]::IsNullOrWhiteSpace($Json)) {
        return
    }

    $parsed = ConvertFrom-Json -InputObject $Json

    if ($null -eq $parsed) {
        return
    }

    # Windows PowerShell 5.1 can preserve a top-level JSON array as a single
    # pipeline object in some call shapes. Explicit foreach guarantees one
    # PowerShell pipeline item per JSON element.
    foreach ($item in @($parsed)) {
        Write-Output $item
    }
}

function Assert-SameNullableString {
    param(
        [string]$Name,
        $Expected,
        $Actual
    )

    $e = if ($null -eq $Expected) { $null } else { [string]$Expected }
    $a = if ($null -eq $Actual) { $null } else { [string]$Actual }

    if ($e -ne $a) {
        Fail-Test "$Name mismatch. expected=[$e] actual=[$a]"
    }
}

function Assert-TranscriptRowsMatchPython {
    param(
        [long]$JobId,
        $PythonResult
    )

    $dbJson = Get-DbTranscriptJson -JobId $JobId
    $dbRows = @(Convert-JsonArrayItems -Json $dbJson)
    $pyRows = @($PythonResult.transcripts)

    Write-Host "Parsed persisted transcript rows =" $dbRows.Count

    if ($dbRows.Count -ne $pyRows.Count) {
        Fail-Test "Persisted transcript row count mismatch after JSON parse. ParsedDB=$($dbRows.Count) Python=$($pyRows.Count)"
    }

    for ($i = 0; $i -lt $pyRows.Count; $i++) {
        $db = $dbRows[$i]
        $py = $pyRows[$i]

        Assert-SameNullableString "transcript[$i].sessionId" $py.sessionId $db.sessionId
        Assert-SameNullableString "transcript[$i].attendeeId" $py.attendeeId $db.attendeeId
        Assert-SameNullableString "transcript[$i].speakerName" $py.speakerName $db.speakerName
        Assert-SameNullableString "transcript[$i].segmentId" $py.segmentId $db.segmentId
        Assert-SameNullableString "transcript[$i].segmentStartedAt" $py.segmentStartedAt $db.segmentStartedAt
        Assert-SameNullableString "transcript[$i].absoluteStartTime" $py.absoluteStartTime $db.absoluteStartTime
        Assert-SameNullableString "transcript[$i].filePath" $py.filePath $db.filePath
        Assert-SameNullableString "transcript[$i].text" $py.text $db.text

        if ([int]$py.segmentIndex -ne [int]$db.segmentIndex) {
            Fail-Test "transcript[$i].segmentIndex mismatch."
        }
        if ([int]$py.chunkIndex -ne [int]$db.chunkIndex) {
            Fail-Test "transcript[$i].chunkIndex mismatch."
        }
        if ([long]$py.chunkStartMs -ne [long]$db.chunkStartMs) {
            Fail-Test "transcript[$i].chunkStartMs mismatch."
        }
        if ([long]$py.chunkDurationMs -ne [long]$db.chunkDurationMs) {
            Fail-Test "transcript[$i].chunkDurationMs mismatch."
        }
    }

    Write-Host "PASS: persisted transcript rows exactly match Python result fields/order."
}

function Assert-FailureRowsMatchPython {
    param(
        [long]$JobId,
        $PythonResult
    )

    $dbJson = Get-DbFailureJson -JobId $JobId
    $dbRows = @(Convert-JsonArrayItems -Json $dbJson)
    $pyRows = @($PythonResult.failures)

    Write-Host "Parsed persisted failure rows    =" $dbRows.Count

    if ($dbRows.Count -ne $pyRows.Count) {
        Fail-Test "Persisted failure row count mismatch after JSON parse. ParsedDB=$($dbRows.Count) Python=$($pyRows.Count)"
    }

    for ($i = 0; $i -lt $pyRows.Count; $i++) {
        $db = $dbRows[$i]
        $py = $pyRows[$i]

        Assert-SameNullableString "failure[$i].sessionId" $py.sessionId $db.sessionId
        Assert-SameNullableString "failure[$i].segmentId" $py.segmentId $db.segmentId
        Assert-SameNullableString "failure[$i].filePath" $py.filePath $db.filePath
        Assert-SameNullableString "failure[$i].error" $py.error $db.error

        if ($null -eq $py.chunkIndex) {
            if ($null -ne $db.chunkIndex) {
                Fail-Test "failure[$i].chunkIndex expected null."
            }
        }
        elseif ([int]$py.chunkIndex -ne [int]$db.chunkIndex) {
            Fail-Test "failure[$i].chunkIndex mismatch."
        }

        if ([int]$py.attempts -ne [int]$db.attempts) {
            Fail-Test "failure[$i].attempts mismatch."
        }
    }

    Write-Host "PASS: persisted failure rows exactly match Python result fields/order."
}

function Get-MinutesVersionCount {
    return [int](Invoke-PsqlScalar -Sql @"
SELECT COUNT(*)
FROM meeting_minutes
WHERE meeting_id = $MeetingId;
"@)
}

function Get-SemanticVoiceCount {
    param([long]$JobId)

    return [int](Invoke-PsqlScalar -Sql @"
SELECT COUNT(*)
FROM meeting_transcript_chunks
WHERE minutes_job_id = $JobId
  AND text IS NOT NULL
  AND BTRIM(text) <> '';
"@)
}

function Get-SemanticChatCount {
    return [int](Invoke-PsqlScalar -Sql @"
SELECT COUNT(*)
FROM meeting_chat_messages
WHERE meeting_id = $MeetingId
  AND content IS NOT NULL
  AND BTRIM(content) <> '';
"@)
}

function Run-JavaTimelineLiveProbe {
    param(
        [long]$JobId,
        [int]$ExpectedVoiceCount,
        [int]$ExpectedChatCount
    )

    Write-Host ""
    Write-Host "=== JAVA LIVE VOICE + CHAT TIMELINE PROBE ==="
    Write-Host "This probe uses the REAL Java MeetingMinutesTimelineService + REAL PostgreSQL rows."
    Write-Host "It creates a TEMPORARY @DataJpaTest source and deletes it after Maven finishes."

    $testDir = Join-Path (Get-Location) "src\test\java\vn\cusc\meetingservice\service"
    $testFile = Join-Path $testDir "MeetingTimelineLiveProbeTest.java"
    $compiledDir = Join-Path (Get-Location) "target\test-classes\vn\cusc\meetingservice\service"

    if (Test-Path $testFile) {
        Fail-Test "Temporary probe already exists: $testFile"
    }

    New-Item -ItemType Directory -Path $testDir -Force | Out-Null

    $javaSource = @'
package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import vn.cusc.meetingservice.config.MeetingTimelineProperties;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineItem;
import vn.cusc.meetingservice.dto.timeline.MeetingTimelineResult;
import vn.cusc.meetingservice.model.enums.MeetingTimelineSourceType;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MeetingMinutesTimelineService.class)
@EnableConfigurationProperties(MeetingTimelineProperties.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect"
})
class MeetingTimelineLiveProbeTest {

    @Autowired
    private MeetingMinutesTimelineService timelineService;

    @Test
    void buildsRealVoiceChatTimelineFromPostgres() {
        long jobId = Long.parseLong(required("TIMELINE_PROBE_JOB_ID"));
        long meetingId = Long.parseLong(required("TIMELINE_PROBE_MEETING_ID"));
        int expectedVoice = Integer.parseInt(required("TIMELINE_PROBE_EXPECTED_VOICE"));
        int expectedChat = Integer.parseInt(required("TIMELINE_PROBE_EXPECTED_CHAT"));

        MeetingTimelineResult result = timelineService.buildTimeline(jobId);

        assertNotNull(result);
        assertEquals(jobId, result.jobId());
        assertEquals(meetingId, result.meetingId());
        assertEquals(expectedVoice, result.voiceItemCount());
        assertEquals(expectedChat, result.chatItemCount());
        assertEquals(expectedVoice + expectedChat, result.totalItemCount());
        assertEquals(result.totalItemCount(), result.items().size());

        if (expectedVoice > 0) {
            assertTrue(
                result.items().stream()
                    .anyMatch(item -> item.sourceType() == MeetingTimelineSourceType.VOICE)
            );
        }

        if (expectedChat > 0) {
            assertTrue(
                result.items().stream()
                    .anyMatch(item -> item.sourceType() == MeetingTimelineSourceType.CHAT)
            );
        }

        assertSortedDeterministically(result.items());

        System.out.println();
        System.out.println("======================================================");
        System.out.println(" REAL MEETING TIMELINE");
        System.out.println("======================================================");
        System.out.printf(
            "jobId=%d meetingId=%d voice=%d chat=%d total=%d%n",
            result.jobId(),
            result.meetingId(),
            result.voiceItemCount(),
            result.chatItemCount(),
            result.totalItemCount()
        );

        int index = 0;
        for (MeetingTimelineItem item : result.items()) {
            String content = item.content() == null
                ? ""
                : item.content()
                    .replace('\r', ' ')
                    .replace('\n', ' ');

            System.out.printf(
                "TIMELINE[%d] %s | %s | attendee=%s | speaker=%s | sourceId=%s | order=%s | %s%n",
                index++,
                item.occurredAt(),
                item.sourceType(),
                item.attendeeId(),
                item.speakerName(),
                item.sourceId(),
                item.sourceOrder(),
                content
            );
        }

        System.out.println("======================================================");
    }

    private static void assertSortedDeterministically(
        List<MeetingTimelineItem> items
    ) {
        for (int i = 1; i < items.size(); i++) {
            MeetingTimelineItem previous = items.get(i - 1);
            MeetingTimelineItem current = items.get(i);

            assertNotNull(previous.occurredAt());
            assertNotNull(current.occurredAt());
            assertFalse(
                compare(previous, current) > 0,
                "Timeline is not deterministically sorted at index " + i
            );
        }
    }

    private static int compare(
        MeetingTimelineItem left,
        MeetingTimelineItem right
    ) {
        int time = left.occurredAt().compareTo(right.occurredAt());
        if (time != 0) {
            return time;
        }

        int source = Integer.compare(
            sourcePriority(left.sourceType()),
            sourcePriority(right.sourceType())
        );
        if (source != 0) {
            return source;
        }

        int order = compareNullableLong(
            left.sourceOrder(),
            right.sourceOrder()
        );
        if (order != 0) {
            return order;
        }

        return compareNullableLong(
            left.sourceId(),
            right.sourceId()
        );
    }

    private static int sourcePriority(
        MeetingTimelineSourceType sourceType
    ) {
        return sourceType == MeetingTimelineSourceType.VOICE ? 0 : 1;
    }

    private static int compareNullableLong(
        Long left,
        Long right
    ) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return Long.compare(left, right);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Missing required environment variable: " + name
            );
        }
        return value;
    }
}
'@

    [System.IO.File]::WriteAllText(
        $testFile,
        $javaSource,
        [System.Text.UTF8Encoding]::new($false)
    )

    $env:TIMELINE_PROBE_JOB_ID = [string]$JobId
    $env:TIMELINE_PROBE_MEETING_ID = [string]$MeetingId
    $env:TIMELINE_PROBE_EXPECTED_VOICE = [string]$ExpectedVoiceCount
    $env:TIMELINE_PROBE_EXPECTED_CHAT = [string]$ExpectedChatCount

    # Force the temporary DataJpaTest to the same local PostgreSQL DB.
    # Environment variables override application-test.yml.
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${DbHost}:${DbPort}/${DbName}"
    $env:SPRING_DATASOURCE_USERNAME = $DbUser
    $env:SPRING_DATASOURCE_PASSWORD = $DbPassword
    $env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = "org.postgresql.Driver"
    $env:SPRING_JPA_DATABASE_PLATFORM = "org.hibernate.dialect.PostgreSQLDialect"
    $env:SPRING_FLYWAY_ENABLED = "false"
    $env:SPRING_JPA_HIBERNATE_DDL_AUTO = "none"
    $env:MEETING_TIMELINE_ZONE_ID = $TimelineZoneId

    Write-Host "Live probe datasource driver : org.postgresql.Driver"
    Write-Host "Live probe datasource URL    : jdbc:postgresql://${DbHost}:${DbPort}/${DbName}"
    Write-Host "Live probe Spring profile    : default (NOT test/H2)"

    $exitCode = 1

    try {
        & mvn '-Dtest=MeetingTimelineLiveProbeTest' test
        $exitCode = $LASTEXITCODE
    }
    finally {
        if (Test-Path $testFile) {
            Remove-Item $testFile -Force -ErrorAction SilentlyContinue
        }

        if (Test-Path $compiledDir) {
            Get-ChildItem `
                -Path $compiledDir `
                -Filter "MeetingTimelineLiveProbeTest*.class" `
                -ErrorAction SilentlyContinue |
                Remove-Item -Force -ErrorAction SilentlyContinue
        }

        @(
            "TIMELINE_PROBE_JOB_ID",
            "TIMELINE_PROBE_MEETING_ID",
            "TIMELINE_PROBE_EXPECTED_VOICE",
            "TIMELINE_PROBE_EXPECTED_CHAT",
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_DATASOURCE_DRIVER_CLASS_NAME",
            "SPRING_JPA_DATABASE_PLATFORM",
            "SPRING_FLYWAY_ENABLED",
            "SPRING_JPA_HIBERNATE_DDL_AUTO",
            "MEETING_TIMELINE_ZONE_ID"
        ) | ForEach-Object {
            Remove-Item "Env:$_" -ErrorAction SilentlyContinue
        }
    }

    if ($exitCode -ne 0) {
        Fail-Test @"
Java live Timeline probe failed.

The temporary test used:
  MeetingMinutesTimelineService
  + real MeetingMinutesJobRepository
  + real MeetingTranscriptChunkRepository
  + real MeetingChatMessageRepository
  + local PostgreSQL

Temporary Java source was cleaned up.
"@
    }

    Write-Host "PASS: REAL Java MeetingMinutesTimelineService built the timeline from PostgreSQL."
    Write-Host "PASS: Timeline semantic counts and deterministic ordering validated."
    Write-Host "PASS: temporary Java probe cleaned up."
}

function Run-JavaEditorDraftLiveProbe {
    param(
        [long]$JobId,
        [int]$ExpectedVoiceCount,
        [int]$ExpectedChatCount
    )

    Write-Host ""
    Write-Host "=== JAVA LIVE EDITOR DRAFT PROBE ==="
    Write-Host "This probe uses REAL Java MeetingMinutesEditorDraftService"
    Write-Host "+ REAL MeetingMinutesTimelineService + REAL PostgreSQL."
    Write-Host "Authorization is mocked only to bypass login in the temporary test."
    Write-Host "It creates a TEMPORARY @DataJpaTest source and deletes it after Maven finishes."

    $testDir = Join-Path (Get-Location) "src\test\java\vn\cusc\meetingservice\service"
    $testFile = Join-Path $testDir "MeetingEditorDraftLiveProbeTest.java"
    $compiledDir = Join-Path (Get-Location) "target\test-classes\vn\cusc\meetingservice\service"

    if (Test-Path $testFile) {
        Fail-Test "Temporary editor draft probe already exists: $testFile"
    }

    New-Item -ItemType Directory -Path $testDir -Force | Out-Null

    $javaSource = @'
package vn.cusc.meetingservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import vn.cusc.meetingservice.config.MeetingTimelineProperties;
import vn.cusc.meetingservice.dto.response.MinutesEditorDraftResponse;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    MeetingMinutesTimelineService.class,
    MeetingMinutesEditorDraftService.class
})
@EnableConfigurationProperties(MeetingTimelineProperties.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect"
})
class MeetingEditorDraftLiveProbeTest {

    @Autowired
    private MeetingMinutesEditorDraftService editorDraftService;

    @MockBean
    private MeetingMinutesAuthorizationService authorizationService;

    @Test
    void buildsRealEditorDraftFromTimelineAndPostgres() {
        long jobId = Long.parseLong(required("EDITOR_DRAFT_PROBE_JOB_ID"));
        long meetingId = Long.parseLong(required("EDITOR_DRAFT_PROBE_MEETING_ID"));
        int expectedVoice = Integer.parseInt(required("EDITOR_DRAFT_PROBE_EXPECTED_VOICE"));
        int expectedChat = Integer.parseInt(required("EDITOR_DRAFT_PROBE_EXPECTED_CHAT"));

        MinutesEditorDraftResponse result = editorDraftService.getDraft(meetingId);

        assertNotNull(result);
        assertEquals(jobId, result.jobId());
        assertEquals(meetingId, result.meetingId());
        assertEquals(expectedVoice, result.voiceItemCount());
        assertEquals(expectedChat, result.chatItemCount());
        assertEquals(expectedVoice + expectedChat, result.totalItemCount());
        assertEquals("", result.conclusion());

        if (result.totalItemCount() > 0) {
            assertNotNull(result.mainContent());
            assertFalse(result.mainContent().isBlank());
        }

        System.out.println();
        System.out.println("======================================================");
        System.out.println(" REAL MINUTES EDITOR DRAFT");
        System.out.println("======================================================");
        System.out.printf(
            "jobId=%d meetingId=%d voice=%d chat=%d total=%d%n",
            result.jobId(),
            result.meetingId(),
            result.voiceItemCount(),
            result.chatItemCount(),
            result.totalItemCount()
        );
        System.out.println("conclusion=[" + result.conclusion() + "]");
        System.out.println("---------------- MAIN CONTENT ----------------");
        System.out.println(result.mainContent());
        System.out.println("------------------------------------------------");
        System.out.println("======================================================");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Missing required environment variable: " + name
            );
        }
        return value;
    }
}
'@

    [System.IO.File]::WriteAllText(
        $testFile,
        $javaSource,
        [System.Text.UTF8Encoding]::new($false)
    )

    $env:EDITOR_DRAFT_PROBE_JOB_ID = [string]$JobId
    $env:EDITOR_DRAFT_PROBE_MEETING_ID = [string]$MeetingId
    $env:EDITOR_DRAFT_PROBE_EXPECTED_VOICE = [string]$ExpectedVoiceCount
    $env:EDITOR_DRAFT_PROBE_EXPECTED_CHAT = [string]$ExpectedChatCount

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${DbHost}:${DbPort}/${DbName}"
    $env:SPRING_DATASOURCE_USERNAME = $DbUser
    $env:SPRING_DATASOURCE_PASSWORD = $DbPassword
    $env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = "org.postgresql.Driver"
    $env:SPRING_JPA_DATABASE_PLATFORM = "org.hibernate.dialect.PostgreSQLDialect"
    $env:SPRING_FLYWAY_ENABLED = "false"
    $env:SPRING_JPA_HIBERNATE_DDL_AUTO = "none"
    $env:MEETING_TIMELINE_ZONE_ID = $TimelineZoneId

    Write-Host "Editor probe datasource driver : org.postgresql.Driver"
    Write-Host "Editor probe datasource URL    : jdbc:postgresql://${DbHost}:${DbPort}/${DbName}"
    Write-Host "Editor probe timeline zone     :" $TimelineZoneId

    $exitCode = 1

    try {
        & mvn '-Dtest=MeetingEditorDraftLiveProbeTest' test
        $exitCode = $LASTEXITCODE
    }
    finally {
        if (Test-Path $testFile) {
            Remove-Item $testFile -Force -ErrorAction SilentlyContinue
        }

        if (Test-Path $compiledDir) {
            Get-ChildItem `
                -Path $compiledDir `
                -Filter "MeetingEditorDraftLiveProbeTest*.class" `
                -ErrorAction SilentlyContinue |
                Remove-Item -Force -ErrorAction SilentlyContinue
        }

        @(
            "EDITOR_DRAFT_PROBE_JOB_ID",
            "EDITOR_DRAFT_PROBE_MEETING_ID",
            "EDITOR_DRAFT_PROBE_EXPECTED_VOICE",
            "EDITOR_DRAFT_PROBE_EXPECTED_CHAT",
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_DATASOURCE_DRIVER_CLASS_NAME",
            "SPRING_JPA_DATABASE_PLATFORM",
            "SPRING_FLYWAY_ENABLED",
            "SPRING_JPA_HIBERNATE_DDL_AUTO",
            "MEETING_TIMELINE_ZONE_ID"
        ) | ForEach-Object {
            Remove-Item "Env:$_" -ErrorAction SilentlyContinue
        }
    }

    if ($exitCode -ne 0) {
        Fail-Test @"
Java live Editor Draft probe failed.

Expected production state:
  PROCESSING / DRAFT_READY / 80
  transcriptStatus = READY
  no existing meeting_minutes version

The temporary test used:
  MeetingMinutesEditorDraftService
  + MeetingMinutesTimelineService
  + real repositories
  + local PostgreSQL

Authorization was mocked only to bypass login in the temporary runtime probe.
Temporary Java source was cleaned up.
"@
    }

    Write-Host "PASS: REAL MeetingMinutesEditorDraftService built editor draft from PostgreSQL."
    Write-Host "PASS: Editor Draft counts match VOICE + CHAT Timeline."
    Write-Host "PASS: conclusion is blank as designed."
    Write-Host "PASS: temporary Editor Draft probe cleaned up."
}

function Run-TargetedJavaTests {
    Write-Host ""
    Write-Host "=== TARGETED JAVA TESTS ==="

    & mvn `
        '-Dtest=MeetingMinutesEditorDraftServiceTest,MeetingMinutesProcessingServiceTest,MeetingMinutesVersionServiceTest,MeetingMinutesTimelineServiceTest,MeetingMinutesTranscriptStateServiceTest,MeetingMinutesTranscriptPersistenceServiceTest,MeetingMinutesTranscriptDispatcherTest,MeetingMinutesSttResultFetchServiceTest,MeetingMinutesSttJobStateServiceTest,SttClientImplTest' `
        test

    if ($LASTEXITCODE -ne 0) {
        Fail-Test "Targeted Java regression tests failed."
    }

    Write-Host "PASS: targeted Timeline + Transcript + STT regression tests."
}

Write-Host "======================================================"
Write-Host " FULL E2E: STT -> VOICE + CHAT -> EDITOR DRAFT"
Write-Host "======================================================"
Write-Host "MeetingId    :" $MeetingId
Write-Host "Java         :" $JavaBaseUrl
Write-Host "Python       :" $PythonBaseUrl
Write-Host "TimelineZone :" $TimelineZoneId
Write-Host "JSON decode   : explicit UTF-8 bytes (PowerShell 5.1 safe)"
Write-Host ""
Write-Host "This script verifies:"
Write-Host "  1. Java -> Python STT POST"
Write-Host "  2. Python progress -> Java"
Write-Host "  3. Python completion -> Java"
Write-Host "  4. Java production transcript dispatcher GETs Python /result"
Write-Host "  5. Java persists transcript rows atomically"
Write-Host "  6. transcriptStatus READY / progress 80"
Write-Host "  7. persisted rows match Python result"
Write-Host "  8. REAL Java MeetingMinutesTimelineService reads VOICE + CHAT"
Write-Host "  9. Timeline is normalized/sorted and printed"
Write-Host " 10. Production job reaches DRAFT_READY / 80"
Write-Host " 11. REAL MeetingMinutesEditorDraftService builds editor draft"
Write-Host ""

$script:Psql = Find-Psql
Write-Host "psql         :" $script:Psql

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
Write-Host "=== 2. VERIFY TRANSCRIPT SCHEMA ==="

Assert-TranscriptSchemaExists
Write-Host "PASS: V11 transcript state columns/tables exist."

Write-Host ""
Write-Host "=== 3. LOAD / CLEAN RESET JOB ==="

$job = Get-LatestMinutesJob

if ($null -eq $job) {
    Fail-Test "No meeting_minutes_jobs row found for meeting=$MeetingId."
}

Show-Job -Prefix "Current" -Job $job

if ($ResetExistingJob) {
    Reset-LatestJob -JobId $job.id
}
else {
    if ($job.status -ne "QUEUED") {
        Fail-Test @"
Latest job is not QUEUED.

For a clean LOCAL DEV rerun use:

  -ResetExistingJob

This now safely deletes old transcript rows before resetting the job.
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
        Fail-Test "Java job failed before STT dispatch. error=$($job.jobError)"
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
Write-Host "=== 5. STT PROGRESS -> COMPLETION -> TRANSCRIPT READY ==="

$lastJavaState = ""
$lastPythonState = ""
$pythonTerminal = $false
$pythonFinalStatus = ""

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
    $job = Get-LatestMinutesJob

    if (
        -not [string]::IsNullOrWhiteSpace($job.processingId) -and
        $job.processingId -ne $processingId
    ) {
        Fail-Test "stt_processing_id changed unexpectedly."
    }

    $javaState = "$($job.status)|$($job.stage)|$($job.progress)|$($job.sttStatus)|$($job.transcriptStatus)|$($job.transcriptAttempts)"

    if ($javaState -ne $lastJavaState) {
        Write-Host (
            "JAVA   STATUS={0} STAGE={1} PROGRESS={2}% STT={3} TRANSCRIPT={4} ATTEMPTS={5}" -f `
            $job.status,
            $job.stage,
            $job.progress,
            $job.sttStatus,
            $job.transcriptStatus,
            $job.transcriptAttempts
        )

        $lastJavaState = $javaState
    }

    if ($job.status -eq "FAILED") {
        Fail-Test @"
Java Minutes job FAILED.

sttStatus=$($job.sttStatus)
sttError=$($job.sttError)

transcriptStatus=$($job.transcriptStatus)
transcriptAttempts=$($job.transcriptAttempts)
transcriptLastError=$($job.transcriptLastError)

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
        $job.stage -eq "DRAFT_READY" -and
        $job.progress -eq 80 -and
        $job.transcriptStatus -eq "READY" -and
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

if ($job.status -ne "PROCESSING") {
    Fail-Test "Expected Java status PROCESSING, actual=$($job.status)"
}

if ($job.stage -ne "DRAFT_READY") {
    Fail-Test "Expected DRAFT_READY, actual=$($job.stage)"
}

if ($job.progress -ne 80) {
    Fail-Test "Expected progress 80 after transcript persistence, actual=$($job.progress)"
}

if ($job.transcriptStatus -ne "READY") {
    Fail-Test "Expected transcriptStatus READY, actual=$($job.transcriptStatus)"
}

if ([string]::IsNullOrWhiteSpace($job.transcriptPersistedAt)) {
    Fail-Test "transcript_persisted_at is empty."
}

if ($job.transcriptAttempts -lt 1) {
    Fail-Test "Expected transcriptAttempts >= 1, actual=$($job.transcriptAttempts)"
}

if ($job.sttStatus -ne $pythonFinalStatus) {
    Fail-Test "Java/Python terminal status mismatch. Java=$($job.sttStatus) Python=$pythonFinalStatus"
}

Write-Host "PASS: production transcript dispatcher reached READY / progress 80."
Write-Host "PASS: transcriptPersistedAt=$($job.transcriptPersistedAt)"

Write-Host ""
Write-Host "=== 6. VERIFY PYTHON RESULT ==="

$pythonOuter = Get-PythonResult -ProcessingId $processingId

$result = Assert-PythonResultMatchesJava `
    -PythonOuter $pythonOuter `
    -JavaJob $job `
    -ProcessingId $processingId

Write-Host "PASS: Python result is READY and matches Java STT completion metadata."

Write-Host ""
Write-Host "=== 7. VERIFY PERSISTED TRANSCRIPT DB ==="

$dbChunkCount = [int](Invoke-PsqlScalar -Sql "SELECT COUNT(*) FROM meeting_transcript_chunks WHERE minutes_job_id = $($job.id);")
$dbFailureCount = [int](Invoke-PsqlScalar -Sql "SELECT COUNT(*) FROM meeting_transcript_failures WHERE minutes_job_id = $($job.id);")

Write-Host "DB transcript chunks  =" $dbChunkCount
Write-Host "DB transcript failures=" $dbFailureCount

if ($dbChunkCount -ne [int]$result.succeededChunks) {
    Fail-Test "DB transcript chunks mismatch. DB=$dbChunkCount succeededChunks=$($result.succeededChunks)"
}

if ($dbFailureCount -ne [int]$result.failedChunks) {
    Fail-Test "DB transcript failures mismatch. DB=$dbFailureCount failedChunks=$($result.failedChunks)"
}

Assert-TranscriptRowsMatchPython `
    -JobId $job.id `
    -PythonResult $result

Assert-FailureRowsMatchPython `
    -JobId $job.id `
    -PythonResult $result

Write-Host ""
Write-Host "=== 8. PREPARE REAL VOICE + CHAT COUNTS ==="

$semanticVoiceCount = Get-SemanticVoiceCount -JobId $job.id
$semanticChatCount = Get-SemanticChatCount

Write-Host "Semantic VOICE items expected =" $semanticVoiceCount
Write-Host "Semantic CHAT items expected  =" $semanticChatCount
Write-Host "Expected timeline total       =" ($semanticVoiceCount + $semanticChatCount)

if ($semanticVoiceCount -le 0) {
    Fail-Test "No nonblank VOICE transcript item exists for this job."
}

if (-not $AllowNoChat -and $semanticChatCount -le 0) {
    Fail-Test @"
No nonblank CHAT row exists for meeting=$MeetingId.

This E2E is intended to prove VOICE + CHAT, not only VOICE.

Use a meeting that already has persisted chat messages, or use:
  -AllowNoChat

only if you intentionally want a voice-only Timeline runtime check.
"@
}

if (-not $SkipJavaTimelineLiveProbe) {
    Run-JavaTimelineLiveProbe `
        -JobId $job.id `
        -ExpectedVoiceCount $semanticVoiceCount `
        -ExpectedChatCount $semanticChatCount
}
else {
    Write-Host ""
    Write-Host "=== 9. JAVA LIVE TIMELINE PROBE ==="
    Write-Host "SKIPPED by -SkipJavaTimelineLiveProbe"
}

Write-Host ""
Write-Host "=== 10. VERIFY EDITOR DRAFT PRECONDITION ==="

$minutesVersionCount = Get-MinutesVersionCount
Write-Host "Existing meeting_minutes versions =" $minutesVersionCount

if ($minutesVersionCount -gt 0) {
    Fail-Test @"
meeting=$MeetingId already has $minutesVersionCount saved Minutes version(s).

This is expected to block GET /minutes/editor-draft by design,
because Editor Draft is only for the no-version initial state.

Use a meeting with no saved Minutes version for this runtime test.
The script intentionally DOES NOT delete saved Minutes versions.
"@
}

Run-JavaEditorDraftLiveProbe `
    -JobId $job.id `
    -ExpectedVoiceCount $semanticVoiceCount `
    -ExpectedChatCount $semanticChatCount

if (-not $SkipTargetedTests) {
    Run-TargetedJavaTests
}
else {
    Write-Host ""
    Write-Host "=== 10. TARGETED JAVA TESTS ==="
    Write-Host "SKIPPED by -SkipTargetedTests"
}

Write-Host ""
Write-Host "======================================================"
Write-Host " FULL STT -> VOICE + CHAT -> EDITOR DRAFT E2E PASS"
Write-Host "======================================================"
Write-Host "Confirmed:"
Write-Host "  Java -> Python POST STT                   PASS"
Write-Host "  Python progress -> Java                   PASS"
Write-Host "  Python completion -> Java                 PASS"
Write-Host "  Production Java GET Python result         PASS"
Write-Host "  Java transcript persistence               PASS"
Write-Host "  transcriptStatus READY / progress 80      PASS"
Write-Host "  Persisted transcript == Python result     PASS"
Write-Host "  Production job DRAFT_READY / 80           PASS"

if (-not $SkipJavaTimelineLiveProbe) {
    Write-Host "  REAL Java VOICE + CHAT Timeline            PASS"
    Write-Host "  Timeline deterministic sort                PASS"
}

Write-Host "  REAL Java Minutes Editor Draft             PASS"
Write-Host "  Editor Draft conclusion blank              PASS"

Write-Host ""
Write-Host "Final job:"
Write-Host "  jobId                  =" $job.id
Write-Host "  status                 =" $job.status
Write-Host "  stage                  =" $job.stage
Write-Host "  progress               =" $job.progress
Write-Host "  sttStatus              =" $job.sttStatus
Write-Host "  transcriptStatus       =" $job.transcriptStatus
Write-Host "  transcriptAttempts     =" $job.transcriptAttempts
Write-Host "  transcriptPersistedAt  =" $job.transcriptPersistedAt
Write-Host ""
Write-Host "Source data:"
Write-Host "  raw transcript chunks  =" $dbChunkCount
Write-Host "  transcript failures    =" $dbFailureCount
Write-Host "  semantic VOICE items   =" $semanticVoiceCount
Write-Host "  semantic CHAT items    =" $semanticChatCount
Write-Host ""
Write-Host "Current production state remains intentionally:"
Write-Host "  PROCESSING / DRAFT_READY / 80"
Write-Host ""
Write-Host "NEXT:"
Write-Host "  FE poll /minutes/processing"
Write-Host "  -> DRAFT_READY / 80 opens Minutes Editor"
Write-Host "  -> GET /minutes/editor-draft"
Write-Host "  -> Optional AI Summary later"
Write-Host "  -> POST /minutes/versions on Save"
