package net.epicpunishments;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PluginMetadataTest {
    @Test
    void pluginDescriptorUsesTheLowercaseMainPackageAndProcessedVersion() throws IOException {
        try (InputStream descriptorStream = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertThat(descriptorStream).isNotNull();

            String descriptor = new String(descriptorStream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(descriptor)
                    .contains("version: '1.0-SNAPSHOT'")
                    .contains("main: net.epicpunishments.bootstrap.EpicPunishments")
                    .contains("api-version: '26.2'")
                    .contains("epicpunishments.command.reload:")
                    .contains("epicpunishments.command.status:")
                    .contains("epicpunishments.punishment.ban:")
                    .contains("epicpunishments.punishment.ban.ip:")
                    .contains("epicpunishments.punishment.unban:")
                    .contains("epicpunishments.punishment.mute:")
                    .contains("epicpunishments.punishment.mute.ip:")
                    .contains("epicpunishments.punishment.unmute:")
                    .contains("epicpunishments.punishment.warn:")
                    .contains("epicpunishments.punishment.warn.ip:")
                    .contains("epicpunishments.punishment.warnings:")
                    .contains("epicpunishments.punishment.history:")
                    .contains("epicpunishments.punishment.history.ip:")
                    .contains("epicpunishments.punishment.override-exempt:")
                    .contains("epicpunishments.exempt:")
                    .contains("epicpunishments.report.create:")
                    .contains("epicpunishments.report.own:")
                    .contains("epicpunishments.report.staff.list:")
                    .contains("epicpunishments.report.staff.view:")
                    .contains("epicpunishments.report.staff.claim:")
                    .contains("epicpunishments.report.staff.respond:")
                    .contains("epicpunishments.report.staff.resolve:")
                    .contains("epicpunishments.report.staff.dismiss:")
                    .contains("epicpunishments.notify.report:")
                    .doesNotContain("${version}")
                    .doesNotContain("net.epicPunishments");
        }
    }

    @Test
    void renamedPluginAndCommandClassFilesArePresent() {
        ClassLoader classLoader = getClass().getClassLoader();

        assertThat(classLoader.getResource("net/epicpunishments/bootstrap/EpicPunishments.class")).isNotNull();
        assertThat(classLoader.getResource("net/epicpunishments/bootstrap/PluginContainer.class")).isNotNull();
        assertThat(classLoader.getResource("net/epicpunishments/interaction/command/EpicPunishmentsCommand.class"))
                .isNotNull();
        assertThat(classLoader.getResource("net/epicpunishments/interaction/command/subcommand/PunishCommand.class"))
                .isNotNull();
        assertThat(classLoader.getResource("net/epicpunishments/interaction/command/subcommand/ReportCommand.class"))
                .isNotNull();
        assertThat(classLoader.getResource("net/epicpunishments/interaction/command/subcommand/ReportsCommand.class"))
                .isNotNull();
        assertThat(classLoader.getResource("config.yml")).isNotNull();
        assertThat(classLoader.getResource("messages.yml")).isNotNull();
    }
}
