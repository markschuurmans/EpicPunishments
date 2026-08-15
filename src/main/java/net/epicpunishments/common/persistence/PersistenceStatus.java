package net.epicpunishments.common.persistence;

import java.util.concurrent.CompletionStage;

public interface PersistenceStatus {
    String providerName();

    CompletionStage<PersistenceHealth> health();

    CompletionStage<String> schemaVersion();
}
