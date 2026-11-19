ALTER TABLE meeting_qa_questions
    ADD COLUMN answer VARCHAR(5000),
    ADD COLUMN answer_author_display_name VARCHAR(255);

-- Existing status-only answers remain valid; new written answers have a snapshot
-- of the responder and keep it when the question is hidden or soft-deleted.
ALTER TABLE meeting_qa_questions ADD CONSTRAINT ck_meeting_qa_written_answer CHECK (
    (answer IS NULL AND answer_author_display_name IS NULL)
    OR (answer IS NOT NULL AND length(btrim(answer)) BETWEEN 1 AND 5000
        AND answer_author_display_name IS NOT NULL
        AND length(btrim(answer_author_display_name)) > 0 AND answered_at IS NOT NULL)
);
