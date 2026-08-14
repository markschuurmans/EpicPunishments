package net.epicpunishments.punishment.domain;

import net.epicpunishments.identity.domain.PlayerAddress;

import java.util.Objects;

public record AddressPunishmentTarget(PlayerAddress address) implements PunishmentTarget {
    public AddressPunishmentTarget {
        Objects.requireNonNull(address, "address");
    }

    @Override
    public PunishmentTargetType type() {
        return PunishmentTargetType.IP_ADDRESS;
    }

    @Override
    public String toString() {
        return "AddressPunishmentTarget[address=" + address.redacted() + ']';
    }
}
