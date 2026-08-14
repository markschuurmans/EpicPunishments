package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.domain.PlayerAddressHistory;
import net.epicpunishments.identity.domain.PlayerIdentity;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import net.epicpunishments.identity.port.PlayerIdentityRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

final class SqlitePlayerIdentityRepository implements PlayerIdentityRepository {
    private final SqliteDatabase database;

    SqlitePlayerIdentityRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletionStage<Void> recordSuccessfulJoin(SuccessfulJoin join) {
        Objects.requireNonNull(join, "join");
        return database.transaction(connection -> {
            upsertPlayer(connection, join);
            upsertName(connection, join);
            long addressId = upsertAddress(connection, join);
            upsertPlayerAddress(connection, join, addressId);
            return null;
        });
    }

    @Override
    public CompletionStage<Optional<PlayerIdentity>> findByPlayerId(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return database.read(connection -> {
            try (PreparedStatement statement = database.prepare(connection, """
                    SELECT player_uuid, current_name, first_seen_at, last_seen_at
                    FROM players
                    WHERE player_uuid = ?
                    """)) {
                statement.setString(1, SqliteMappings.uuid(playerId));
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? Optional.of(readIdentity(results)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<List<PlayerIdentity>> findByCurrentOrHistoricalName(String playerName) {
        Objects.requireNonNull(playerName, "playerName");
        String normalizedName = playerName.toLowerCase(Locale.ROOT);
        return database.read(connection -> {
            var identities = new ArrayList<PlayerIdentity>();
            try (PreparedStatement statement = database.prepare(connection, """
                    SELECT p.player_uuid, p.current_name, p.first_seen_at, p.last_seen_at
                    FROM player_names n
                    JOIN players p ON p.player_uuid = n.player_uuid
                    WHERE n.normalized_name = ?
                    ORDER BY p.player_uuid
                    """)) {
                statement.setString(1, normalizedName);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        identities.add(readIdentity(results));
                    }
                }
            }
            return List.copyOf(identities);
        });
    }

    @Override
    public CompletionStage<List<PlayerAddressHistory>> findAddressHistory(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return database.read(connection -> {
            var history = new ArrayList<PlayerAddressHistory>();
            try (PreparedStatement statement = database.prepare(connection, """
                    SELECT a.address_bytes, pa.first_successful_join_at,
                           pa.last_successful_join_at, pa.join_count
                    FROM player_addresses pa
                    JOIN addresses a ON a.address_id = pa.address_id
                    WHERE pa.player_uuid = ?
                    ORDER BY pa.last_successful_join_at DESC, a.address_id
                    """)) {
                statement.setString(1, SqliteMappings.uuid(playerId));
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        history.add(new PlayerAddressHistory(
                                PlayerAddress.fromBytes(results.getBytes("address_bytes")),
                                SqliteMappings.instant(results.getString("first_successful_join_at")),
                                SqliteMappings.instant(results.getString("last_successful_join_at")),
                                results.getLong("join_count")
                        ));
                    }
                }
            }
            return List.copyOf(history);
        });
    }

    private void upsertPlayer(Connection connection, SuccessfulJoin join) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO players (player_uuid, current_name, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    current_name = CASE
                        WHEN excluded.last_seen_at >= players.last_seen_at THEN excluded.current_name
                        ELSE players.current_name
                    END,
                    first_seen_at = min(players.first_seen_at, excluded.first_seen_at),
                    last_seen_at = max(players.last_seen_at, excluded.last_seen_at)
                """)) {
            statement.setString(1, SqliteMappings.uuid(join.playerId()));
            statement.setString(2, join.playerName());
            statement.setString(3, SqliteMappings.instant(join.joinedAt()));
            statement.setString(4, SqliteMappings.instant(join.joinedAt()));
            statement.executeUpdate();
        }
    }

    private void upsertName(Connection connection, SuccessfulJoin join) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO player_names (
                    player_uuid, normalized_name, player_name, first_seen_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid, normalized_name) DO UPDATE SET
                    player_name = CASE
                        WHEN excluded.last_seen_at >= player_names.last_seen_at THEN excluded.player_name
                        ELSE player_names.player_name
                    END,
                    first_seen_at = min(player_names.first_seen_at, excluded.first_seen_at),
                    last_seen_at = max(player_names.last_seen_at, excluded.last_seen_at)
                """)) {
            statement.setString(1, SqliteMappings.uuid(join.playerId()));
            statement.setString(2, join.playerName().toLowerCase(Locale.ROOT));
            statement.setString(3, join.playerName());
            statement.setString(4, SqliteMappings.instant(join.joinedAt()));
            statement.setString(5, SqliteMappings.instant(join.joinedAt()));
            statement.executeUpdate();
        }
    }

    private long upsertAddress(Connection connection, SuccessfulJoin join) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO addresses (address_family, address_bytes, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(address_bytes) DO UPDATE SET
                    first_seen_at = min(addresses.first_seen_at, excluded.first_seen_at),
                    last_seen_at = max(addresses.last_seen_at, excluded.last_seen_at)
                """)) {
            statement.setString(1, join.address().family().name());
            statement.setBytes(2, join.address().bytes());
            statement.setString(3, SqliteMappings.instant(join.joinedAt()));
            statement.setString(4, SqliteMappings.instant(join.joinedAt()));
            statement.executeUpdate();
        }
        return findAddressId(connection, join.address());
    }

    private void upsertPlayerAddress(Connection connection, SuccessfulJoin join, long addressId) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                INSERT INTO player_addresses (
                    player_uuid, address_id, first_successful_join_at, last_successful_join_at, join_count
                ) VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(player_uuid, address_id) DO UPDATE SET
                    first_successful_join_at = min(
                        player_addresses.first_successful_join_at,
                        excluded.first_successful_join_at
                    ),
                    last_successful_join_at = max(
                        player_addresses.last_successful_join_at,
                        excluded.last_successful_join_at
                    ),
                    join_count = player_addresses.join_count + 1
                """)) {
            statement.setString(1, SqliteMappings.uuid(join.playerId()));
            statement.setLong(2, addressId);
            statement.setString(3, SqliteMappings.instant(join.joinedAt()));
            statement.setString(4, SqliteMappings.instant(join.joinedAt()));
            statement.executeUpdate();
        }
    }

    private long findAddressId(Connection connection, PlayerAddress address) throws SQLException {
        try (PreparedStatement statement = database.prepare(connection, """
                SELECT address_id FROM addresses WHERE address_bytes = ?
                """)) {
            statement.setBytes(1, address.bytes());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Inserted address could not be found");
                }
                return results.getLong(1);
            }
        }
    }

    private static PlayerIdentity readIdentity(ResultSet results) throws SQLException {
        return new PlayerIdentity(
                SqliteMappings.uuid(results.getString("player_uuid")),
                results.getString("current_name"),
                SqliteMappings.instant(results.getString("first_seen_at")),
                SqliteMappings.instant(results.getString("last_seen_at"))
        );
    }
}
