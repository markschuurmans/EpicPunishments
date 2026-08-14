package net.epicpunishments.common.config;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
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

    @Test
    void fillsNewMessageKeysFromBundledDefaultsWhenUpgradingAnExistingFile() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        var loader = new YamlConfigurationLoader(
                dataDirectory,
                classLoader::getResourceAsStream,
                Map.of()
        );
        loader.load();
        Files.writeString(dataDirectory.resolve("messages.yml"), """
                command:
                  usage: '<gold>Custom usage</gold>'
                  version: '<gold>Custom {version}</gold>'
                  reload-started: '<yellow>Custom reload</yellow>'
                  reload-success: '<green>Custom success</green>'
                  reload-failed: '<red>Custom failure</red>'
                  not-ready: '<red>Custom starting</red>'
                """);

        ConfigurationSnapshot upgraded = loader.load();

        var plainText = PlainTextComponentSerializer.plainText();
        assertThat(plainText.serialize(upgraded.messages().message("command.usage")))
                .isEqualTo("Custom usage");
        assertThat(plainText.serialize(upgraded.messages().message("login.temporary-error")))
                .contains("temporarily unavailable");
    }
}
