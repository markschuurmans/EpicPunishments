package net.epicpunishments.contract;

import net.epicpunishments.identity.port.PlayerIdentityRepository;
import net.epicpunishments.testing.InMemoryPlayerIdentityRepository;

class InMemoryPlayerIdentityRepositoryContractTest extends PlayerIdentityRepositoryContract {
    @Override
    protected PlayerIdentityRepository createRepository() {
        return new InMemoryPlayerIdentityRepository();
    }
}
