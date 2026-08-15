package net.epicpunishments.punishment.port;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface PlayerExemptionLookup {
    CompletionStage<Boolean> isExempt(UUID playerId);
}
