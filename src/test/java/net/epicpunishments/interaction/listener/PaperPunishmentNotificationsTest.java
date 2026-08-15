package net.epicpunishments.interaction.listener;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaperPunishmentNotificationsTest {
    @Test
    void exposesFullIpNotificationOnlyToDedicatedPermissionHolders() {
        Component full = Component.text("192.0.2.42");
        Component redacted = Component.text("192.0.2.x");

        assertThat(PunishmentNotificationPrivacy.select(full, redacted, false)).isSameAs(redacted);
        assertThat(PunishmentNotificationPrivacy.select(full, redacted, true)).isSameAs(full);
    }
}
