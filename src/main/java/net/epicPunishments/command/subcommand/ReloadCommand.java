package net.epicPunishments.command.subcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.epicPunishments.EpicPunishments;
import net.epicPunishments.command.EpicCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class ReloadCommand implements EpicCommand {
    private final EpicPunishments plugin;

    public ReloadCommand(EpicPunishments plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "epicpunishments.command.reload";
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        plugin.reloadConfig();
        context.getSource().getSender().sendMessage(Component.text(
                "EpicPunishments reloaded.",
                NamedTextColor.GREEN
        ));
        return Command.SINGLE_SUCCESS;
    }
}
