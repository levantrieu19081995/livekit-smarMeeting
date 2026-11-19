-- Independent Q&A aggregate: existing chat, poll, recording and minutes tables are unchanged.
CREATE TABLE meeting_qa_settings (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    anonymous_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    moderation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_qa_settings_meeting UNIQUE (meeting_id)
);

CREATE TABLE meeting_qa_questions (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    participant_id BIGINT NOT NULL,
    client_question_id VARCHAR(36) NOT NULL,
    content VARCHAR(2000) NOT NULL CHECK (length(btrim(content)) > 0),
    anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    author_display_name VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('PENDING', 'OPEN', 'ANSWERED', 'HIDDEN', 'DELETED')),
    answered_at TIMESTAMP,
    upvote_count BIGINT NOT NULL DEFAULT 0 CHECK (upvote_count >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_qa_question_id_meeting UNIQUE (id, meeting_id),
    CONSTRAINT uk_meeting_qa_question_retry UNIQUE (meeting_id, participant_id, client_question_id),
    CONSTRAINT fk_meeting_qa_question_participant FOREIGN KEY (participant_id, meeting_id)
        REFERENCES meeting_participants(id, meeting_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_meeting_qa_anonymous_author CHECK (
        (anonymous AND author_display_name IS NULL)
        OR (NOT anonymous AND author_display_name IS NOT NULL AND length(btrim(author_display_name)) > 0)
    ),
    CONSTRAINT ck_meeting_qa_answered_at CHECK (status <> 'ANSWERED' OR answered_at IS NOT NULL)
);
CREATE INDEX idx_meeting_qa_question_history ON meeting_qa_questions (meeting_id, created_at DESC, id DESC);
CREATE INDEX idx_meeting_qa_question_visibility ON meeting_qa_questions (meeting_id, status, participant_id);
CREATE INDEX idx_meeting_qa_question_popular ON meeting_qa_questions (meeting_id, upvote_count DESC, id DESC);

CREATE TABLE meeting_qa_upvotes (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_qa_upvote UNIQUE (question_id, participant_id),
    CONSTRAINT fk_meeting_qa_upvote_question FOREIGN KEY (question_id, meeting_id)
        REFERENCES meeting_qa_questions(id, meeting_id) ON DELETE CASCADE,
    CONSTRAINT fk_meeting_qa_upvote_participant FOREIGN KEY (participant_id, meeting_id)
        REFERENCES meeting_participants(id, meeting_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idx_meeting_qa_upvote_participant ON meeting_qa_upvotes (participant_id, question_id);
