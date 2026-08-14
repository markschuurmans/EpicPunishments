package net.epicpunishments.common.config;

@FunctionalInterface
public interface ConfigurationLoader {
    ConfigurationSnapshot load() throws ConfigurationException;
}
