package net.epicpunishments.common.domain;

import java.util.List;
import java.util.Objects;

public record Page<T>(List<T> items, int page, int size, long totalItems) {
    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (page < 0 || size < 1 || totalItems < 0 || items.size() > size) {
            throw new IllegalArgumentException("Invalid page metadata");
        }
    }
}
