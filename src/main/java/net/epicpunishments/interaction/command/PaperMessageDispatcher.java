package net.epicpunishments.interaction.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.concurrent.Executor;

public final class PaperMessageDispatcher {
    private final Server server;
    private final Executor mainThreadExecutor;

    public PaperMessageDispatcher(Server server, Executor mainThreadExecutor) {
        this.server = Objects.requireNonNull(server, "server");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    public CommandRecipient capture(CommandSender sender) {
        if (sender instanceof Player player) {
            return new CommandRecipient.Player(player.getUniqueId());
        }
        if (sender instanceof ConsoleCommandSender) {
            return CommandRecipient.Console.INSTANCE;
        }
        return CommandRecipient.Unavailable.INSTANCE;
    }

    public void send(CommandRecipient recipient, Component message) {
        if (recipient == CommandRecipient.Unavailable.INSTANCE) {
            return;
        }
        mainThreadExecutor.execute(() -> {
            switch (recipient) {
                case CommandRecipient.Player playerRecipient -> {
                    Player player = server.getPlayer(playerRecipient.playerId());
                    if (player != null) {
                        player.sendMessage(message);
                    }
                }
                case CommandRecipient.Console ignored -> server.getConsoleSender().sendMessage(message);
                case CommandRecipient.Unavailable ignored -> {
                    // This case is handled before scheduling.
                }
            }
        });
    }
}
