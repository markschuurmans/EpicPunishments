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
        assertThat(classLoader.getResource("config.yml")).isNotNull();
        assertThat(classLoader.getResource("messages.yml")).isNotNull();
    }
}
