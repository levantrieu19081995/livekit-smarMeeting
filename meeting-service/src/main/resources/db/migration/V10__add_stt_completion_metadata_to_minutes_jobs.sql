ALTER TABLE meeting_minutes_jobs
    ADD COLUMN IF NOT EXISTS stt_status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS stt_total_manifests INTEGER,
    ADD COLUMN IF NOT EXISTS stt_total_segments INTEGER,
    ADD COLUMN IF NOT EXISTS stt_total_chunks INTEGER,
    ADD COLUMN IF NOT EXISTS stt_processed_chunks INTEGER,
    ADD COLUMN IF NOT EXISTS stt_succeeded_chunks INTEGER,
    ADD COLUMN IF NOT EXISTS stt_failed_chunks INTEGER,
    ADD COLUMN IF NOT EXISTS stt_error_message TEXT;
