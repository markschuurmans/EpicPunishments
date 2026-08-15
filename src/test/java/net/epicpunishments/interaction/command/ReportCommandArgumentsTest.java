package net.epicpunishments.interaction.command;

import net.epicpunishments.report.domain.ReportStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportCommandArgumentsTest {
    @Test
    void parsesCreateAndAdministrativeMessagesWithoutLosingSpaces() {
        UUID reportId = UUID.randomUUID();

        assertThat(ReportCommandArguments.targetAndMessage("Reported repeated chat abuse"))
                .isEqualTo(new ReportCommandArguments.TargetAndMessage("Reported", "repeated chat abuse"));
        assertThat(ReportCommandArguments.idAndRequiredMessage(reportId + " resolved after review"))
                .isEqualTo(new ReportCommandArguments.IdAndMessage(reportId, "resolved after review"));
        assertThat(ReportCommandArguments.idAndOptionalMessage(reportId.toString()).message()).isEmpty();
    }

    @Test
    void parsesStaffFiltersAndOneBasedPages() {
        assertThat(ReportCommandArguments.staffList("in-review 3"))
                .isEqualTo(new ReportCommandArguments.StaffList(Optional.of(ReportStatus.IN_REVIEW), 3));
        assertThat(ReportCommandArguments.staffList("2"))
                .isEqualTo(new ReportCommandArguments.StaffList(Optional.empty(), 2));
        assertThatThrownBy(() -> ReportCommandArguments.staffList("unknown"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Status");
        assertThatThrownBy(() -> ReportCommandArguments.page("0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }
}
