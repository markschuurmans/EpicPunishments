package net.epicpunishments.common.config;

import java.util.Locale;

public enum PunishmentCommandAlias {
    BAN,
    MUTE,
    WARN;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PunishmentCommandAlias parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "ban" -> BAN;
            case "mute" -> MUTE;
            case "warn" -> WARN;
            default -> throw new IllegalArgumentException(
                    "Punishment command aliases must be ban, mute, or warn"
            );
        };
    }
}
