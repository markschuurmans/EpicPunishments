package net.epicpunishments.common.config;

import java.nio.file.Path;
import java.util.Objects;

public record SqliteConnectionConfiguration(Path file) implements DatabaseConnectionConfiguration {
    public SqliteConnectionConfiguration {
        Objects.requireNonNull(file, "file");
        file = file.toAbsolutePath().normalize();
    }
}
