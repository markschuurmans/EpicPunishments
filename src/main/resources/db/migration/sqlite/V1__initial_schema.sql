CREATE TABLE players (
    player_uuid TEXT PRIMARY KEY,
    current_name TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);

CREATE TABLE player_names (
    player_uuid TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    player_name TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    PRIMARY KEY (player_uuid, normalized_name),
    FOREIGN KEY (player_uuid) REFERENCES players(player_uuid) ON DELETE CASCADE
);

CREATE INDEX player_names_lookup_idx ON player_names(normalized_name, player_uuid);

CREATE TABLE addresses (
    address_id INTEGER PRIMARY KEY AUTOINCREMENT,
    address_family TEXT NOT NULL CHECK (address_family IN ('IPV4', 'IPV6')),
    address_bytes BLOB NOT NULL UNIQUE,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    CHECK (length(address_bytes) IN (4, 16))
);

CREATE TABLE player_addresses (
    player_uuid TEXT NOT NULL,
    address_id INTEGER NOT NULL,
    first_successful_join_at TEXT NOT NULL,
    last_successful_join_at TEXT NOT NULL,
    join_count INTEGER NOT NULL CHECK (join_count > 0),
    PRIMARY KEY (player_uuid, address_id),
    FOREIGN KEY (player_uuid) REFERENCES players(player_uuid) ON DELETE CASCADE,
    FOREIGN KEY (address_id) REFERENCES addresses(address_id) ON DELETE RESTRICT
);

CREATE INDEX player_addresses_recent_idx
    ON player_addresses(player_uuid, last_successful_join_at DESC);

CREATE TABLE punishments (
    punishment_uuid TEXT PRIMARY KEY,
    punishment_type TEXT NOT NULL CHECK (punishment_type IN ('BAN', 'MUTE', 'WARNING')),
    target_type TEXT NOT NULL CHECK (target_type IN ('PLAYER', 'IP_ADDRESS')),
    target_player_uuid TEXT,
    target_address_id INTEGER,
    reason TEXT NOT NULL,
    issuer_type TEXT NOT NULL CHECK (issuer_type IN ('PLAYER', 'CONSOLE')),
    issuer_player_uuid TEXT,
    issuer_display_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT,
    revoked_by_type TEXT CHECK (revoked_by_type IN ('PLAYER', 'CONSOLE')),
    revoked_by_player_uuid TEXT,
    revoked_by_display_name TEXT,
    revoked_at TEXT,
    revocation_reason TEXT,
    CHECK (
        (target_type = 'PLAYER' AND target_player_uuid IS NOT NULL AND target_address_id IS NULL)
        OR
        (target_type = 'IP_ADDRESS' AND target_player_uuid IS NULL AND target_address_id IS NOT NULL)
    ),
    CHECK (
        (issuer_type = 'PLAYER' AND issuer_player_uuid IS NOT NULL)
        OR
        (issuer_type = 'CONSOLE' AND issuer_player_uuid IS NULL)
    ),
    CHECK (
        (revoked_at IS NULL AND revoked_by_type IS NULL AND revoked_by_player_uuid IS NULL
            AND revoked_by_display_name IS NULL AND revocation_reason IS NULL)
        OR
        (revoked_at IS NOT NULL AND revoked_by_type IS NOT NULL
            AND revoked_by_display_name IS NOT NULL AND revocation_reason IS NOT NULL
            AND ((revoked_by_type = 'PLAYER' AND revoked_by_player_uuid IS NOT NULL)
                OR (revoked_by_type = 'CONSOLE' AND revoked_by_player_uuid IS NULL)))
    ),
    FOREIGN KEY (target_address_id) REFERENCES addresses(address_id) ON DELETE RESTRICT
);

CREATE INDEX punishments_player_active_idx
    ON punishments(target_player_uuid, punishment_type, revoked_at, expires_at, created_at);
CREATE INDEX punishments_address_active_idx
    ON punishments(target_address_id, punishment_type, revoked_at, expires_at, created_at);

CREATE TABLE punishment_deliveries (
    punishment_uuid TEXT NOT NULL,
    affected_player_uuid TEXT NOT NULL,
    delivered_at TEXT NOT NULL,
    acknowledged_at TEXT,
    PRIMARY KEY (punishment_uuid, affected_player_uuid),
    FOREIGN KEY (punishment_uuid) REFERENCES punishments(punishment_uuid) ON DELETE RESTRICT
);

CREATE TABLE reports (
    report_uuid TEXT PRIMARY KEY,
    reporter_uuid TEXT NOT NULL,
    reporter_name TEXT NOT NULL,
    reported_uuid TEXT NOT NULL,
    reported_name TEXT NOT NULL,
    reason TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED')),
    assignee_type TEXT CHECK (assignee_type IN ('PLAYER', 'CONSOLE')),
    assignee_player_uuid TEXT,
    assignee_display_name TEXT,
    version INTEGER NOT NULL CHECK (version >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK (reporter_uuid <> reported_uuid),
    CHECK (
        (assignee_type IS NULL AND assignee_player_uuid IS NULL AND assignee_display_name IS NULL)
        OR
        (assignee_type = 'PLAYER' AND assignee_player_uuid IS NOT NULL AND assignee_display_name IS NOT NULL)
        OR
        (assignee_type = 'CONSOLE' AND assignee_player_uuid IS NULL AND assignee_display_name IS NOT NULL)
    )
);

CREATE INDEX reports_reporter_created_idx ON reports(reporter_uuid, created_at DESC, report_uuid);
CREATE INDEX reports_status_created_idx ON reports(status, created_at DESC, report_uuid);
CREATE INDEX reports_created_idx ON reports(created_at DESC, report_uuid);
CREATE INDEX reports_assignee_status_created_idx
    ON reports(assignee_player_uuid, status, created_at DESC, report_uuid);

CREATE TABLE report_responses (
    response_uuid TEXT PRIMARY KEY,
    report_uuid TEXT NOT NULL,
    administrator_type TEXT NOT NULL CHECK (administrator_type IN ('PLAYER', 'CONSOLE')),
    administrator_player_uuid TEXT,
    administrator_display_name TEXT NOT NULL,
    message TEXT NOT NULL,
    visibility TEXT NOT NULL CHECK (visibility IN ('REPORTER', 'STAFF_ONLY')),
    created_at TEXT NOT NULL,
    CHECK (
        (administrator_type = 'PLAYER' AND administrator_player_uuid IS NOT NULL)
        OR
        (administrator_type = 'CONSOLE' AND administrator_player_uuid IS NULL)
    ),
    FOREIGN KEY (report_uuid) REFERENCES reports(report_uuid) ON DELETE RESTRICT
);

CREATE INDEX report_responses_report_idx
    ON report_responses(report_uuid, created_at, response_uuid);

CREATE TABLE report_notifications (
    notification_uuid TEXT PRIMARY KEY,
    recipient_uuid TEXT NOT NULL,
    report_uuid TEXT NOT NULL,
    response_uuid TEXT,
    created_at TEXT NOT NULL,
    read_at TEXT,
    FOREIGN KEY (report_uuid) REFERENCES reports(report_uuid) ON DELETE RESTRICT,
    FOREIGN KEY (response_uuid) REFERENCES report_responses(response_uuid) ON DELETE RESTRICT
);

CREATE INDEX report_notifications_unread_idx
    ON report_notifications(recipient_uuid, read_at, created_at, notification_uuid);

CREATE TABLE audit_log (
    audit_uuid TEXT PRIMARY KEY,
    actor_type TEXT NOT NULL CHECK (actor_type IN ('PLAYER', 'CONSOLE')),
    actor_player_uuid TEXT,
    actor_display_name TEXT NOT NULL,
    action TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_uuid TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    details TEXT NOT NULL,
    CHECK (
        (actor_type = 'PLAYER' AND actor_player_uuid IS NOT NULL)
        OR
        (actor_type = 'CONSOLE' AND actor_player_uuid IS NULL)
    )
);

CREATE INDEX audit_log_entity_idx ON audit_log(entity_type, entity_uuid, occurred_at);
