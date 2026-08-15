package net.epicpunishments.interaction.command.subcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.common.observability.PluginStatusService;
import net.epicpunishments.interaction.command.EpicCommand;
import net.epicpunishments.interaction.command.PaperMessageDispatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class StatusCommand implements EpicCommand {
    private final ConfigurationService configurations;
    private final PluginStatusService statuses;
    private final PaperMessageDispatcher messageDispatcher;

    public StatusCommand(
            ConfigurationService configurations,
            PluginStatusService statuses,
            PaperMessageDispatcher messageDispatcher
    ) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.statuses = Objects.requireNonNull(statuses, "statuses");
        this.messageDispatcher = Objects.requireNonNull(messageDispatcher, "messageDispatcher");
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String permission() {
        return "epicpunishments.command.status";
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        var current = configurations.current();
        if (current.isEmpty()) {
            context.getSource().getSender().sendMessage(Component.text(
                    "EpicPunishments is still starting. Try again shortly.",
                    NamedTextColor.RED
            ));
            return 0;
        }

        var messages = current.orElseThrow().messages();
        var recipient = messageDispatcher.capture(context.getSource().getSender());
        statuses.status().thenAccept(status -> messageDispatcher.send(recipient, messages.message(
                "command.status",
                Map.of(
                        "provider", status.providerType(),
                        "schema", status.schemaVersion(),
                        "health", status.health().name().toLowerCase(Locale.ROOT),
                        "pending-tasks", Integer.toString(status.pendingTaskCount())
                )
        )));
        return Command.SINGLE_SUCCESS;
    }
}
