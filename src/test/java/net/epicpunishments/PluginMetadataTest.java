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
                    .contains("main: net.epicpunishments.EpicPunishments")
                    .contains("api-version: '26.2'")
                    .doesNotContain("${version}")
                    .doesNotContain("net.epicPunishments");
        }
    }

    @Test
    void renamedPluginAndCommandClassFilesArePresent() {
        ClassLoader classLoader = getClass().getClassLoader();

        assertThat(classLoader.getResource("net/epicpunishments/EpicPunishments.class")).isNotNull();
        assertThat(classLoader.getResource("net/epicpunishments/command/EpicPunishmentsCommand.class")).isNotNull();
        assertThat(classLoader.getResource("net/epicpunishments/command/subcommand/VersionCommand.class")).isNotNull();
        assertThat(classLoader.getResource("net/epicpunishments/command/subcommand/ReloadCommand.class")).isNotNull();
    }
}
