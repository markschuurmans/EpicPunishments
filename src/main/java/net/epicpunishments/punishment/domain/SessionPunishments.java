package net.epicpunishments.punishment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SessionPunishments(
        List<Punishment> bans,
        List<Punishment> mutes,
        List<Punishment> undeliveredWarnings
) {
    private static final SessionPunishments EMPTY = new SessionPunishments(List.of(), List.of(), List.of());

    public SessionPunishments {
        bans = copyOfType(bans, PunishmentType.BAN, "bans");
        mutes = copyOfType(mutes, PunishmentType.MUTE, "mutes");
        undeliveredWarnings = copyOfType(undeliveredWarnings, PunishmentType.WARNING, "undeliveredWarnings");
    }

    public static SessionPunishments empty() {
        return EMPTY;
    }

    public boolean isBannedAt(Instant instant) {
        return bans.stream().anyMatch(punishment -> punishment.isActiveAt(instant));
    }

    public boolean isMutedAt(Instant instant) {
        return mutes.stream().anyMatch(punishment -> punishment.isActiveAt(instant));
    }

    private static List<Punishment> copyOfType(
            List<Punishment> punishments,
            PunishmentType expectedType,
            String name
    ) {
        List<Punishment> copy = List.copyOf(Objects.requireNonNull(punishments, name));
        if (copy.stream().anyMatch(punishment -> punishment.type() != expectedType)) {
            throw new IllegalArgumentException(name + " may contain only " + expectedType + " punishments");
        }
        return copy;
    }
}
