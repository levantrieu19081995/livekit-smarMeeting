ALTER TABLE meetings ADD COLUMN creation_mode VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED';
ALTER TABLE meetings ALTER COLUMN start_time DROP NOT NULL;
ALTER TABLE meetings ALTER COLUMN end_time DROP NOT NULL;
ALTER TABLE meetings ADD CONSTRAINT ck_meetings_creation_mode
    CHECK (creation_mode IN ('INSTANT', 'LATER', 'SCHEDULED'));
ALTER TABLE meetings ADD CONSTRAINT ck_meetings_schedule
    CHECK ((creation_mode = 'SCHEDULED' AND start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)
        OR (creation_mode IN ('INSTANT', 'LATER') AND access_type = 'PUBLIC'
            AND (end_time IS NULL OR (start_time IS NOT NULL AND end_time >= start_time)))) NOT VALID;

ALTER TABLE meeting_polls ALTER COLUMN created_by_account_id DROP NOT NULL;
ALTER TABLE meeting_polls ADD COLUMN created_by_participant_id BIGINT;
ALTER TABLE meeting_polls ADD CONSTRAINT fk_meeting_poll_creator_participant
    FOREIGN KEY (created_by_participant_id, meeting_id) REFERENCES meeting_participants(id, meeting_id);
ALTER TABLE meeting_polls ADD CONSTRAINT ck_meeting_poll_creator
    CHECK (created_by_account_id IS NOT NULL OR created_by_participant_id IS NOT NULL);
