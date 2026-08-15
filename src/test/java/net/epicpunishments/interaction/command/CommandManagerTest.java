package net.epicpunishments.interaction.command;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommandManagerTest {
    @Test
    void detectsExistingRootsWithoutClaimingTheCanonicalFallback() {
        assertThat(CommandRootCollisions.contains(Set.of("ban", "minecraft:ban"), "BAN")).isTrue();
        assertThat(CommandRootCollisions.contains(Set.of("ban"), "epicpunishments")).isFalse();
    }
}
