package net.epicpunishments.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.epicpunishments.EpicPunishments;
import net.epicpunishments.command.subcommand.ReloadCommand;
import net.epicpunishments.command.subcommand.VersionCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collection;
import java.util.List;

public final class EpicPunishmentsCommand implements EpicCommand {
    private final EpicPunishments plugin;

    public EpicPunishmentsCommand(EpicPunishments plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "epicpunishments";
    }

    @Override
    public String permission() {
        return "epicpunishments.command";
    }

    @Override
    public String description() {
        return "Manage EpicPunishments";
    }

    @Override
    public Collection<EpicCommand> subcommands() {
        return List.of(
                new VersionCommand(plugin),
                new ReloadCommand(plugin)
        );
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage(Component.text(
                "Use /epicpunishments <subcommand>.",
                NamedTextColor.GOLD
        ));
        return Command.SINGLE_SUCCESS;
    }
}
