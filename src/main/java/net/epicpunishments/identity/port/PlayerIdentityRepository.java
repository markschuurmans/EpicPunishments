package net.epicpunishments.identity.port;

import net.epicpunishments.identity.domain.PlayerAddressHistory;
import net.epicpunishments.identity.domain.PlayerIdentity;
import net.epicpunishments.identity.domain.SuccessfulJoin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PlayerIdentityRepository {
    CompletionStage<Void> recordSuccessfulJoin(SuccessfulJoin join);

    CompletionStage<Optional<PlayerIdentity>> findByPlayerId(UUID playerId);

    CompletionStage<List<PlayerIdentity>> findByCurrentOrHistoricalName(String playerName);

    CompletionStage<List<PlayerAddressHistory>> findAddressHistory(UUID playerId);
}
