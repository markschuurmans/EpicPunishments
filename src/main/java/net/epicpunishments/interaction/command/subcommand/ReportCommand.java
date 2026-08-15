package net.epicpunishments.interaction.command.subcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.common.domain.Page;
import net.epicpunishments.common.domain.PageRequest;
import net.epicpunishments.interaction.command.CommandRecipient;
import net.epicpunishments.interaction.command.EpicCommand;
import net.epicpunishments.interaction.command.PaperMessageDispatcher;
import net.epicpunishments.interaction.command.ReportCommandArguments;
import net.epicpunishments.interaction.command.ReportCommandRuntime;
import net.epicpunishments.report.application.ReportResult;
import net.epicpunishments.report.domain.Report;
import net.epicpunishments.report.domain.ReportResponse;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class ReportCommand implements EpicCommand {
    private final ConfigurationService configurations;
    private final PaperMessageDispatcher dispatcher;
    private final Supplier<Optional<ReportCommandRuntime>> runtime;
    private final Logger logger;

    public ReportCommand(
            ConfigurationService configurations,
            PaperMessageDispatcher dispatcher,
            Supplier<Optional<ReportCommandRuntime>> runtime,
            Logger logger
    ) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return "report";
    }

    @Override
    public String permission() {
        return "epicpunishments.command";
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage(message("report.usage"));
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal(name()).requires(source -> source.getSender().hasPermission(permission()))
                .executes(this::execute)
                .then(Commands.literal("create")
                        .requires(source -> source.getSender().hasPermission("epicpunishments.report.create"))
                        .then(Commands.argument("arguments", StringArgumentType.greedyString())
                                .executes(this::createReport)))
                .then(Commands.literal("status")
                        .requires(source -> source.getSender().hasPermission("epicpunishments.report.own"))
                        .then(Commands.argument("report-id", StringArgumentType.word()).executes(this::status)))
                .then(Commands.literal("list")
                        .requires(source -> source.getSender().hasPermission("epicpunishments.report.own"))
                        .executes(context -> list(context, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(context, IntegerArgumentType.getInteger(context, "page")))));
    }

    private int createReport(CommandContext<CommandSourceStack> context) {
        Player player = player(context);
        Optional<ReportCommandRuntime> available = runtime.get();
        if (player == null || available.isEmpty()) {
            return unavailable(context, player);
        }
        final ReportCommandArguments.TargetAndMessage arguments;
        try {
            arguments = ReportCommandArguments.targetAndMessage(
                    StringArgumentType.getString(context, "arguments"));
        } catch (IllegalArgumentException exception) {
            return invalid(context, exception);
        }
        CommandRecipient recipient = dispatcher.capture(player);
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        available.orElseThrow().service().create(
                playerId, playerName, arguments.target(), arguments.message()
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                failed(recipient, failure);
                return;
            }
            render(recipient, result);
            if (result.status() == ReportResult.Status.APPLIED) {
                Report report = result.report().orElseThrow();
                logger.info("Created report " + report.id() + '.');
                available.orElseThrow().notifications().notifyStaff(report);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> context) {
        Player player = player(context);
        Optional<ReportCommandRuntime> available = runtime.get();
        if (player == null || available.isEmpty()) {
            return unavailable(context, player);
        }
        UUID reportId;
        try {
            reportId = ReportCommandArguments.reportId(StringArgumentType.getString(context, "report-id"));
        } catch (IllegalArgumentException exception) {
            return invalid(context, exception);
        }
        CommandRecipient recipient = dispatcher.capture(player);
        available.orElseThrow().service().viewOwn(player.getUniqueId(), reportId)
                .whenComplete((result, failure) -> completeView(recipient, result, failure));
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> context, int page) {
        Player player = player(context);
        Optional<ReportCommandRuntime> available = runtime.get();
        var policy = configurations.current().map(snapshot -> snapshot.reports()).orElse(null);
        if (player == null || available.isEmpty() || policy == null) {
            return unavailable(context, player);
        }
        CommandRecipient recipient = dispatcher.capture(player);
        available.orElseThrow().service().listOwn(
                player.getUniqueId(), new PageRequest(page - 1, policy.pageSize())
        ).whenComplete((result, failure) -> completeList(recipient, result, failure));
        return Command.SINGLE_SUCCESS;
    }

    private void completeView(CommandRecipient recipient, ReportResult result, Throwable failure) {
        if (failure != null) {
            failed(recipient, failure);
            return;
        }
        render(recipient, result);
        if (result.status() == ReportResult.Status.FOUND) {
            for (ReportResponse response : result.responses()) {
                dispatcher.send(recipient, message("report.response-entry", Map.of(
                        "actor", response.administrator().displayName(),
                        "message", response.message(),
                        "created", response.createdAt().toString()
                )));
            }
        }
    }

    private void completeList(CommandRecipient recipient, Page<Report> page, Throwable failure) {
        if (failure != null) {
            failed(recipient, failure);
            return;
        }
        renderPage(recipient, page);
    }

    private void renderPage(CommandRecipient recipient, Page<Report> page) {
        long pages = Math.max(1L, (page.totalItems() + page.size() - 1L) / page.size());
        dispatcher.send(recipient, message("report.list-header", Map.of(
                "page", Integer.toString(page.page() + 1), "pages", Long.toString(pages))));
        if (page.items().isEmpty()) {
            dispatcher.send(recipient, message("report.list-empty"));
        }
        page.items().forEach(report -> dispatcher.send(recipient, reportEntry(report)));
    }

    private void render(CommandRecipient recipient, ReportResult result) {
        String key = switch (result.status()) {
            case APPLIED -> "report.created";
            case FOUND -> "report.details";
            case NOT_FOUND -> "report.not-found";
            case NOT_OWNER -> "report.not-owner";
            case TARGET_NOT_FOUND -> "report.target-not-found";
            case TARGET_AMBIGUOUS -> "report.target-ambiguous";
            case SELF_REPORT -> "report.self";
            case COOLDOWN -> "report.cooldown";
            case DUPLICATE -> "report.duplicate";
            case VERSION_CONFLICT -> "report.version-conflict";
            case INVALID_STATE -> "report.invalid-state";
        };
        if (result.report().isPresent()) {
            Report report = result.report().orElseThrow();
            dispatcher.send(recipient, message(key, placeholders(report)));
        } else {
            dispatcher.send(recipient, message(key));
        }
    }

    private Component reportEntry(Report report) {
        return message("report.list-entry", placeholders(report));
    }

    static Map<String, String> placeholders(Report report) {
        return Map.of(
                "id", report.id().toString(),
                "reporter", report.reporter().nameAtCreation(),
                "reported", report.reported().nameAtCreation(),
                "reason", report.reason(),
                "status", report.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                "assignee", report.assignee().map(actor -> actor.displayName()).orElse("unassigned"),
                "created", report.createdAt().toString()
        );
    }

    private Player player(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getSender() instanceof Player player) {
            return player;
        }
        context.getSource().getSender().sendMessage(message("report.player-only"));
        return null;
    }

    private int unavailable(CommandContext<CommandSourceStack> context, Player player) {
        if (player != null) {
            context.getSource().getSender().sendMessage(message("command.not-ready"));
        }
        return 0;
    }

    private int invalid(CommandContext<CommandSourceStack> context, IllegalArgumentException exception) {
        context.getSource().getSender().sendMessage(message("report.invalid-input", Map.of(
                "error", exception.getMessage())));
        return 0;
    }

    private void failed(CommandRecipient recipient, Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        logger.warning("A report command failed with " + cause.getClass().getSimpleName() + '.');
        dispatcher.send(recipient, message("report.command-failed"));
    }

    private Component message(String key) {
        return configurations.current().map(snapshot -> snapshot.messages().message(key))
                .orElse(Component.text("EpicPunishments is still starting."));
    }

    private Component message(String key, Map<String, String> placeholders) {
        return configurations.current().map(snapshot -> snapshot.messages().message(key, placeholders))
                .orElse(Component.text("EpicPunishments is still starting."));
    }
}
