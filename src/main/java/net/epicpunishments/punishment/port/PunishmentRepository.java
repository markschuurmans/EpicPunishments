package net.epicpunishments.punishment.port;

import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentTarget;
import net.epicpunishments.punishment.domain.SessionPunishments;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PunishmentRepository {
    CompletionStage<Optional<Punishment>> findById(UUID punishmentId);

    CompletionStage<SessionPunishments> findActiveForPlayer(UUID playerId, Instant at);

    CompletionStage<SessionPunishments> findActiveForAddress(PlayerAddress address, UUID affectedPlayerId, Instant at);

    CompletionStage<Page<Punishment>> findHistory(PunishmentTarget target, PageRequest pageRequest);

    /**
     * Records a delivery once for a punishment/player pair.
     *
     * @return {@code true} when inserted, or {@code false} when it was already recorded
     */
    CompletionStage<Boolean> recordWarningDelivery(UUID punishmentId, UUID affectedPlayerId, Instant deliveredAt);
}
