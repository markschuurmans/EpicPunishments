package net.epicpunishments.punishment.application;

import net.epicpunishments.common.domain.Page;
import net.epicpunishments.identity.domain.PlayerIdentity;
import net.epicpunishments.punishment.domain.Punishment;

import java.util.Objects;
import java.util.Optional;

public record PlayerHistoryResult(
        PlayerModerationResult.Status status,
        Optional<PlayerIdentity> identity,
        Optional<Page<Punishment>> history
) {
    public PlayerHistoryResult {
        Objects.requireNonNull(status, "status");
        identity = Objects.requireNonNull(identity, "identity");
        history = Objects.requireNonNull(history, "history");
        if ((status == PlayerModerationResult.Status.APPLIED) != history.isPresent()) {
            throw new IllegalArgumentException("Only a successful history lookup carries a page");
        }
    }
}
