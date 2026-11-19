CREATE TABLE IF NOT EXISTS user_accounts (
    id BIGSERIAL PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL UNIQUE,
    username VARCHAR(100) UNIQUE,
    normalized_email VARCHAR(255) NOT NULL UNIQUE,
    full_name VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    avatar_url VARCHAR(500),
    account_status VARCHAR(30) NOT NULL,
    system_role VARCHAR(30) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS user_identities (
    id BIGSERIAL PRIMARY KEY,
    user_account_id BIGINT NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    provider VARCHAR(30) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    provider_email VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_subject ON user_identities(provider, provider_subject);

ALTER TABLE meetings ADD COLUMN IF NOT EXISTS created_by_account_id BIGINT REFERENCES user_accounts(id);
ALTER TABLE meeting_participants ADD COLUMN IF NOT EXISTS user_account_id BIGINT REFERENCES user_accounts(id);
ALTER TABLE meeting_participants ADD COLUMN IF NOT EXISTS invited_email_normalized VARCHAR(255);
ALTER TABLE meeting_participants ADD COLUMN IF NOT EXISTS participant_key VARCHAR(36);
ALTER TABLE meeting_participants ADD COLUMN IF NOT EXISTS join_type VARCHAR(20);
CREATE UNIQUE INDEX IF NOT EXISTS uk_meeting_participant_key ON meeting_participants(participant_key);
CREATE INDEX IF NOT EXISTS idx_meeting_participants_invited_email ON meeting_participants(invited_email_normalized);

CREATE TABLE IF NOT EXISTS meeting_participant_connections (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    participant_id BIGINT NOT NULL REFERENCES meeting_participants(id) ON DELETE CASCADE,
    attendee_id VARCHAR(100) NOT NULL,
    connection_id VARCHAR(100) NOT NULL,
    live_kit_identity VARCHAR(255) NOT NULL UNIQUE,
    joined_at TIMESTAMP NOT NULL,
    left_at TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

UPDATE meeting_participants
SET participant_key = COALESCE(participant_key, md5(random()::text || clock_timestamp()::text)),
    invited_email_normalized = COALESCE(invited_email_normalized, lower(email)),
    join_type = COALESCE(join_type, CASE WHEN user_id LIKE 'guest-%' THEN 'GUEST' ELSE 'AUTHENTICATED' END)
WHERE participant_key IS NULL
   OR invited_email_normalized IS NULL
   OR join_type IS NULL;
