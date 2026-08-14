package net.epicpunishments.contract;

import net.epicpunishments.testing.InMemoryModerationStore;
import net.epicpunishments.testing.ModerationStoreTestFixture;

class InMemoryPunishmentRepositoryContractTest extends PunishmentRepositoryContract {
    @Override
    protected ModerationStoreTestFixture createFixture() {
        return new InMemoryModerationStore();
    }
}
