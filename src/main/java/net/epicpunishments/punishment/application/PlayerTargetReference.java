package net.epicpunishments.punishment.application;

import java.util.UUID;

public sealed interface PlayerTargetReference permits PlayerTargetReference.ById, PlayerTargetReference.ByName {
    record ById(UUID playerId) implements PlayerTargetReference {
    }

    record ByName(String playerName) implements PlayerTargetReference {
    }
}
