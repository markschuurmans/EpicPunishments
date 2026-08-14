package net.epicpunishments.infrastructure.persistence.sqlite;

import net.epicpunishments.contract.PlayerIdentityRepositoryContract;
import net.epicpunishments.identity.port.PlayerIdentityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class SqlitePlayerIdentityRepositoryContractTest extends PlayerIdentityRepositoryContract {
    @TempDir
    Path temporaryDirectory;

    private SqliteTestContext context;

    @Override
    protected PlayerIdentityRepository createRepository() {
        context = new SqliteTestContext(temporaryDirectory.resolve("identity.db"));
        return context.provider().playerIdentities();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }
}
