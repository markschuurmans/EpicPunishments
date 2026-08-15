CREATE UNIQUE INDEX reports_open_participants_unique_idx
    ON reports(reporter_uuid, reported_uuid)
    WHERE status IN ('OPEN', 'IN_REVIEW');
