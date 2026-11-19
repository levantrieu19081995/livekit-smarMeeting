CREATE TABLE IF NOT EXISTS meeting_capture_sessions (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE,
    external_session_id VARCHAR(100),
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP,
    stopping_at TIMESTAMP,
    completed_at TIMESTAMP,
    manifest_path VARCHAR(1000),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_meeting_capture_sessions_status
    ON meeting_capture_sessions(status);
