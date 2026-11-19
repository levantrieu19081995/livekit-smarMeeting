ALTER TABLE meeting_minutes_jobs
    ADD COLUMN IF NOT EXISTS stt_processing_id VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS
    uk_meeting_minutes_jobs_stt_processing_id
ON meeting_minutes_jobs(stt_processing_id)
WHERE stt_processing_id IS NOT NULL;
