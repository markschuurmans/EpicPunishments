package net.epicpunishments.punishment.application;

import net.epicpunishments.identity.domain.PlayerIdentity;
import net.epicpunishments.identity.port.PlayerIdentityRepository;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PlayerTargetResolver {
    private final PlayerIdentityRepository identities;
    private final PlayerTargetParser parser;

    public PlayerTargetResolver(PlayerIdentityRepository identities, PlayerTargetParser parser) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public CompletionStage<PlayerTargetResolution> resolve(String input) {
        final PlayerTargetReference reference;
        try {
            reference = parser.parse(input);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(PlayerTargetResolution.unresolved(
                    PlayerTargetResolution.Status.INVALID,
                    List.of()
            ));
        }
        return switch (reference) {
            case PlayerTargetReference.ById byId -> identities.findByPlayerId(byId.playerId())
                    .thenApply(found -> found.map(PlayerTargetResolution::resolved).orElseGet(() ->
                            PlayerTargetResolution.unresolved(PlayerTargetResolution.Status.NOT_FOUND, List.of())));
            case PlayerTargetReference.ByName byName -> identities.findByCurrentOrHistoricalName(byName.playerName())
                    .thenApply(PlayerTargetResolver::fromNameMatches);
        };
    }

    private static PlayerTargetResolution fromNameMatches(List<PlayerIdentity> matches) {
        return switch (matches.size()) {
            case 0 -> PlayerTargetResolution.unresolved(PlayerTargetResolution.Status.NOT_FOUND, List.of());
            case 1 -> PlayerTargetResolution.resolved(matches.getFirst());
            default -> PlayerTargetResolution.unresolved(PlayerTargetResolution.Status.AMBIGUOUS, matches);
        };
    }
}
