ALTER TABLE meeting_minutes_jobs
    ADD COLUMN transcript_status VARCHAR(20),
    ADD COLUMN transcript_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN transcript_last_error TEXT,
    ADD COLUMN transcript_persisted_at TIMESTAMP;

UPDATE meeting_minutes_jobs
SET transcript_status = 'PENDING'
WHERE status = 'PROCESSING'
  AND stage = 'BUILDING_TRANSCRIPT'
  AND stt_status IN ('COMPLETED', 'PARTIAL')
  AND transcript_status IS NULL;

UPDATE meeting_minutes_jobs
SET transcript_status = 'FAILED'
WHERE stt_status = 'FAILED'
  AND transcript_status IS NULL;

CREATE TABLE meeting_transcript_chunks (
    id BIGSERIAL PRIMARY KEY,
    minutes_job_id BIGINT NOT NULL REFERENCES meeting_minutes_jobs(id) ON DELETE CASCADE,
    stt_processing_id VARCHAR(100) NOT NULL,
    result_order INTEGER NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    attendee_id VARCHAR(100),
    speaker_name VARCHAR(255),
    segment_id VARCHAR(100) NOT NULL,
    segment_index INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_start_ms BIGINT NOT NULL,
    chunk_duration_ms BIGINT NOT NULL,
    segment_started_at VARCHAR(64),
    absolute_start_time VARCHAR(64),
    file_path TEXT NOT NULL,
    text TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_transcript_chunk_job_order UNIQUE (minutes_job_id, result_order),
    CONSTRAINT uk_meeting_transcript_chunk_identity UNIQUE (minutes_job_id, session_id, segment_id, chunk_index),
    CONSTRAINT ck_meeting_transcript_chunk_result_order CHECK (result_order >= 0),
    CONSTRAINT ck_meeting_transcript_chunk_segment_index CHECK (segment_index >= 0),
    CONSTRAINT ck_meeting_transcript_chunk_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_meeting_transcript_chunk_start_ms CHECK (chunk_start_ms >= 0),
    CONSTRAINT ck_meeting_transcript_chunk_duration_ms CHECK (chunk_duration_ms >= 0)
);

CREATE INDEX idx_meeting_transcript_chunks_job_order
    ON meeting_transcript_chunks (minutes_job_id, result_order);

CREATE TABLE meeting_transcript_failures (
    id BIGSERIAL PRIMARY KEY,
    minutes_job_id BIGINT NOT NULL REFERENCES meeting_minutes_jobs(id) ON DELETE CASCADE,
    stt_processing_id VARCHAR(100) NOT NULL,
    failure_order INTEGER NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    segment_id VARCHAR(100) NOT NULL,
    chunk_index INTEGER,
    file_path TEXT NOT NULL,
    attempts INTEGER NOT NULL,
    error TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_transcript_failure_job_order UNIQUE (minutes_job_id, failure_order),
    CONSTRAINT ck_meeting_transcript_failure_order CHECK (failure_order >= 0),
    CONSTRAINT ck_meeting_transcript_failure_chunk_index CHECK (chunk_index IS NULL OR chunk_index >= 0),
    CONSTRAINT ck_meeting_transcript_failure_attempts CHECK (attempts >= 0)
);

CREATE UNIQUE INDEX uk_meeting_transcript_failure_identity
    ON meeting_transcript_failures (
        minutes_job_id,
        session_id,
        segment_id,
        COALESCE(chunk_index, -1),
        file_path
    );
