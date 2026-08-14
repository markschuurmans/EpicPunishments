package net.epicpunishments.common.config;

public sealed interface DatabaseConnectionConfiguration
        permits SqliteConnectionConfiguration, NetworkConnectionConfiguration {
}
