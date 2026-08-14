package net.epicpunishments.common.config;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BundledConfigurationTest {
    @TempDir
    Path dataDirectory;

    @Test
    void bundledDefaultsLoadWithoutDatabaseEnvironmentVariables() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        var loader = new YamlConfigurationLoader(
                dataDirectory,
                classLoader::getResourceAsStream,
                Map.of()
        );

        ConfigurationSnapshot snapshot = loader.load();

        assertThat(snapshot.database().type()).isEqualTo(DatabaseType.SQLITE);
        assertThat(PlainTextComponentSerializer.plainText().serialize(
                snapshot.messages().message("command.usage")
        )).isEqualTo("Use /epicpunishments <subcommand>.");
    }
}
