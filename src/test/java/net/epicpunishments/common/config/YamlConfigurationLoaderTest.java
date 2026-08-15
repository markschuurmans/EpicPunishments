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
        assertThat(snapshot.punishments()).isEqualTo(new PunishmentConfiguration(
                Duration.ofDays(365), 512, 10, true,
                java.util.Set.of(PunishmentCommandAlias.BAN, PunishmentCommandAlias.MUTE, PunishmentCommandAlias.WARN)
        ));
        assertThat(snapshot.reports()).isEqualTo(new ReportConfiguration(
                Duration.ofMinutes(5), 512, 1_024, 10
        ));
        assertThat(snapshot.database().connection())
                .isEqualTo(new SqliteConnectionConfiguration(temporaryDirectory.resolve("epicpunishments.db")));
    }

    @Test
    void validatesConfigurablePunishmentConvenienceAliases() throws Exception {
        String configured = sqliteConfig("3s") + """

                punishments:
                  command-aliases: [ban, warn]
                """;
        ConfigurationSnapshot snapshot = loaderAt(
                temporaryDirectory.resolve("aliases"), configured, messages(), Map.of()
        ).load();

        assertThat(snapshot.punishments().commandAliases()).containsExactlyInAnyOrder(
                PunishmentCommandAlias.BAN, PunishmentCommandAlias.WARN
        );

        String invalid = sqliteConfig("3s") + """

                punishments:
                  command-aliases: [kick]
                """;
        assertThatThrownBy(() -> loaderAt(
                temporaryDirectory.resolve("invalid-alias"), invalid, messages(), Map.of()
        ).load()).isInstanceOf(ConfigurationException.class).hasMessageContaining("ban, mute, or warn");
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

        assertThatThrownBy(() -> loaderAt(
                temporaryDirectory.resolve("punishment-duration"),
                sqliteConfig("3s") + "\npunishments:\n  maximum-duration: 3651d\n",
                messages(),
                Map.of()
        ).load())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("must not exceed 3650d");

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
                  status: "<gold>{provider} {schema} {health} {pending-tasks}</gold>"
                  reload-started: "<yellow>Reloading</yellow>"
                  reload-success: "<green>Reloaded</green>"
                  reload-failed: "<red>Failed</red>"
                  not-ready: "<red>Starting</red>"
                login:
                  banned: "<red>Banned: {reason}</red>"
                  temporary-error: "<red>Unavailable</red>"
                  degraded-warning: "<red>Degraded for {player-id}</red>"
                warning:
                  received: "<gold>Warning: {reason}</gold>"
                punishment:
                  usage: "<gold>Usage</gold>"
                  invalid-input: "<red>{error}</red>"
                  invalid-target: "<red>Invalid target</red>"
                  target-not-found: "<red>Not found</red>"
                  target-ambiguous: "<red>Ambiguous</red>"
                  target-exempt: "<red>Exempt</red>"
                  applied: "<green>{type} {player}</green>"
                  revoked: "<green>{count} {type} {player}</green>"
                  no-active: "<yellow>None</yellow>"
                  history-header: "<gold>{player} {page} {pages}</gold>"
                  history-entry: "<gray>{id} {type} {created} {status} {reason}</gray>"
                  history-empty: "<yellow>Empty</yellow>"
                  command-failed: "<red>Failed</red>"
                  unsupported-sender: "<red>Unsupported</red>"
                  muted: "<red>{reason}</red>"
                  mute-blocked: "<red>{reason}</red>"
                  staff-notification: "<gold>{actor} {action} {type} {id} {target} {reason}</gold>"
                report:
                  usage: "<gold>Usage</gold>"
                  invalid-input: "<red>{error}</red>"
                  player-only: "<red>Player only</red>"
                  unsupported-sender: "<red>Unsupported</red>"
                  created: "<green>{id} {reported}</green>"
                  updated: "<green>{id} {status}</green>"
                  details: "<gold>{id} {reporter} {reported} {status} {assignee} {reason}</gold>"
                  not-found: "<red>Not found</red>"
                  not-owner: "<red>Not owner</red>"
                  target-not-found: "<red>Target missing</red>"
                  target-ambiguous: "<red>Ambiguous</red>"
                  self: "<red>Self</red>"
                  cooldown: "<yellow>Cooldown</yellow>"
                  duplicate: "<yellow>Duplicate</yellow>"
                  version-conflict: "<yellow>Conflict</yellow>"
                  invalid-state: "<yellow>Invalid state</yellow>"
                  list-header: "<gold>{page} {pages}</gold>"
                  list-entry: "<gray>{id} {reported} {status} {created}</gray>"
                  list-empty: "<yellow>Empty</yellow>"
                  response-entry: "<gray>{created} {actor} {message}</gray>"
                  command-failed: "<red>Failed</red>"
                  staff-notification: "<gold>{id} {reporter} {reported}</gold>"
                  notification: "<gold>{id}</gold>"
                reports:
                  usage: "<gold>Usage</gold>"
                """;
    }
}
