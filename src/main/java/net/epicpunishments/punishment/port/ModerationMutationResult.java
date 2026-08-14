package net.epicpunishments.punishment.port;

import net.epicpunishments.punishment.domain.Punishment;

import java.util.Objects;
import java.util.Optional;

public record ModerationMutationResult(Status status, Optional<Punishment> punishment) {
    public enum Status {
        APPLIED,
        NOT_FOUND,
        ALREADY_REVOKED
    }

    public ModerationMutationResult {
        Objects.requireNonNull(status, "status");
        punishment = Objects.requireNonNull(punishment, "punishment");
        if ((status == Status.APPLIED) != punishment.isPresent()) {
            throw new IllegalArgumentException("Only an applied mutation carries its punishment");
        }
    }

    public static ModerationMutationResult applied(Punishment punishment) {
        return new ModerationMutationResult(Status.APPLIED, Optional.of(punishment));
    }

    public static ModerationMutationResult notFound() {
        return new ModerationMutationResult(Status.NOT_FOUND, Optional.empty());
    }

    public static ModerationMutationResult alreadyRevoked() {
        return new ModerationMutationResult(Status.ALREADY_REVOKED, Optional.empty());
    }
}
