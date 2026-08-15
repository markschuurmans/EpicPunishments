package net.epicpunishments.interaction.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PunishmentCommandArgumentsTest {
    @Test
    void acceptsTheDocumentedColonSeparatedTargetWithoutTrailingArguments() {
        assertThat(PunishmentCommandArguments.history("player:MockingDude"))
                .isEqualTo(new PunishmentCommandArguments.HistoryArguments("player:MockingDude", 1));
        assertThat(PunishmentCommandArguments.history(" player:MockingDude "))
                .isEqualTo(new PunishmentCommandArguments.HistoryArguments("player:MockingDude", 1));
    }

    @Test
    void separatesTheOptionalHistoryPage() {
        assertThat(PunishmentCommandArguments.history("player:MockingDude 1"))
                .isEqualTo(new PunishmentCommandArguments.HistoryArguments("player:MockingDude", 1));
        assertThat(PunishmentCommandArguments.history("player:MockingDude\t12"))
                .isEqualTo(new PunishmentCommandArguments.HistoryArguments("player:MockingDude", 12));
    }

    @Test
    void separatesTargetsFromDurationsReasonsAndRevocationNotes() {
        assertThat(PunishmentCommandArguments.withRequiredRemainder(
                "player:MockingDude 7d repeated griefing"
        )).isEqualTo(new PunishmentCommandArguments.TargetAndRemainder(
                "player:MockingDude",
                "7d repeated griefing"
        ));
        assertThat(PunishmentCommandArguments.withOptionalRemainder(
                "player:MockingDude appeal accepted"
        )).isEqualTo(new PunishmentCommandArguments.TargetAndRemainder(
                "player:MockingDude",
                "appeal accepted"
        ));
    }

    @Test
    void rejectsMalformedHistoryPagesAndMissingRequiredDetails() {
        assertThatThrownBy(() -> PunishmentCommandArguments.history("player:MockingDude page"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");
        assertThatThrownBy(() -> PunishmentCommandArguments.history("player:MockingDude 1 extra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("optional page");
        assertThatThrownBy(() -> PunishmentCommandArguments.withRequiredRemainder("player:MockingDude"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("details");
    }
}
