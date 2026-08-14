package net.epicpunishments.common.config;

import net.epicpunishments.common.message.MessageCatalog;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YamlConfigurationLoader implements ConfigurationLoader {
    private static final String CONFIG_FILE = "config.yml";
    private static final String MESSAGES_FILE = "messages.yml";
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)(ms|s|m)");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Duration MINIMUM_QUERY_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_QUERY_TIMEOUT = Duration.ofSeconds(30);

    private final Path dataDirectory;
    private final ResourceProvider resources;
    private final Map<String, String> environment;

    public YamlConfigurationLoader(
            Path dataDirectory,
            ResourceProvider resources,
            Map<String, String> environment
    ) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath()
                .normalize();
        this.resources = Objects.requireNonNull(resources, "resources");
        this.environment = Map.copyOf(environment);
    }

    @Override
    public ConfigurationSnapshot load() throws ConfigurationException {
        ensureDefaultFile(CONFIG_FILE);
        ensureDefaultFile(MESSAGES_FILE);

        Map<String, Object> configuration = loadYaml(CONFIG_FILE);
        Map<String, Object> messages = loadYaml(MESSAGES_FILE);

        return new ConfigurationSnapshot(
                readDatabaseConfiguration(configuration),
                readMessageCatalog(messages)
        );
    }

    private void ensureDefaultFile(String name) throws ConfigurationException {
        try {
            Files.createDirectories(dataDirectory);
            Path target = dataDirectory.resolve(name);
            if (Files.exists(target)) {
                if (!Files.isRegularFile(target)) {
                    throw new ConfigurationException(name + " must be a regular file.");
                }
                return;
            }

            try (InputStream source = resources.open(name)) {
                if (source == null) {
                    throw new ConfigurationException("Bundled default " + name + " is missing.");
                }
                try {
                    Files.copy(source, target);
                } catch (FileAlreadyExistsException ignored) {
                    // Another load won the create race; its complete file is authoritative.
                }
            }
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ConfigurationException("Could not prepare " + name + '.', exception);
        }
    }

    private Map<String, Object> loadYaml(String name) throws ConfigurationException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setNestingDepthLimit(30);
        options.setCodePointLimit(1_000_000);

        try (Reader reader = Files.newBufferedReader(dataDirectory.resolve(name), StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new ConfigurationException(name + " must contain a YAML mapping at its root.");
            }
            return stringKeyedMap(root, name);
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ConfigurationException("Could not parse " + name + '.', exception);
        }
    }

    private DatabaseConfiguration readDatabaseConfiguration(Map<String, Object> root)
            throws ConfigurationException {
        DatabaseType type = DatabaseType.parse(requiredString(root, "database.type", true));
        Duration timeout = parseQueryTimeout(requiredString(root, "database.query-timeout", true));
        LoginFailurePolicy failurePolicy = LoginFailurePolicy.parse(
                requiredString(root, "database.login-failure-policy", true)
        );

        DatabaseConnectionConfiguration connection = switch (type) {
            case SQLITE -> readSqliteConfiguration(root);
            case MYSQL -> readNetworkConfiguration(root, "database.mysql");
            case POSTGRES -> readNetworkConfiguration(root, "database.postgres");
        };
        return new DatabaseConfiguration(type, timeout, failurePolicy, connection);
    }

    private SqliteConnectionConfiguration readSqliteConfiguration(Map<String, Object> root)
            throws ConfigurationException {
        String configuredFile = requiredString(root, "database.sqlite.file", true);
        Path file;
        try {
            file = Path.of(configuredFile);
        } catch (RuntimeException exception) {
            throw new ConfigurationException("database.sqlite.file is not a valid path.", exception);
        }
        if (!file.isAbsolute()) {
            file = dataDirectory.resolve(file);
        }
        return new SqliteConnectionConfiguration(file);
    }

    private NetworkConnectionConfiguration readNetworkConfiguration(Map<String, Object> root, String path)
            throws ConfigurationException {
        return new NetworkConnectionConfiguration(
                requiredString(root, path + ".host", true),
                requiredInteger(root, path + ".port", 1, 65_535),
                requiredString(root, path + ".database", true),
                requiredString(root, path + ".username", true),
                requiredString(root, path + ".password", false),
                requiredInteger(root, path + ".pool-size", 1, 64)
        );
    }

    private MessageCatalog readMessageCatalog(Map<String, Object> root) throws ConfigurationException {
        var flattened = new LinkedHashMap<String, String>();
        flattenMessages("", root, flattened);
        try {
            return MessageCatalog.parse(flattened);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("messages.yml contains invalid or missing message templates.", exception);
        }
    }

    private void flattenMessages(String prefix, Map<String, Object> values, Map<String, String> flattened)
            throws ConfigurationException {
        for (var entry : values.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + '.' + entry.getKey();
            if (entry.getValue() instanceof String value) {
                flattened.put(path, value);
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                flattenMessages(path, stringKeyedMap(nested, MESSAGES_FILE), flattened);
            } else {
                throw new ConfigurationException("Message " + path + " must be a string.");
            }
        }
    }

    private String requiredString(Map<String, Object> root, String path, boolean requireNonBlank)
            throws ConfigurationException {
        Object value = valueAt(root, path);
        if (!(value instanceof String stringValue)) {
            throw new ConfigurationException(path + " must be a string.");
        }
        String interpolated = interpolateEnvironment(stringValue, path);
        if (requireNonBlank && interpolated.isBlank()) {
            throw new ConfigurationException(path + " must not be blank.");
        }
        return interpolated;
    }

    private int requiredInteger(Map<String, Object> root, String path, int minimum, int maximum)
            throws ConfigurationException {
        Object value = valueAt(root, path);
        if (!(value instanceof Number number)) {
            throw new ConfigurationException(path + " must be an integer.");
        }
        long longValue = number.longValue();
        if (number.doubleValue() != longValue || longValue < minimum || longValue > maximum) {
            throw new ConfigurationException(path + " must be between " + minimum + " and " + maximum + '.');
        }
        return (int) longValue;
    }

    private Object valueAt(Map<String, Object> root, String path) throws ConfigurationException {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap) || !currentMap.containsKey(segment)) {
                throw new ConfigurationException("Missing required setting " + path + '.');
            }
            current = currentMap.get(segment);
        }
        return current;
    }

    private Duration parseQueryTimeout(String value) throws ConfigurationException {
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw new ConfigurationException("database.query-timeout must use ms, s, or m (for example, 3s).");
        }

        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration parsed = switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> throw new IllegalStateException("Unexpected duration unit.");
            };
            if (parsed.compareTo(MINIMUM_QUERY_TIMEOUT) < 0 || parsed.compareTo(MAXIMUM_QUERY_TIMEOUT) > 0) {
                throw new ConfigurationException("database.query-timeout must be between 100ms and 30s.");
            }
            return parsed;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ConfigurationException("database.query-timeout is outside the supported range.", exception);
        }
    }

    private String interpolateEnvironment(String value, String path) throws ConfigurationException {
        Matcher matcher = ENVIRONMENT_VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String variable = matcher.group(1);
            String replacement = environment.get(variable);
            if (replacement == null) {
                throw new ConfigurationException(
                        "Environment variable " + variable + " required by " + path + " is not set."
                );
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        if (result.indexOf("${") >= 0) {
            throw new ConfigurationException(path + " contains an invalid environment variable placeholder.");
        }
        return result.toString();
    }

    private Map<String, Object> stringKeyedMap(Map<?, ?> source, String name) throws ConfigurationException {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ConfigurationException(name + " contains a non-string YAML key.");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }
}
