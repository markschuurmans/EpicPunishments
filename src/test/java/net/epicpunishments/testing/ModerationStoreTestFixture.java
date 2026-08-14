package net.epicpunishments.testing;

import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.identity.port.LoginAssessmentRepository;
import net.epicpunishments.punishment.port.ModerationMutationStore;
import net.epicpunishments.punishment.port.PunishmentRepository;

import java.util.List;

public interface ModerationStoreTestFixture {
    PunishmentRepository punishments();

    ModerationMutationStore mutations();

    LoginAssessmentRepository loginAssessments();

    List<AuditEntry> auditEntries();

    void failNextAuditWrite();
}
