package net.epicpunishments.common.domain;

public record PageRequest(int page, int size) {
    public static final int MAXIMUM_SIZE = 100;

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative");
        }
        if (size < 1 || size > MAXIMUM_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAXIMUM_SIZE);
        }
    }

    public long offset() {
        return Math.multiplyExact((long) page, size);
    }
}
