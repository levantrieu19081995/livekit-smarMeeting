CREATE TABLE meeting_file_cleanup_jobs (
    id BIGSERIAL PRIMARY KEY,
    storage_path VARCHAR(500) NOT NULL UNIQUE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP NOT NULL,
    last_error_code VARCHAR(100),
    lease_token VARCHAR(36),
    lease_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX idx_meeting_file_cleanup_due ON meeting_file_cleanup_jobs(next_attempt_at, id);
ALTER TABLE meeting_documents ADD COLUMN content_sha256 VARCHAR(64) CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX uk_meeting_document_content_retry ON meeting_documents(meeting_id, content_sha256, original_file_name)
    WHERE content_sha256 IS NOT NULL;
