UPDATE meeting_minutes_jobs j
SET stage = 'GENERATING_MINUTES',
    updated_at = CURRENT_TIMESTAMP
WHERE j.status = 'PROCESSING'
  AND j.stage = 'DRAFT_READY'
  AND j.transcript_status = 'READY'
  AND j.progress_percent < 100
  AND NOT EXISTS (
      SELECT 1
      FROM meeting_minutes m
      WHERE m.meeting_id = j.meeting_id
  );
