package net.epicpunishments.interaction.command.subcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.interaction.command.EpicCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Map;
import java.util.Objects;

public final class VersionCommand implements EpicCommand {
    private final ConfigurationService configurations;
    private final String version;

    public VersionCommand(ConfigurationService configurations, String version) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.version = Objects.requireNonNull(version, "version");
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
        Component message = configurations.current()
                .map(snapshot -> snapshot.messages().message("command.version", Map.of("version", version)))
                .orElseGet(() -> Component.text("EpicPunishments " + version, NamedTextColor.GOLD));
        context.getSource().getSender().sendMessage(message);
        return Command.SINGLE_SUCCESS;
    }
}
