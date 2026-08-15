package net.epicpunishments.interaction.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Objects;
import java.util.function.Supplier;

public record ConvenienceCommand(
        String name,
        String description,
        Supplier<LiteralArgumentBuilder<CommandSourceStack>> builder
) {
    public ConvenienceCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(builder, "builder");
        if (!name.matches("[a-z][a-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid convenience command name");
        }
    }
}
