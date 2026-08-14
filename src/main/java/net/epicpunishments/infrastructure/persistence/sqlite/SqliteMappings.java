package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.ActorType;
import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.AddressPunishmentTarget;
import net.epicpunishments.punishment.domain.PlayerPunishmentTarget;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentRevocation;
import net.epicpunishments.punishment.domain.PunishmentTarget;
import net.epicpunishments.punishment.domain.PunishmentTargetType;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportParticipant;
import net.epicpunishments.report.domain.ReportStatus;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Optional;
import java.util.UUID;

final class SqliteMappings {
    private static final DateTimeFormatter INSTANT_FORMATTER = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private SqliteMappings() {
    }

    static String uuid(UUID value) {
        return value.toString();
    }

    static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    static String instant(Instant value) {
        return INSTANT_FORMATTER.format(value);
    }

    static Instant instant(String value) {
        return Instant.from(INSTANT_FORMATTER.parse(value));
    }

    static Optional<Instant> optionalInstant(ResultSet results, String column) throws SQLException {
        String value = results.getString(column);
        return value == null ? Optional.empty() : Optional.of(instant(value));
    }

    static Actor actor(String type, String playerId, String displayName) {
        ActorType actorType = ActorType.valueOf(type);
        return actorType == ActorType.CONSOLE
                ? new Actor(actorType, Optional.empty(), displayName)
                : new Actor(actorType, Optional.of(uuid(playerId)), displayName);
    }

    static void bindActor(PreparedStatement statement, int firstIndex, Actor actor) throws SQLException {
        statement.setString(firstIndex, actor.type().name());
        statement.setString(firstIndex + 1, actor.playerId().map(SqliteMappings::uuid).orElse(null));
        statement.setString(firstIndex + 2, actor.displayName());
    }

    static Punishment punishment(ResultSet results) throws SQLException {
        PunishmentTargetType targetType = PunishmentTargetType.valueOf(results.getString("target_type"));
        PunishmentTarget target = switch (targetType) {
            case PLAYER -> new PlayerPunishmentTarget(uuid(results.getString("target_player_uuid")));
            case IP_ADDRESS -> new AddressPunishmentTarget(PlayerAddress.fromBytes(results.getBytes("address_bytes")));
        };
        Actor issuer = actor(
                results.getString("issuer_type"),
                results.getString("issuer_player_uuid"),
                results.getString("issuer_display_name")
        );
        String revokedAt = results.getString("revoked_at");
        Optional<PunishmentRevocation> revocation = revokedAt == null
                ? Optional.empty()
                : Optional.of(new PunishmentRevocation(
                        actor(
                                results.getString("revoked_by_type"),
                                results.getString("revoked_by_player_uuid"),
                                results.getString("revoked_by_display_name")
                        ),
                        instant(revokedAt),
                        results.getString("revocation_reason")
                ));
        return new Punishment(
                uuid(results.getString("punishment_uuid")),
                PunishmentType.valueOf(results.getString("punishment_type")),
                target,
                results.getString("reason"),
                issuer,
                instant(results.getString("created_at")),
                optionalInstant(results, "expires_at"),
                revocation
        );
    }

    static Report report(ResultSet results) throws SQLException {
        String assigneeType = results.getString("assignee_type");
        Optional<Actor> assignee = assigneeType == null
                ? Optional.empty()
                : Optional.of(actor(
                        assigneeType,
                        results.getString("assignee_player_uuid"),
                        results.getString("assignee_display_name")
                ));
        return new Report(
                uuid(results.getString("report_uuid")),
                new ReportParticipant(
                        uuid(results.getString("reporter_uuid")),
                        results.getString("reporter_name")
                ),
                new ReportParticipant(
                        uuid(results.getString("reported_uuid")),
                        results.getString("reported_name")
                ),
                results.getString("reason"),
                ReportStatus.valueOf(results.getString("status")),
                assignee,
                results.getLong("version"),
                instant(results.getString("created_at")),
                instant(results.getString("updated_at"))
        );
    }

    static void insertAudit(SqliteDatabase database, java.sql.Connection connection, AuditEntry audit)
            throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO audit_log (
                    audit_uuid, actor_type, actor_player_uuid, actor_display_name,
                    action, entity_type, entity_uuid, occurred_at, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, uuid(audit.id()));
            bindActor(statement, 2, audit.actor());
            statement.setString(5, audit.action());
            statement.setString(6, audit.entityType());
            statement.setString(7, uuid(audit.entityId()));
            statement.setString(8, instant(audit.occurredAt()));
            statement.setString(9, audit.details());
            statement.executeUpdate();
        }
    }
}
