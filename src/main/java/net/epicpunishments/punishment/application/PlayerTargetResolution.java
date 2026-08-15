package net.epicpunishments.punishment.application;

import net.epicpunishments.identity.domain.PlayerIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PlayerTargetResolution(Status status, Optional<PlayerIdentity> identity, List<PlayerIdentity> matches) {
    public enum Status {
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS,
        INVALID
    }

    public PlayerTargetResolution {
        Objects.requireNonNull(status, "status");
        identity = Objects.requireNonNull(identity, "identity");
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        if ((status == Status.RESOLVED) != identity.isPresent()) {
            throw new IllegalArgumentException("Only a resolved target carries an identity");
        }
    }

    static PlayerTargetResolution resolved(PlayerIdentity identity) {
        return new PlayerTargetResolution(Status.RESOLVED, Optional.of(identity), List.of(identity));
    }

    static PlayerTargetResolution unresolved(Status status, List<PlayerIdentity> matches) {
        return new PlayerTargetResolution(status, Optional.empty(), matches);
    }
}
