package net.epicpunishments.interaction.command.subcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.epicpunishments.common.config.ConfigurationException;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.interaction.command.EpicCommand;
import net.epicpunishments.interaction.command.PaperMessageDispatcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

public final class ReloadCommand implements EpicCommand {
    private final ConfigurationService configurations;
    private final PaperMessageDispatcher messageDispatcher;
    private final Logger logger;

    public ReloadCommand(
            ConfigurationService configurations,
            PaperMessageDispatcher messageDispatcher,
            Logger logger
    ) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.messageDispatcher = Objects.requireNonNull(messageDispatcher, "messageDispatcher");
        this.logger = Objects.requireNonNull(logger, "logger");
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
        var current = configurations.current();
        if (current.isEmpty()) {
            context.getSource().getSender().sendMessage(Component.text(
                    "EpicPunishments is still starting. Try again shortly.",
                    NamedTextColor.RED
            ));
            return 0;
        }

        var sender = context.getSource().getSender();
        var recipient = messageDispatcher.capture(sender);
        var previousMessages = current.orElseThrow().messages();
        sender.sendMessage(previousMessages.message("command.reload-started"));

        configurations.reload().whenComplete((reloaded, failure) -> {
            if (failure == null) {
                messageDispatcher.send(recipient, reloaded.messages().message("command.reload-success"));
                return;
            }

            messageDispatcher.send(recipient, previousMessages.message("command.reload-failed"));
            logger.warning("Configuration reload failed; the previous configuration remains active. "
                    + safeFailureMessage(failure));
        });
        return Command.SINGLE_SUCCESS;
    }

    private String safeFailureMessage(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ConfigurationException || cause instanceof IllegalStateException) {
            return cause.getMessage();
        }
        return "Unexpected " + cause.getClass().getSimpleName() + '.';
    }
}
