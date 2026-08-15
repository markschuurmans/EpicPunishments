package net.epicpunishments.interaction.command;

import java.util.Collection;
import java.util.Objects;

public final class CommandRootCollisions {
    private CommandRootCollisions() {
    }

    public static boolean contains(Collection<String> existingRoots, String requestedRoot) {
        Objects.requireNonNull(existingRoots, "existingRoots");
        Objects.requireNonNull(requestedRoot, "requestedRoot");
        return existingRoots.stream().anyMatch(root -> root.equalsIgnoreCase(requestedRoot));
    }
}
