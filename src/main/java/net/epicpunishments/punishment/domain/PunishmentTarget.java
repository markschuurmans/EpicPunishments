package net.epicpunishments.punishment.domain;

public sealed interface PunishmentTarget permits PlayerPunishmentTarget, AddressPunishmentTarget {
    PunishmentTargetType type();
}
