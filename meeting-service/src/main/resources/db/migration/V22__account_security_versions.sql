-- Credential epochs are durable; disable/re-enable or a global role change cannot revive an old session/token.
ALTER TABLE user_accounts ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0 CHECK (security_version >= 0);
-- Profile/login writes must not overwrite a concurrent disable or reset its credential epoch.
ALTER TABLE user_accounts ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0);
ALTER TABLE meeting_participant_connections ADD COLUMN account_security_version BIGINT CHECK (account_security_version >= 0);
