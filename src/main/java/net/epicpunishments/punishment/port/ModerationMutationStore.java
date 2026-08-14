package net.epicpunishments.punishment.port;

import net.epicpunishments.common.domain.AuditEntry;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentRevocation;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ModerationMutationStore {
    CompletionStage<ModerationMutationResult> createPunishment(Punishment punishment, AuditEntry auditEntry);

    CompletionStage<ModerationMutationResult> revokePunishment(
            UUID punishmentId,
            PunishmentRevocation revocation,
            AuditEntry auditEntry
    );
}
