package net.epicpunishments.interaction.listener;

import java.util.Objects;

public final class PunishmentNotificationPrivacy {
    private PunishmentNotificationPrivacy() {
    }

    public static <T> T select(T fullValue, T redactedValue, boolean mayViewFullAddress) {
        Objects.requireNonNull(fullValue, "fullValue");
        Objects.requireNonNull(redactedValue, "redactedValue");
        return mayViewFullAddress ? fullValue : redactedValue;
    }
}
