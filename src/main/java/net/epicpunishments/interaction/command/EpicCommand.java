package net.epicpunishments.interaction.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import java.util.Collection;
import java.util.List;

public interface EpicCommand {
    String name();

    String permission();

    int execute(CommandContext<CommandSourceStack> context);

    default Collection<EpicCommand> subcommands() {
        return List.of();
    }

    default String description() {
        return "";
    }

    default Collection<String> aliases() {
        return List.of();
    }

    default Collection<ConvenienceCommand> convenienceCommands() {
        return List.of();
    }

    default LiteralArgumentBuilder<CommandSourceStack> create() {
        var command = Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(permission()))
                .executes(this::execute);

        for (var subcommand : subcommands()) {
            command.then(subcommand.create());
        }

        return command;
    }
}
