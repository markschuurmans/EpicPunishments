package net.epicpunishments.identity.domain;

import java.time.Instant;
import java.util.Objects;

public record PlayerAddressHistory(
        PlayerAddress address,
        Instant firstSuccessfulJoinAt,
        Instant lastSuccessfulJoinAt,
        long joinCount
) {
    public PlayerAddressHistory {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(firstSuccessfulJoinAt, "firstSuccessfulJoinAt");
        Objects.requireNonNull(lastSuccessfulJoinAt, "lastSuccessfulJoinAt");
        if (lastSuccessfulJoinAt.isBefore(firstSuccessfulJoinAt)) {
            throw new IllegalArgumentException("lastSuccessfulJoinAt cannot be before firstSuccessfulJoinAt");
        }
        if (joinCount < 1) {
            throw new IllegalArgumentException("joinCount must be positive");
        }
    }
}
