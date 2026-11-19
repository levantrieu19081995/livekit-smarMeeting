-- Remote cleanup survives commit, process restart and hard deletion of a meeting.
CREATE TABLE meeting_media_cleanup_jobs (
    id BIGSERIAL PRIMARY KEY,
    operation_key VARCHAR(64) NOT NULL UNIQUE,
    meeting_id BIGINT,
    creator_account_id BIGINT,
    operation VARCHAR(30) NOT NULL,
    room_name VARCHAR(255) NOT NULL,
    target_identity VARCHAR(255),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL,
    last_error_code VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_media_cleanup_due ON meeting_media_cleanup_jobs(next_attempt_at, id);
CREATE INDEX idx_media_cleanup_meeting ON meeting_media_cleanup_jobs(meeting_id);
ALTER TABLE meeting_participant_connections ADD COLUMN last_webhook_at BIGINT;
ALTER TABLE meeting_minutes ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_chat_meeting_cursor ON meeting_chat_messages(meeting_id, id);
