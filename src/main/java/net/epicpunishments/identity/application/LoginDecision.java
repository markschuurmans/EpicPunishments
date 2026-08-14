package net.epicpunishments.identity.application;

import net.epicpunishments.punishment.domain.Punishment;

import java.util.Objects;
import java.util.Optional;

public record LoginDecision(Status status, Optional<Punishment> ban, boolean degraded) {
    public enum Status {
        ALLOWED,
        DENIED_BANNED,
        DENIED_TEMPORARY_FAILURE
    }

    public LoginDecision {
        Objects.requireNonNull(status, "status");
        ban = Objects.requireNonNull(ban, "ban");
        if ((status == Status.DENIED_BANNED) != ban.isPresent()) {
            throw new IllegalArgumentException("Only a banned decision may contain a ban");
        }
    }

    static LoginDecision allowed(boolean degraded) {
        return new LoginDecision(Status.ALLOWED, Optional.empty(), degraded);
    }

    static LoginDecision banned(Punishment ban, boolean degraded) {
        return new LoginDecision(Status.DENIED_BANNED, Optional.of(ban), degraded);
    }

    static LoginDecision temporaryFailure() {
        return new LoginDecision(Status.DENIED_TEMPORARY_FAILURE, Optional.empty(), true);
    }
}
