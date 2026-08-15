package net.epicpunishments.interaction.command.subcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.epicpunishments.common.config.ConfigurationService;
import net.epicpunishments.common.domain.Actor;
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
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class ReportsCommand implements EpicCommand {
    private final ConfigurationService configurations;
    private final PaperMessageDispatcher dispatcher;
    private final Supplier<Optional<ReportCommandRuntime>> runtime;
    private final Logger logger;

    public ReportsCommand(
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
        return "reports";
    }

    @Override
    public String permission() {
        return "epicpunishments.command";
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage(message("reports.usage"));
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal(name()).requires(source -> source.getSender().hasPermission(permission()))
                .executes(this::execute)
                .then(Commands.literal("list")
                        .requires(source -> source.getSender().hasPermission(
                                "epicpunishments.report.staff.list"))
                        .executes(context -> list(context, ""))
                        .then(Commands.argument("arguments", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    builder.suggest("open");
                                    builder.suggest("in-review");
                                    builder.suggest("resolved");
                                    builder.suggest("dismissed");
                                    return builder.buildFuture();
                                }).executes(context -> list(context,
                                        StringArgumentType.getString(context, "arguments")))))
                .then(idBranch("view", "epicpunishments.report.staff.view", this::view))
                .then(idBranch("claim", "epicpunishments.report.staff.claim", this::claim))
                .then(messageBranch("respond", "epicpunishments.report.staff.respond", this::respond))
                .then(messageBranch("resolve", "epicpunishments.report.staff.resolve", this::resolve))
                .then(messageBranch("dismiss", "epicpunishments.report.staff.dismiss", this::dismiss));
    }

    private LiteralArgumentBuilder<CommandSourceStack> idBranch(
            String name,
            String permission,
            BiFunction<CommandContext<CommandSourceStack>, UUID, Integer> executor
    ) {
        return Commands.literal(name).requires(source -> source.getSender().hasPermission(permission))
                .then(Commands.argument("report-id", StringArgumentType.word()).executes(context -> {
                    try {
                        return executor.apply(context, ReportCommandArguments.reportId(
                                StringArgumentType.getString(context, "report-id")));
                    } catch (IllegalArgumentException exception) {
                        return invalid(context, exception);
                    }
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> messageBranch(
            String name,
            String permission,
            java.util.function.BiFunction<CommandContext<CommandSourceStack>, String, Integer> executor
    ) {
        return Commands.literal(name).requires(source -> source.getSender().hasPermission(permission))
                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                        .executes(context -> executor.apply(
                                context, StringArgumentType.getString(context, "arguments"))));
    }

    private int list(CommandContext<CommandSourceStack> context, String input) {
        Optional<ReportCommandRuntime> available = runtime.get();
        var policy = configurations.current().map(snapshot -> snapshot.reports()).orElse(null);
        if (available.isEmpty() || policy == null) {
            return notReady(context);
        }
        final ReportCommandArguments.StaffList arguments;
        try {
            arguments = ReportCommandArguments.staffList(input);
        } catch (IllegalArgumentException exception) {
            return invalid(context, exception);
        }
        CommandRecipient recipient = dispatcher.capture(context.getSource().getSender());
        available.orElseThrow().service().listStaff(arguments.status(), new PageRequest(
                arguments.page() - 1, policy.pageSize()
        )).whenComplete((page, failure) -> completeList(recipient, page, failure));
        return Command.SINGLE_SUCCESS;
    }

    private int view(CommandContext<CommandSourceStack> context, UUID reportId) {
        Optional<ReportCommandRuntime> available = runtime.get();
        if (available.isEmpty()) {
            return notReady(context);
        }
        CommandRecipient recipient = dispatcher.capture(context.getSource().getSender());
        available.orElseThrow().service().viewStaff(reportId)
                .whenComplete((result, failure) -> completeView(recipient, result, failure));
        return Command.SINGLE_SUCCESS;
    }

    private int claim(CommandContext<CommandSourceStack> context, UUID reportId) {
        return mutate(context, false, (runtime, actor) -> runtime.service().claim(reportId, actor));
    }

    private int respond(CommandContext<CommandSourceStack> context, String input) {
        try {
            var arguments = ReportCommandArguments.idAndRequiredMessage(input);
            return mutate(context, true, (runtime, actor) ->
                    runtime.service().respond(arguments.reportId(), actor, arguments.message()));
        } catch (IllegalArgumentException exception) {
            return invalid(context, exception);
        }
    }

    private int resolve(CommandContext<CommandSourceStack> context, String input) {
        try {
            var arguments = ReportCommandArguments.idAndOptionalMessage(input);
            return mutate(context, true, (runtime, actor) ->
                    runtime.service().resolve(arguments.reportId(), actor, arguments.message()));
        } catch (IllegalArgumentException exception) {
            return invalid(context, exception);
        }
    }

    private int dismiss(CommandContext<CommandSourceStack> context, String input) {
        try {
            var arguments = ReportCommandArguments.idAndRequiredMessage(input);
            return mutate(context, true, (runtime, actor) ->
                    runtime.service().dismiss(arguments.reportId(), actor, arguments.message()));
        } catch (IllegalArgumentException exception) {
            return invalid(context, exception);
        }
    }

    private int mutate(
            CommandContext<CommandSourceStack> context,
            boolean notifyReporter,
            StaffMutation mutation
    ) {
        Optional<ReportCommandRuntime> available = runtime.get();
        if (available.isEmpty()) {
            return notReady(context);
        }
        Actor actor = actor(context.getSource().getSender());
        if (actor == null) {
            context.getSource().getSender().sendMessage(message("report.unsupported-sender"));
            return 0;
        }
        CommandRecipient recipient = dispatcher.capture(context.getSource().getSender());
        mutation.apply(available.orElseThrow(), actor).whenComplete((result, failure) -> {
            if (failure != null) {
                failed(recipient, failure);
                return;
            }
            render(recipient, result);
            if (result.status() == ReportResult.Status.APPLIED) {
                Report report = result.report().orElseThrow();
                logger.info("Updated report " + report.id() + " to " + report.status().name().toLowerCase() + '.');
                if (notifyReporter) {
                    available.orElseThrow().notifications().notifyReporter(report);
                }
            }
        });
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
        long pages = Math.max(1L, (page.totalItems() + page.size() - 1L) / page.size());
        dispatcher.send(recipient, message("report.list-header", Map.of(
                "page", Integer.toString(page.page() + 1), "pages", Long.toString(pages))));
        if (page.items().isEmpty()) {
            dispatcher.send(recipient, message("report.list-empty"));
        }
        page.items().forEach(report -> dispatcher.send(recipient,
                message("report.list-entry", ReportCommand.placeholders(report))));
    }

    private void render(CommandRecipient recipient, ReportResult result) {
        String key = switch (result.status()) {
            case APPLIED -> "report.updated";
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
        dispatcher.send(recipient, result.report().isPresent()
                ? message(key, ReportCommand.placeholders(result.report().orElseThrow()))
                : message(key));
    }

    private static Actor actor(CommandSender sender) {
        if (sender instanceof Player player) {
            return Actor.player(player.getUniqueId(), player.getName());
        }
        if (sender instanceof ConsoleCommandSender) {
            return Actor.console();
        }
        return null;
    }

    private int notReady(CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage(message("command.not-ready"));
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
        logger.warning("A staff report command failed with " + cause.getClass().getSimpleName() + '.');
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

    @FunctionalInterface
    private interface StaffMutation {
        java.util.concurrent.CompletionStage<ReportResult> apply(ReportCommandRuntime runtime, Actor actor);
    }
}
