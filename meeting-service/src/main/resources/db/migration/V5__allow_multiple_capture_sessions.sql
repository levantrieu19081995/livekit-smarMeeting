ALTER TABLE meeting_capture_sessions
    DROP CONSTRAINT IF EXISTS meeting_capture_sessions_meeting_id_key;

CREATE INDEX IF NOT EXISTS idx_meeting_capture_sessions_meeting_started
    ON meeting_capture_sessions(meeting_id, started_at, id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_meeting_capture_sessions_one_active
    ON meeting_capture_sessions(meeting_id)
    WHERE status IN ('STARTING', 'RUNNING', 'STOPPING');
