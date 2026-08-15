package net.epicpunishments.interaction.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.common.observability.PluginStatusService;
import net.epicpunishments.interaction.command.subcommand.ReloadCommand;
import net.epicpunishments.interaction.command.subcommand.PunishCommand;
import net.epicpunishments.interaction.command.subcommand.VersionCommand;
import net.epicpunishments.interaction.command.subcommand.StatusCommand;
import net.epicpunishments.interaction.command.subcommand.ReportCommand;
import net.epicpunishments.interaction.command.subcommand.ReportsCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import java.util.Optional;
import java.util.function.Supplier;
import java.time.Clock;
import org.bukkit.Server;

public final class EpicPunishmentsCommand implements EpicCommand {
    private final ConfigurationService configurations;
    private final Collection<EpicCommand> subcommands;
    private final PunishCommand punishCommand;

    public EpicPunishmentsCommand(
            ConfigurationService configurations,
            String version,
            PluginStatusService statuses,
            PaperMessageDispatcher messageDispatcher,
            Supplier<Optional<PunishmentCommandRuntime>> punishmentRuntime,
            Supplier<Optional<ReportCommandRuntime>> reportRuntime,
            Server server,
            Clock clock,
            Logger logger
    ) {
        this.configurations = configurations;
        this.punishCommand = new PunishCommand(
                configurations, messageDispatcher, punishmentRuntime, server, clock, logger);
        this.subcommands = List.of(
                new VersionCommand(configurations, version),
                new StatusCommand(configurations, statuses, messageDispatcher),
                new ReloadCommand(configurations, messageDispatcher, logger),
                punishCommand,
                new ReportCommand(configurations, messageDispatcher, reportRuntime, logger),
                new ReportsCommand(configurations, messageDispatcher, reportRuntime, logger)
        );
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
        return subcommands;
    }

    @Override
    public Collection<ConvenienceCommand> convenienceCommands() {
        return punishCommand.convenienceCommands();
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        Component message = configurations.current()
                .map(snapshot -> snapshot.messages().message("command.usage"))
                .orElseGet(() -> Component.text(
                        "Use /epicpunishments <subcommand>.",
                        NamedTextColor.GOLD
                ));
        context.getSource().getSender().sendMessage(message);
        return Command.SINGLE_SUCCESS;
    }
}
