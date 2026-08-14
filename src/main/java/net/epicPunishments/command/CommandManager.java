package net.epicPunishments.command;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class CommandManager {
    private CommandManager() {
    }

    public static void register(JavaPlugin plugin, EpicCommand... commands) {
        var registeredCommands = List.of(commands);

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (var command : registeredCommands) {
                event.registrar().register(
                        command.create().build(),
                        command.description(),
                        command.aliases()
                );
            }
        });
    }
}
