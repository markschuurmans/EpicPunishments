package net.epicPunishments.command.subcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.epicPunishments.EpicPunishments;
import net.epicPunishments.command.EpicCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class VersionCommand implements EpicCommand {
    private final EpicPunishments plugin;

    public VersionCommand(EpicPunishments plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "version";
    }

    @Override
    public String permission() {
        return "epicpunishments.command.version";
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage(Component.text(
                "EpicPunishments " + plugin.getPluginMeta().getVersion(),
                NamedTextColor.GOLD
        ));
        return Command.SINGLE_SUCCESS;
    }
}
