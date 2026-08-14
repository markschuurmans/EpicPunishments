package net.epicpunishments.identity.application;

import net.epicpunishments.punishment.domain.Punishment;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JoinOutcome(
        DisconnectReason disconnectReason,
        Optional<Punishment> ban,
        List<Punishment> warnings,
        boolean degraded
) {
    public enum DisconnectReason {
        NONE,
        BANNED,
        TEMPORARY_FAILURE
    }

    public JoinOutcome {
        Objects.requireNonNull(disconnectReason, "disconnectReason");
        ban = Objects.requireNonNull(ban, "ban");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if ((disconnectReason == DisconnectReason.BANNED) != ban.isPresent()) {
            throw new IllegalArgumentException("Only a banned outcome may contain a ban");
        }
    }
}
