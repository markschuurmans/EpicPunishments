package net.epicpunishments.common.config;

import java.io.InputStream;

@FunctionalInterface
public interface ResourceProvider {
    InputStream open(String name);
}
