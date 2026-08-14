package net.epicpunishments.common.persistence;

import net.epicpunishments.identity.port.LoginAssessmentRepository;
import net.epicpunishments.identity.port.PlayerIdentityRepository;
import net.epicpunishments.punishment.port.ModerationMutationStore;
import net.epicpunishments.punishment.port.PunishmentRepository;
import net.epicpunishments.report.port.ReportMutationStore;
import net.epicpunishments.report.port.ReportRepository;

import java.util.concurrent.CompletionStage;

public interface PersistenceProvider {
    CompletionStage<Void> initialize();

    String providerName();

    CompletionStage<PersistenceHealth> health();

    CompletionStage<String> schemaVersion();

    PlayerIdentityRepository playerIdentities();

    LoginAssessmentRepository loginAssessments();

    PunishmentRepository punishments();

    ModerationMutationStore moderationMutations();

    ReportRepository reports();

    ReportMutationStore reportMutations();

    CompletionStage<Void> closeAsync();
}
