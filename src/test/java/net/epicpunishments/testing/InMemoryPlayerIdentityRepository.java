package net.epicpunishments.testing;

import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.identity.domain.PlayerAddressHistory;
import net.epicpunishments.identity.domain.PlayerIdentity;
import net.epicpunishments.identity.domain.SuccessfulJoin;
import net.epicpunishments.identity.port.PlayerIdentityRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class InMemoryPlayerIdentityRepository implements PlayerIdentityRepository {
    private final Map<UUID, PlayerIdentity> identities = new HashMap<>();
    private final Map<String, Set<UUID>> names = new HashMap<>();
    private final Map<UUID, Map<PlayerAddress, PlayerAddressHistory>> addresses = new HashMap<>();

    @Override
    public synchronized CompletionStage<Void> recordSuccessfulJoin(SuccessfulJoin join) {
        PlayerIdentity previous = identities.get(join.playerId());
        Instant firstSeen = previous == null ? join.joinedAt() : earliest(previous.firstSeenAt(), join.joinedAt());
        Instant lastSeen = previous == null ? join.joinedAt() : latest(previous.lastSeenAt(), join.joinedAt());
        PlayerIdentity updated = new PlayerIdentity(join.playerId(), join.playerName(), firstSeen, lastSeen);
        identities.put(join.playerId(), updated);
        if (previous != null) {
            rememberName(previous.currentName(), join.playerId());
        }
        rememberName(join.playerName(), join.playerId());

        Map<PlayerAddress, PlayerAddressHistory> playerAddresses =
                addresses.computeIfAbsent(join.playerId(), ignored -> new HashMap<>());
        PlayerAddressHistory history = playerAddresses.get(join.address());
        playerAddresses.put(join.address(), history == null
                ? new PlayerAddressHistory(join.address(), join.joinedAt(), join.joinedAt(), 1)
                : new PlayerAddressHistory(
                        join.address(),
                        earliest(history.firstSuccessfulJoinAt(), join.joinedAt()),
                        latest(history.lastSuccessfulJoinAt(), join.joinedAt()),
                        history.joinCount() + 1
                ));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Optional<PlayerIdentity>> findByPlayerId(UUID playerId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(identities.get(playerId)));
    }

    @Override
    public synchronized CompletionStage<List<PlayerIdentity>> findByCurrentOrHistoricalName(String playerName) {
        Set<UUID> matchingIds = names.getOrDefault(normalizeName(playerName), Set.of());
        List<PlayerIdentity> matching = matchingIds.stream()
                .map(identities::get)
                .sorted(Comparator.comparing(identity -> identity.playerId().toString()))
                .toList();
        return CompletableFuture.completedFuture(matching);
    }

    @Override
    public synchronized CompletionStage<List<PlayerAddressHistory>> findAddressHistory(UUID playerId) {
        List<PlayerAddressHistory> history = new ArrayList<>(
                addresses.getOrDefault(playerId, Map.of()).values()
        );
        history.sort(Comparator.comparing(PlayerAddressHistory::lastSuccessfulJoinAt).reversed());
        return CompletableFuture.completedFuture(List.copyOf(history));
    }

    private void rememberName(String name, UUID playerId) {
        names.computeIfAbsent(normalizeName(name), ignored -> new LinkedHashSet<>()).add(playerId);
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static Instant earliest(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static Instant latest(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }
}
