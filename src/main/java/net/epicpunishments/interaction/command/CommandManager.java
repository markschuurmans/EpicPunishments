package net.epicpunishments.interaction.command;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class CommandManager {
    private CommandManager() {
    }

    public static void register(JavaPlugin plugin, Logger logger, EpicCommand... commands) {
        var registeredCommands = List.of(commands);

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var command : registeredCommands) {
                if (CommandRootCollisions.contains(rootNames(event.registrar()), command.name())) {
                    logger.warning("Command collision detected for canonical /" + command.name()
                            + "; Paper will keep the EpicPunishments namespaced command available.");
                }
                event.registrar().register(
                        command.create().build(),
                        command.description(),
                        command.aliases()
                );
                for (ConvenienceCommand convenience : command.convenienceCommands()) {
                    registerConvenience(event.registrar(), convenience, logger);
                }
            }
        });
    }

    private static void registerConvenience(
            io.papermc.paper.command.brigadier.Commands registrar,
            ConvenienceCommand command,
            Logger logger
    ) {
        if (CommandRootCollisions.contains(rootNames(registrar), command.name())) {
            logger.warning("Convenience command /" + command.name()
                    + " was not registered because another command already owns that root; "
                    + "use /epicpunishments punish " + command.name() + " instead.");
            return;
        }
        Set<String> registered = registrar.register(command.builder().get().build(), command.description());
        boolean available = registered.stream().map(label -> label.toLowerCase(Locale.ROOT))
                .anyMatch(command.name()::equals);
        if (!available) {
            logger.warning("Convenience command /" + command.name()
                    + " could not be registered; use /epicpunishments punish " + command.name() + " instead.");
        }
    }

    private static Set<String> rootNames(io.papermc.paper.command.brigadier.Commands registrar) {
        return registrar.getDispatcher().getRoot().getChildren().stream()
                .map(node -> node.getName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
