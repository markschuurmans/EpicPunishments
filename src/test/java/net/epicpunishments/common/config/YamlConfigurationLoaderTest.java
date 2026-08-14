package net.epicpunishments.common.config;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConfigurationLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesDefaultsAndCreatesAValidatedImmutableSqliteSnapshot() throws Exception {
        var loader = loader(sqliteConfig("3s"), messages(), Map.of());

        ConfigurationSnapshot snapshot = loader.load();

        assertThat(Files.isRegularFile(temporaryDirectory.resolve("config.yml"))).isTrue();
        assertThat(Files.isRegularFile(temporaryDirectory.resolve("messages.yml"))).isTrue();
        assertThat(snapshot.database().type()).isEqualTo(DatabaseType.SQLITE);
        assertThat(snapshot.database().queryTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(snapshot.database().loginFailurePolicy()).isEqualTo(LoginFailurePolicy.DENY);
        assertThat(snapshot.database().connection())
                .isEqualTo(new SqliteConnectionConfiguration(temporaryDirectory.resolve("epicpunishments.db")));
    }

    @Test
    void interpolatesSelectedNetworkSettingsWithoutRequiringUnusedProviderSecrets() throws Exception {
        String mysql = networkConfig("mysql", "${DB_HOST}", "${DB_PASSWORD}");
        var loader = loader(mysql, messages(), Map.of(
                "DB_HOST", "database.internal",
                "DB_PASSWORD", "highly-secret"
        ));

        ConfigurationSnapshot snapshot = loader.load();

        var connection = (NetworkConnectionConfiguration) snapshot.database().connection();
        assertThat(connection.host()).isEqualTo("database.internal");
        assertThat(connection.password()).isEqualTo("highly-secret");
        assertThat(connection.toString())
                .contains("password=<redacted>")
                .doesNotContain("highly-secret");
    }

    @Test
    void rejectsInvalidBoundsMissingEnvironmentAndDuplicateYamlKeys() throws Exception {
        assertThatThrownBy(() -> loaderAt(
                temporaryDirectory.resolve("timeout"),
                sqliteConfig("31s"),
                messages(),
                Map.of()
        ).load())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("between 100ms and 30s");

        assertThatThrownBy(() -> loaderAt(
                temporaryDirectory.resolve("environment"),
                networkConfig("mysql", "localhost", "${MISSING_PASSWORD}"),
                messages(),
                Map.of()
        ).load())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("MISSING_PASSWORD")
                .hasMessageNotContaining("null");

        Path duplicateDirectory = temporaryDirectory.resolve("duplicate");
        Files.createDirectories(duplicateDirectory);
        Files.writeString(duplicateDirectory.resolve("config.yml"), "database:\n  type: sqlite\n  type: mysql\n");
        assertThatThrownBy(() -> loaderAt(
                duplicateDirectory,
                sqliteConfig("3s"),
                messages(),
                Map.of()
        ).load())
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Could not parse config.yml.");
    }

    @Test
    void loadsAndRendersConfiguredMiniMessageTemplates() throws Exception {
        ConfigurationSnapshot snapshot = loader(sqliteConfig("3s"), messages(), Map.of()).load();

        String plainText = PlainTextComponentSerializer.plainText().serialize(
                snapshot.messages().message("command.version", Map.of("version", "1.2.3"))
        );

        assertThat(plainText).isEqualTo("EpicPunishments 1.2.3");
    }

    private YamlConfigurationLoader loader(String config, String messages, Map<String, String> environment) {
        return loaderAt(temporaryDirectory, config, messages, environment);
    }

    private YamlConfigurationLoader loaderAt(
            Path directory,
            String config,
            String messages,
            Map<String, String> environment
    ) {
        Map<String, String> resources = Map.of(
                "config.yml", config,
                "messages.yml", messages
        );
        return new YamlConfigurationLoader(
                directory,
                name -> new ByteArrayInputStream(resources.get(name).getBytes(StandardCharsets.UTF_8)),
                environment
        );
    }

    private String sqliteConfig(String timeout) {
        return """
                database:
                  type: sqlite
                  query-timeout: %s
                  login-failure-policy: deny
                  sqlite:
                    file: epicpunishments.db
                  mysql:
                    password: ${UNUSED_MYSQL_PASSWORD}
                  postgres:
                    password: ${UNUSED_POSTGRES_PASSWORD}
                """.formatted(timeout);
    }

    private String networkConfig(String type, String host, String password) {
        return """
                database:
                  type: %s
                  query-timeout: 3s
                  login-failure-policy: allow-with-cache
                  mysql:
                    host: %s
                    port: 3306
                    database: epicpunishments
                    username: moderator
                    password: %s
                    pool-size: 8
                  postgres:
                    host: localhost
                    port: 5432
                    database: epicpunishments
                    username: moderator
                    password: ${UNUSED_POSTGRES_PASSWORD}
                    pool-size: 8
                """.formatted(type, host, password);
    }

    private String messages() {
        return """
                command:
                  usage: "<gold>Usage</gold>"
                  version: "<gold>EpicPunishments {version}</gold>"
                  reload-started: "<yellow>Reloading</yellow>"
                  reload-success: "<green>Reloaded</green>"
                  reload-failed: "<red>Failed</red>"
                  not-ready: "<red>Starting</red>"
                """;
    }
}
