package net.epicpunishments.punishment.application;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PlayerTargetParser {
    private static final String PREFIX = "player:";
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public PlayerTargetReference parse(String input) {
        Objects.requireNonNull(input, "input");
        if (!input.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Player targets must start with player:");
        }
        String value = input.substring(PREFIX.length());
        try {
            return new PlayerTargetReference.ById(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            if (!PLAYER_NAME.matcher(value).matches()) {
                throw new IllegalArgumentException("Player targets require a valid name or UUID");
            }
            return new PlayerTargetReference.ByName(value);
        }
    }
}
