package net.epicpunishments.contract;

import net.epicpunishments.testing.InMemoryReportStore;
import net.epicpunishments.testing.ReportStoreTestFixture;

class InMemoryReportRepositoryContractTest extends ReportRepositoryContract {
    @Override
    protected ReportStoreTestFixture createFixture() {
        return new InMemoryReportStore();
    }
}
