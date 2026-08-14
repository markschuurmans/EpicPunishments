package net.epicpunishments.interaction.command;

import java.util.UUID;

public sealed interface CommandRecipient permits CommandRecipient.Player, CommandRecipient.Console, CommandRecipient.Unavailable {
    record Player(UUID playerId) implements CommandRecipient {
    }

    enum Console implements CommandRecipient {
        INSTANCE
    }

    enum Unavailable implements CommandRecipient {
        INSTANCE
    }
}
