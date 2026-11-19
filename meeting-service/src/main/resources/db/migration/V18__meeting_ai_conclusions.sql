CREATE TABLE meeting_conclusions (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('GENERATING','GENERATED','FAILED')),
    stage VARCHAR(20) NOT NULL CHECK (stage IN ('QUEUED','PROCESSING','COMPLETED','FAILED')),
    generation_id VARCHAR(36) NOT NULL UNIQUE,
    source_minutes_version INTEGER NOT NULL CHECK (source_minutes_version > 0),
    manual_content_before_ai TEXT NOT NULL,
    source_manual_content TEXT NOT NULL,
    context_json TEXT NOT NULL,
    ai_generated_content TEXT,
    final_content TEXT,
    generated_by_account_id BIGINT REFERENCES user_accounts(id),
    generated_at TIMESTAMP,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    available_at TIMESTAMP NOT NULL,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_conclusion_completion CHECK (
      (status = 'GENERATED' AND stage = 'COMPLETED' AND generated_at IS NOT NULL
         AND ai_generated_content IS NOT NULL AND final_content IS NOT NULL)
      OR (status = 'GENERATING' AND stage IN ('QUEUED','PROCESSING') AND generated_at IS NULL)
      OR (status = 'FAILED' AND stage = 'FAILED' AND generated_at IS NULL)
    )
);
CREATE INDEX idx_conclusion_due ON meeting_conclusions(status, available_at, lease_until);
