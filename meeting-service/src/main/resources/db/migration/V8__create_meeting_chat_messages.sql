ALTER TABLE meeting_participant_connections
    ADD COLUMN IF NOT EXISTS chat_token_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_meeting_connection_chat_token_hash
    ON meeting_participant_connections(chat_token_hash)
    WHERE chat_token_hash IS NOT NULL;

CREATE TABLE IF NOT EXISTS meeting_chat_messages (
    id BIGSERIAL PRIMARY KEY,

    meeting_id BIGINT NOT NULL
        REFERENCES meetings(id) ON DELETE CASCADE,

    participant_id BIGINT
        REFERENCES meeting_participants(id) ON DELETE SET NULL,

    participant_connection_id BIGINT
        REFERENCES meeting_participant_connections(id) ON DELETE SET NULL,

    client_message_id VARCHAR(36) NOT NULL,

    message_type VARCHAR(20) NOT NULL DEFAULT 'TEXT',

    content TEXT NOT NULL,

    sender_display_name VARCHAR(255) NOT NULL,

    sent_at TIMESTAMP NOT NULL,

    created_at TIMESTAMP NOT NULL,

    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT uk_meeting_chat_client_message
        UNIQUE (meeting_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS idx_meeting_chat_timeline
    ON meeting_chat_messages(meeting_id, sent_at, id);
