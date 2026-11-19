-- Additive Poll foundation. Lifecycle/authorization and vote APIs are implemented in later phases.
-- This redundant key enables a database-enforced same-meeting voter reference.
CREATE UNIQUE INDEX uk_meeting_participants_id_meeting
    ON meeting_participants (id, meeting_id);

CREATE TABLE meeting_polls (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    question VARCHAR(1000) NOT NULL,
    description TEXT,
    type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    allow_change_vote BOOLEAN NOT NULL DEFAULT FALSE,
    result_visibility VARCHAR(30) NOT NULL DEFAULT 'AFTER_CLOSE',
    min_selections INTEGER NOT NULL DEFAULT 1,
    max_selections INTEGER NOT NULL DEFAULT 1,
    created_by_account_id BIGINT NOT NULL REFERENCES user_accounts(id),
    opened_at TIMESTAMP,
    closed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_polls_id_meeting UNIQUE (id, meeting_id),
    CONSTRAINT ck_meeting_polls_question CHECK (length(btrim(question)) > 0),
    CONSTRAINT ck_meeting_polls_type CHECK (type IN ('YES_NO_ABSTAIN', 'SINGLE_CHOICE', 'MULTIPLE_CHOICE')),
    CONSTRAINT ck_meeting_polls_status CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_meeting_polls_visibility CHECK (result_visibility IN ('LIVE', 'AFTER_CLOSE', 'AUTHORIZED_ONLY')),
    CONSTRAINT ck_meeting_polls_selections CHECK (
        min_selections >= 1 AND max_selections >= min_selections
        AND (type = 'MULTIPLE_CHOICE' OR (min_selections = 1 AND max_selections = 1))
    ),
    CONSTRAINT ck_meeting_polls_lifecycle_times CHECK (
        (status = 'DRAFT' AND opened_at IS NULL AND closed_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'OPEN' AND opened_at IS NOT NULL AND closed_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'CLOSED' AND opened_at IS NOT NULL AND closed_at >= opened_at AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND closed_at IS NULL AND cancelled_at IS NOT NULL
            AND (opened_at IS NULL OR cancelled_at >= opened_at))
    ),
    CONSTRAINT ck_meeting_polls_closed_at CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL)
);

CREATE UNIQUE INDEX uk_meeting_polls_one_open
    ON meeting_polls (meeting_id) WHERE status = 'OPEN';
CREATE INDEX idx_meeting_polls_history
    ON meeting_polls (meeting_id, created_at DESC, id DESC);

CREATE TABLE meeting_poll_options (
    id BIGSERIAL PRIMARY KEY,
    poll_id BIGINT NOT NULL REFERENCES meeting_polls(id) ON DELETE CASCADE,
    code VARCHAR(20),
    label VARCHAR(255) NOT NULL,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_poll_options_id_poll UNIQUE (id, poll_id),
    CONSTRAINT uk_meeting_poll_options_order UNIQUE (poll_id, sort_order),
    CONSTRAINT uk_meeting_poll_options_code UNIQUE (poll_id, code),
    CONSTRAINT ck_meeting_poll_options_label CHECK (length(btrim(label)) > 0),
    CONSTRAINT ck_meeting_poll_options_order CHECK (sort_order >= 0),
    CONSTRAINT ck_meeting_poll_options_code CHECK (code IN ('YES', 'NO', 'ABSTAIN'))
);

-- In DRAFT these rows are the explicit voter selection. P2 fills all snapshot
-- fields atomically when opening the Poll and freezes this roster thereafter.
CREATE TABLE meeting_poll_eligible_voters (
    id BIGSERIAL PRIMARY KEY,
    poll_id BIGINT NOT NULL,
    meeting_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    participant_key_snapshot VARCHAR(36),
    full_name_snapshot VARCHAR(255),
    role_snapshot VARCHAR(20),
    snapshot_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_poll_eligible_voter UNIQUE (poll_id, participant_id),
    CONSTRAINT fk_meeting_poll_eligible_poll FOREIGN KEY (poll_id, meeting_id)
        REFERENCES meeting_polls(id, meeting_id) ON DELETE CASCADE,
    -- Deferred NO ACTION preserves voters while allowing existing meeting deletion
    -- to remove participants first and then cascade the whole Poll aggregate.
    CONSTRAINT fk_meeting_poll_eligible_participant FOREIGN KEY (participant_id, meeting_id)
        REFERENCES meeting_participants(id, meeting_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_meeting_poll_eligible_snapshot CHECK (
        (snapshot_at IS NULL AND participant_key_snapshot IS NULL AND full_name_snapshot IS NULL AND role_snapshot IS NULL)
        OR (snapshot_at IS NOT NULL AND participant_key_snapshot IS NOT NULL
            AND full_name_snapshot IS NOT NULL AND role_snapshot IS NOT NULL
            AND length(btrim(participant_key_snapshot)) > 0 AND length(btrim(full_name_snapshot)) > 0)
    ),
    CONSTRAINT ck_meeting_poll_eligible_role CHECK (role_snapshot IN ('CHAIRMAN', 'SECRETARY', 'MEMBER', 'GUEST'))
);
CREATE INDEX idx_meeting_poll_eligible_participant
    ON meeting_poll_eligible_voters (participant_id, meeting_id);

CREATE TABLE meeting_poll_ballots (
    id BIGSERIAL PRIMARY KEY,
    poll_id BIGINT NOT NULL REFERENCES meeting_polls(id) ON DELETE CASCADE,
    voter_id BIGINT NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_poll_ballots_id_poll UNIQUE (id, poll_id),
    CONSTRAINT uk_meeting_poll_ballots_voter UNIQUE (poll_id, voter_id),
    CONSTRAINT fk_meeting_poll_ballot_voter FOREIGN KEY (voter_id)
        REFERENCES meeting_participants(id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_meeting_poll_ballot_eligible FOREIGN KEY (poll_id, voter_id)
        REFERENCES meeting_poll_eligible_voters(poll_id, participant_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idx_meeting_poll_ballots_voter ON meeting_poll_ballots (voter_id);

CREATE TABLE meeting_poll_ballot_selections (
    id BIGSERIAL PRIMARY KEY,
    poll_id BIGINT NOT NULL REFERENCES meeting_polls(id) ON DELETE CASCADE,
    ballot_id BIGINT NOT NULL,
    option_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_poll_ballot_selection UNIQUE (ballot_id, option_id),
    CONSTRAINT fk_meeting_poll_selection_ballot FOREIGN KEY (ballot_id, poll_id)
        REFERENCES meeting_poll_ballots(id, poll_id) ON DELETE CASCADE,
    CONSTRAINT fk_meeting_poll_selection_option FOREIGN KEY (option_id, poll_id)
        REFERENCES meeting_poll_options(id, poll_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idx_meeting_poll_selections_option
    ON meeting_poll_ballot_selections (option_id, poll_id);
