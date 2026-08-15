package net.epicpunishments.punishment.application;

import net.epicpunishments.identity.domain.PlayerAddress;
import net.epicpunishments.punishment.domain.Punishment;

import java.util.List;
import java.util.Objects;

public record AddressModerationResult(Status status, PlayerAddress address, List<Punishment> punishments) {
    public AddressModerationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(address, "address");
        punishments = List.copyOf(punishments);
    }

    public enum Status { APPLIED, NO_ACTIVE_PUNISHMENT }
}
