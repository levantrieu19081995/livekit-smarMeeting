-- Additive point meeting and speaking log; legacy meetings and media grants are unchanged.
ALTER TABLE meetings DROP CONSTRAINT ck_meetings_creation_mode;
ALTER TABLE meetings ADD CONSTRAINT ck_meetings_creation_mode
    CHECK (creation_mode IN ('INSTANT', 'LATER', 'SCHEDULED', 'POINT'));
ALTER TABLE meetings DROP CONSTRAINT ck_meetings_schedule;
ALTER TABLE meetings ADD CONSTRAINT ck_meetings_schedule CHECK (
    (creation_mode = 'SCHEDULED' AND start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)
    OR (creation_mode = 'POINT' AND access_type = 'PUBLIC' AND start_time IS NOT NULL
        AND end_time IS NOT NULL AND end_time > start_time)
    OR (creation_mode IN ('INSTANT', 'LATER') AND access_type = 'PUBLIC'
        AND (end_time IS NULL OR (start_time IS NOT NULL AND end_time >= start_time)))
) NOT VALID;

CREATE TABLE meeting_points (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL CHECK (length(btrim(name)) > 0),
    name_key VARCHAR(300) NOT NULL CHECK (length(btrim(name_key)) > 0),
    sort_order INTEGER NOT NULL CHECK (sort_order >= 0 AND sort_order < 100),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_point_id_meeting UNIQUE (id, meeting_id),
    CONSTRAINT uk_meeting_point_name UNIQUE (meeting_id, name_key),
    CONSTRAINT uk_meeting_point_order UNIQUE (meeting_id, sort_order)
);
ALTER TABLE meeting_participant_connections ADD COLUMN point_id BIGINT;
ALTER TABLE meeting_participant_connections ADD CONSTRAINT fk_meeting_connection_point
    FOREIGN KEY (point_id, meeting_id) REFERENCES meeting_points(id, meeting_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE meeting_participant_connections ADD CONSTRAINT uk_meeting_connection_speaking_scope
    UNIQUE (id, meeting_id, participant_id, point_id);

CREATE TABLE meeting_speaking_requests (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    participant_id BIGINT NOT NULL,
    participant_connection_id BIGINT NOT NULL,
    point_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    submitted_speaker_name VARCHAR(255),
    speaker_name VARCHAR(255) NOT NULL CHECK (length(btrim(speaker_name)) > 0),
    speaker_key VARCHAR(510) NOT NULL CHECK (length(btrim(speaker_key)) > 0),
    point_name VARCHAR(150) NOT NULL CHECK (length(btrim(point_name)) > 0),
    requested_by_display_name VARCHAR(255) NOT NULL CHECK (length(btrim(requested_by_display_name)) > 0),
    approved_by_display_name VARCHAR(255),
    finished_by_display_name VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING', 'SPEAKING', 'FINISHED', 'CANCELLED')),
    requested_at TIMESTAMP NOT NULL,
    approved_at TIMESTAMP,
    finished_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_meeting_speaking_retry UNIQUE (meeting_id, participant_id, request_id),
    CONSTRAINT fk_meeting_speaking_participant FOREIGN KEY (participant_id, meeting_id)
        REFERENCES meeting_participants(id, meeting_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_meeting_speaking_point FOREIGN KEY (point_id, meeting_id)
        REFERENCES meeting_points(id, meeting_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_meeting_speaking_connection FOREIGN KEY (participant_connection_id, meeting_id, participant_id, point_id)
        REFERENCES meeting_participant_connections(id, meeting_id, participant_id, point_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_meeting_speaking_times CHECK (
        (approved_at IS NULL OR approved_at >= requested_at)
        AND (finished_at IS NULL OR finished_at >= coalesce(approved_at, requested_at))
        AND ((status = 'WAITING' AND approved_at IS NULL AND finished_at IS NULL)
            OR (status = 'SPEAKING' AND approved_at IS NOT NULL AND finished_at IS NULL AND approved_by_display_name IS NOT NULL)
            OR (status = 'FINISHED' AND approved_at IS NOT NULL AND finished_at IS NOT NULL
                AND approved_by_display_name IS NOT NULL AND finished_by_display_name IS NOT NULL)
            OR (status = 'CANCELLED' AND approved_at IS NULL AND finished_at IS NOT NULL AND finished_by_display_name IS NOT NULL))
    )
);
CREATE UNIQUE INDEX uk_meeting_speaking_active ON meeting_speaking_requests(meeting_id, participant_id, point_id, speaker_key)
    WHERE status IN ('WAITING', 'SPEAKING');
CREATE INDEX idx_meeting_speaking_history ON meeting_speaking_requests(meeting_id, requested_at DESC, id DESC);
CREATE INDEX idx_meeting_speaking_active_lookup ON meeting_speaking_requests(meeting_id, status, participant_id);

-- POINT create retry receipts survive hard deletion, preventing old retries from recreating a removed meeting.
CREATE TABLE meeting_point_creation_receipts (
    id BIGSERIAL PRIMARY KEY,
    created_by_account_id BIGINT NOT NULL REFERENCES user_accounts(id),
    meeting_id BIGINT REFERENCES meetings(id) ON DELETE SET NULL,
    request_id VARCHAR(36) NOT NULL CHECK (request_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    input_hash VARCHAR(64) NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    receipt_json TEXT NOT NULL CHECK (length(receipt_json) > 0),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_point_creation_retry UNIQUE (created_by_account_id, request_id),
    CONSTRAINT uk_point_creation_meeting UNIQUE (meeting_id)
);
