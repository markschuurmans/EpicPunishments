package net.epicpunishments.punishment.application;

import net.epicpunishments.common.domain.Actor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TargetAuthorizationServiceTest {
    @Test
    void ordinaryModeratorsCannotPunishExemptPlayersButOverrideCan() {
        var service = new TargetAuthorizationService(false);
        Actor moderator = Actor.player(UUID.randomUUID(), "Moderator");

        assertThat(service.mayPunish(moderator, false, true)).isFalse();
        assertThat(service.mayPunish(moderator, true, true)).isTrue();
        assertThat(service.mayPunish(moderator, false, false)).isTrue();
    }

    @Test
    void consoleBypassIsExplicitlyControlled() {
        assertThat(new TargetAuthorizationService(true).mayPunish(Actor.console(), false, true)).isTrue();
        assertThat(new TargetAuthorizationService(false).mayPunish(Actor.console(), false, true)).isFalse();
    }
}
