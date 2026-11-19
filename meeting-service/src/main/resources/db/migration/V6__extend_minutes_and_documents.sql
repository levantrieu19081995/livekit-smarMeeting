ALTER TABLE meeting_minutes ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE meeting_minutes ADD COLUMN IF NOT EXISTS source VARCHAR(30);
ALTER TABLE meeting_minutes ADD COLUMN IF NOT EXISTS created_by_account_id BIGINT REFERENCES user_accounts(id);
ALTER TABLE meeting_minutes ADD COLUMN IF NOT EXISTS finalized_at TIMESTAMP;

UPDATE meeting_minutes SET status = 'DRAFT' WHERE status IS NULL;
UPDATE meeting_minutes SET source = 'AI_GENERATED' WHERE source IS NULL;

ALTER TABLE meeting_minutes ALTER COLUMN status SET NOT NULL;
ALTER TABLE meeting_minutes ALTER COLUMN source SET NOT NULL;

WITH ordered_versions AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY meeting_id ORDER BY version, generated_at, id) AS normalized_version
    FROM meeting_minutes
)
UPDATE meeting_minutes target
SET version = ordered_versions.normalized_version
FROM ordered_versions
WHERE target.id = ordered_versions.id
  AND target.version <> ordered_versions.normalized_version;

CREATE UNIQUE INDEX IF NOT EXISTS uk_meeting_minutes_meeting_version
    ON meeting_minutes(meeting_id, version);

CREATE TABLE IF NOT EXISTS meeting_minutes_jobs (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    stage VARCHAR(30) NOT NULL,
    progress_percent INTEGER NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    requested_by_account_id BIGINT NOT NULL REFERENCES user_accounts(id),
    attempt INTEGER NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_minutes_jobs_attempt UNIQUE (meeting_id, attempt)
);

CREATE INDEX IF NOT EXISTS idx_meeting_minutes_jobs_meeting_status
    ON meeting_minutes_jobs(meeting_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_meeting_minutes_jobs_one_active
    ON meeting_minutes_jobs(meeting_id)
    WHERE status IN ('QUEUED', 'PROCESSING');

ALTER TABLE meeting_documents ADD COLUMN IF NOT EXISTS uploaded_by_account_id BIGINT REFERENCES user_accounts(id);
ALTER TABLE meeting_documents ADD COLUMN IF NOT EXISTS source VARCHAR(30);
UPDATE meeting_documents SET source = 'BEFORE_MEETING' WHERE source IS NULL;
ALTER TABLE meeting_documents ALTER COLUMN source SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_meeting_documents_meeting
    ON meeting_documents(meeting_id);
