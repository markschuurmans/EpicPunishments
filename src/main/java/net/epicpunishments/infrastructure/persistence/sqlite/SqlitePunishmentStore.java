package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.common.persistence.PersistenceException;
import net.epicpunishments.common.persistence.PersistenceFailureKind;
import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.port.LoginAssessmentRepository;
import net.epicpunishments.punishment.domain.AddressPunishmentTarget;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentRevocation;
import net.epicpunishments.punishment.domain.PunishmentTarget;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.punishment.domain.SessionPunishments;
import net.epicpunishments.punishment.port.ModerationMutationResult;
import net.epicpunishments.punishment.port.ModerationMutationStore;
import net.epicpunishments.punishment.port.PunishmentRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

final class SqlitePunishmentStore implements PunishmentRepository, ModerationMutationStore, LoginAssessmentRepository {
    private static final String PUNISHMENT_COLUMNS = """
            SELECT p.punishment_uuid, p.punishment_type, p.target_type, p.target_player_uuid,
                   p.reason, p.issuer_type, p.issuer_player_uuid, p.issuer_display_name,
                   p.created_at, p.expires_at, p.revoked_by_type, p.revoked_by_player_uuid,
                   p.revoked_by_display_name, p.revoked_at, p.revocation_reason, a.address_bytes
            FROM punishments p
            LEFT JOIN addresses a ON a.address_id = p.target_address_id
            """;

    private final SqliteDatabase database;

    SqlitePunishmentStore(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletionStage<ModerationMutationResult> createPunishment(Punishment punishment, AuditEntry auditEntry) {
        Objects.requireNonNull(punishment, "punishment");
        requireMatchingAudit(punishment.id(), auditEntry);
        return database.transaction(connection -> {
            Long addressId = punishment.target() instanceof AddressPunishmentTarget addressTarget
                    ? ensureAddress(connection, addressTarget.address(), punishment.createdAt())
                    : null;
            insertPunishment(connection, punishment, addressId);
            SqliteMappings.insertAudit(database, connection, auditEntry);
            return ModerationMutationResult.applied(punishment);
        });
    }

    @Override
    public CompletionStage<ModerationMutationResult> revokePunishment(
            UUID punishmentId,
            PunishmentRevocation revocation,
            AuditEntry auditEntry
    ) {
        Objects.requireNonNull(punishmentId, "punishmentId");
        Objects.requireNonNull(revocation, "revocation");
        requireMatchingAudit(punishmentId, auditEntry);
        return database.transaction(connection -> {
            Optional<Punishment> found = findById(connection, punishmentId);
            if (found.isEmpty()) {
                return ModerationMutationResult.notFound();
            }
            Punishment current = found.orElseThrow();
            if (current.revocation().isPresent()) {
                return ModerationMutationResult.alreadyRevoked();
            }
            try (PreparedStatement statement = database.prepare(connection, """
                    UPDATE punishments
                    SET revoked_by_type = ?, revoked_by_player_uuid = ?, revoked_by_display_name = ?,
                        revoked_at = ?, revocation_reason = ?
                    WHERE punishment_uuid = ? AND revoked_at IS NULL
                    """)) {
                SqliteMappings.bindActor(statement, 1, revocation.actor());
                statement.setString(4, SqliteMappings.instant(revocation.revokedAt()));
                statement.setString(5, revocation.reason());
                statement.setString(6, SqliteMappings.uuid(punishmentId));
                if (statement.executeUpdate() != 1) {
                    return ModerationMutationResult.alreadyRevoked();
                }
            }
            SqliteMappings.insertAudit(database, connection, auditEntry);
            return ModerationMutationResult.applied(current.revoke(revocation));
        });
    }

    @Override
    public CompletionStage<Optional<Punishment>> findById(UUID punishmentId) {
        Objects.requireNonNull(punishmentId, "punishmentId");
        return database.read(connection -> findById(connection, punishmentId));
    }

    @Override
    public CompletionStage<SessionPunishments> findActiveForPlayer(UUID playerId, Instant at) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(at, "at");
        return database.read(connection -> activeForPlayer(connection, playerId, at));
    }

    @Override
    public CompletionStage<SessionPunishments> findActiveForAddress(
            PlayerAddress address,
            UUID affectedPlayerId,
            Instant at
    ) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(affectedPlayerId, "affectedPlayerId");
        Objects.requireNonNull(at, "at");
        return database.read(connection -> activeForAddress(connection, address, affectedPlayerId, at));
    }

    @Override
    public CompletionStage<Page<Punishment>> findHistory(
            PunishmentTarget target,
            Optional<PunishmentType> type,
            PageRequest pageRequest
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pageRequest, "pageRequest");
        return database.read(connection -> {
            String targetPredicate = target instanceof PlayerPunishmentTarget
                    ? "p.target_type = 'PLAYER' AND p.target_player_uuid = ?"
                    : "p.target_type = 'IP_ADDRESS' AND a.address_bytes = ?";
            String predicate = targetPredicate + (type.isPresent() ? " AND p.punishment_type = ?" : "");
            long total = countHistory(connection, predicate, target, type);
            var punishments = new ArrayList<Punishment>();
            String sql = PUNISHMENT_COLUMNS + " WHERE " + predicate
                    + " ORDER BY p.created_at DESC, p.punishment_uuid LIMIT ? OFFSET ?";
            try (PreparedStatement statement = database.prepare(connection, sql)) {
                bindTarget(statement, 1, target);
                int nextIndex = 2;
                if (type.isPresent()) {
                    statement.setString(nextIndex++, type.orElseThrow().name());
                }
                statement.setInt(nextIndex++, pageRequest.size());
                statement.setLong(nextIndex, pageRequest.offset());
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        punishments.add(SqliteMappings.punishment(results));
                    }
                }
            }
            return new Page<>(punishments, pageRequest.page(), pageRequest.size(), total);
        });
    }

    @Override
    public CompletionStage<Boolean> recordWarningDelivery(
            UUID punishmentId,
            UUID affectedPlayerId,
            Instant deliveredAt
    ) {
        Objects.requireNonNull(punishmentId, "punishmentId");
        Objects.requireNonNull(affectedPlayerId, "affectedPlayerId");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        return database.transaction(connection -> {
            try (PreparedStatement check = database.prepare(connection, """
                    SELECT 1 FROM punishments WHERE punishment_uuid = ? AND punishment_type = 'WARNING'
                    """)) {
                check.setString(1, SqliteMappings.uuid(punishmentId));
                try (ResultSet results = check.executeQuery()) {
                    if (!results.next()) {
                        throw new PersistenceException(
                                PersistenceFailureKind.INVALID_DATA,
                                "Warning punishment does not exist"
                        );
                    }
                }
            }
            try (PreparedStatement insert = database.prepare(connection, """
                    INSERT INTO punishment_deliveries (
                        punishment_uuid, affected_player_uuid, delivered_at, acknowledged_at
                    ) VALUES (?, ?, ?, NULL)
                    ON CONFLICT(punishment_uuid, affected_player_uuid) DO NOTHING
                    """)) {
                insert.setString(1, SqliteMappings.uuid(punishmentId));
                insert.setString(2, SqliteMappings.uuid(affectedPlayerId));
                insert.setString(3, SqliteMappings.instant(deliveredAt));
                return insert.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<LoginAssessment> assessLogin(UUID playerId, PlayerAddress address, Instant assessedAt) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(assessedAt, "assessedAt");
        return database.read(connection -> {
            SessionPunishments player = activeForPlayer(connection, playerId, assessedAt);
            SessionPunishments addressed = activeForAddress(connection, address, playerId, assessedAt);
            return new LoginAssessment(
                    playerId,
                    address,
                    assessedAt,
                    new SessionPunishments(
                            combine(player.bans(), addressed.bans()),
                            combine(player.mutes(), addressed.mutes()),
                            combine(player.undeliveredWarnings(), addressed.undeliveredWarnings())
                    )
            );
        });
    }

    private SessionPunishments activeForPlayer(Connection connection, UUID playerId, Instant at) throws SQLException {
        String sql = PUNISHMENT_COLUMNS + """
                WHERE p.target_type = 'PLAYER' AND p.target_player_uuid = ?
                  AND p.created_at <= ? AND p.revoked_at IS NULL
                  AND (p.expires_at IS NULL OR p.expires_at > ?)
                ORDER BY p.created_at, p.punishment_uuid
                """;
        return active(connection, sql, statement -> statement.setString(1, SqliteMappings.uuid(playerId)), playerId, at);
    }

    private SessionPunishments activeForAddress(
            Connection connection,
            PlayerAddress address,
            UUID affectedPlayerId,
            Instant at
    ) throws SQLException {
        String sql = PUNISHMENT_COLUMNS + """
                WHERE p.target_type = 'IP_ADDRESS' AND a.address_bytes = ?
                  AND p.created_at <= ? AND p.revoked_at IS NULL
                  AND (p.expires_at IS NULL OR p.expires_at > ?)
                ORDER BY p.created_at, p.punishment_uuid
                """;
        return active(connection, sql, statement -> statement.setBytes(1, address.bytes()), affectedPlayerId, at);
    }

    private SessionPunishments active(
            Connection connection,
            String sql,
            StatementBinder targetBinder,
            UUID affectedPlayerId,
            Instant at
    ) throws SQLException {
        var active = new ArrayList<Punishment>();
        try (PreparedStatement statement = database.prepare(connection, sql)) {
            targetBinder.bind(statement);
            statement.setString(2, SqliteMappings.instant(at));
            statement.setString(3, SqliteMappings.instant(at));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    active.add(SqliteMappings.punishment(results));
                }
            }
        }
        var bans = new ArrayList<Punishment>();
        var mutes = new ArrayList<Punishment>();
        var warnings = new ArrayList<Punishment>();
        for (Punishment punishment : active) {
            switch (punishment.type()) {
                case BAN -> bans.add(punishment);
                case MUTE -> mutes.add(punishment);
                case WARNING -> {
                    if (!wasDelivered(connection, punishment.id(), affectedPlayerId)) {
                        warnings.add(punishment);
                    }
                }
            }
        }
        return new SessionPunishments(bans, mutes, warnings);
    }

    private boolean wasDelivered(Connection connection, UUID punishmentId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                SELECT 1 FROM punishment_deliveries
                WHERE punishment_uuid = ? AND affected_player_uuid = ?
                """)) {
            statement.setString(1, SqliteMappings.uuid(punishmentId));
            statement.setString(2, SqliteMappings.uuid(playerId));
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private Optional<Punishment> findById(Connection connection, UUID punishmentId) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection,
                PUNISHMENT_COLUMNS + " WHERE p.punishment_uuid = ?")) {
            statement.setString(1, SqliteMappings.uuid(punishmentId));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(SqliteMappings.punishment(results)) : Optional.empty();
            }
        }
    }

    private void insertPunishment(Connection connection, Punishment punishment, Long addressId) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO punishments (
                    punishment_uuid, punishment_type, target_type, target_player_uuid, target_address_id,
                    reason, issuer_type, issuer_player_uuid, issuer_display_name, created_at, expires_at,
                    revoked_by_type, revoked_by_player_uuid, revoked_by_display_name, revoked_at, revocation_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, SqliteMappings.uuid(punishment.id()));
            statement.setString(2, punishment.type().name());
            statement.setString(3, punishment.target().type().name());
            statement.setString(4, punishment.target() instanceof PlayerPunishmentTarget target
                    ? SqliteMappings.uuid(target.playerId()) : null);
            if (addressId == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setLong(5, addressId);
            }
            statement.setString(6, punishment.reason());
            SqliteMappings.bindActor(statement, 7, punishment.issuer());
            statement.setString(10, SqliteMappings.instant(punishment.createdAt()));
            statement.setString(11, punishment.expiresAt().map(SqliteMappings::instant).orElse(null));
            if (punishment.revocation().isPresent()) {
                PunishmentRevocation revocation = punishment.revocation().orElseThrow();
                SqliteMappings.bindActor(statement, 12, revocation.actor());
                statement.setString(15, SqliteMappings.instant(revocation.revokedAt()));
                statement.setString(16, revocation.reason());
            } else {
                statement.setString(12, null);
                statement.setString(13, null);
                statement.setString(14, null);
                statement.setString(15, null);
                statement.setString(16, null);
            }
            statement.executeUpdate();
        }
    }

    private long ensureAddress(Connection connection, PlayerAddress address, Instant observedAt) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO addresses (address_family, address_bytes, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(address_bytes) DO UPDATE SET
                    first_seen_at = min(addresses.first_seen_at, excluded.first_seen_at),
                    last_seen_at = max(addresses.last_seen_at, excluded.last_seen_at)
                """)) {
            statement.setString(1, address.family().name());
            statement.setBytes(2, address.bytes());
            statement.setString(3, SqliteMappings.instant(observedAt));
            statement.setString(4, SqliteMappings.instant(observedAt));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = database.prepare(connection,
                "SELECT address_id FROM addresses WHERE address_bytes = ?")) {
            statement.setBytes(1, address.bytes());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Inserted address could not be found");
                }
                return results.getLong(1);
            }
        }
    }

    private long countHistory(
            Connection connection,
            String predicate,
            PunishmentTarget target,
            Optional<PunishmentType> type
    ) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection,
                "SELECT count(*) FROM punishments p "
                        + "LEFT JOIN addresses a ON a.address_id = p.target_address_id WHERE "
                        + predicate)) {
            bindTarget(statement, 1, target);
            if (type.isPresent()) {
                statement.setString(2, type.orElseThrow().name());
            }
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getLong(1);
            }
        }
    }

    private static void bindTarget(PreparedStatement statement, int index, PunishmentTarget target) throws SQLException {
        if (target instanceof PlayerPunishmentTarget playerTarget) {
            statement.setString(index, SqliteMappings.uuid(playerTarget.playerId()));
        } else if (target instanceof AddressPunishmentTarget addressTarget) {
            statement.setBytes(index, addressTarget.address().bytes());
        } else {
            throw new IllegalArgumentException("Unsupported punishment target " + target.getClass().getName());
        }
    }

    private static void requireMatchingAudit(UUID entityId, AuditEntry auditEntry) {
        Objects.requireNonNull(auditEntry, "auditEntry");
        if (!auditEntry.entityId().equals(entityId)) {
            throw new IllegalArgumentException("Audit entry does not refer to the mutated punishment");
        }
    }

    private static List<Punishment> combine(List<Punishment> first, List<Punishment> second) {
        var combined = new ArrayList<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
