package net.epicpunishments.punishment.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PunishmentInputParsingTest {
    @Test
    void acceptsOnlyExplicitPlayerNamesAndUuids() {
        var parser = new PlayerTargetParser();
        UUID playerId = UUID.randomUUID();

        assertThat(parser.parse("player:Some_Player"))
                .isEqualTo(new PlayerTargetReference.ByName("Some_Player"));
        assertThat(parser.parse("player:" + playerId))
                .isEqualTo(new PlayerTargetReference.ById(playerId));
        assertThatThrownBy(() -> parser.parse("Some_Player"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("ip:192.0.2.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesPermanentAndBoundedTemporaryDurations() {
        var parser = new PunishmentDurationParser(Duration.ofDays(30));

        assertThat(parser.parse("perm")).isEmpty();
        assertThat(parser.parse("30m")).contains(Duration.ofMinutes(30));
        assertThat(parser.parse("12h")).contains(Duration.ofHours(12));
        assertThat(parser.parse("30d")).contains(Duration.ofDays(30));
        assertThatThrownBy(() -> parser.parse("31d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
        assertThatThrownBy(() -> parser.parse("1s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void separatesAnOptionalDurationFromTheRequiredReason() {
        var durations = new PunishmentDurationParser(Duration.ofDays(30));

        assertThat(CreatePunishmentInput.parse("7d repeated griefing", durations))
                .isEqualTo(new CreatePunishmentInput(Optional.of(Duration.ofDays(7)), "repeated griefing"));
        assertThat(CreatePunishmentInput.parse("perm repeated griefing", durations))
                .isEqualTo(new CreatePunishmentInput(Optional.empty(), "repeated griefing"));
        assertThat(CreatePunishmentInput.parse("repeated griefing", durations))
                .isEqualTo(new CreatePunishmentInput(Optional.empty(), "repeated griefing"));
        assertThatThrownBy(() -> CreatePunishmentInput.parse("7d", durations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }
}
