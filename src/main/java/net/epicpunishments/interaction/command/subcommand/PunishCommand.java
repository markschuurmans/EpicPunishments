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
import net.epicpunishments.interaction.command.PunishmentCommandRuntime;
import net.epicpunishments.interaction.command.PunishmentCommandArguments;
import net.epicpunishments.punishment.application.CreatePunishmentInput;
import net.epicpunishments.punishment.application.PlayerHistoryResult;
import net.epicpunishments.punishment.application.PlayerModerationRequest;
import net.epicpunishments.punishment.application.PlayerModerationResult;
import net.epicpunishments.punishment.application.PlayerRevocationRequest;
import net.epicpunishments.punishment.application.PunishmentDurationParser;
import net.epicpunishments.punishment.domain.Punishment;
import net.epicpunishments.punishment.domain.PunishmentType;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class PunishCommand implements EpicCommand {
    private static final String BASE_PERMISSION = "epicpunishments.command";
    private static final String OVERRIDE_PERMISSION = "epicpunishments.punishment.override-exempt";

    private final ConfigurationService configurations;
    private final PaperMessageDispatcher dispatcher;
    private final Supplier<Optional<PunishmentCommandRuntime>> runtime;
    private final Server server;
    private final Clock clock;
    private final Logger logger;

    public PunishCommand(
            ConfigurationService configurations,
            PaperMessageDispatcher dispatcher,
            Supplier<Optional<PunishmentCommandRuntime>> runtime,
            Server server,
            Clock clock,
            Logger logger
    ) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.server = Objects.requireNonNull(server, "server");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return "punish";
    }

    @Override
    public String permission() {
        return BASE_PERMISSION;
    }

    @Override
    public int execute(CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage(message("punishment.usage"));
        return Command.SINGLE_SUCCESS;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(permission()))
                .executes(this::execute)
                .then(createBranch("ban", PunishmentType.BAN, "epicpunishments.punishment.ban"))
                .then(revokeBranch("unban", PunishmentType.BAN, "epicpunishments.punishment.unban"))
                .then(createBranch("mute", PunishmentType.MUTE, "epicpunishments.punishment.mute"))
                .then(revokeBranch("unmute", PunishmentType.MUTE, "epicpunishments.punishment.unmute"))
                .then(warnBranch())
                .then(historyBranch("warnings", Optional.of(PunishmentType.WARNING),
                        "epicpunishments.punishment.warnings"))
                .then(historyBranch("history", Optional.empty(), "epicpunishments.punishment.history"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createBranch(
            String name,
            PunishmentType type,
            String permission
    ) {
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(permission))
                .then(commandArguments().executes(context -> create(context, type)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> revokeBranch(
            String name,
            PunishmentType type,
            String permission
    ) {
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(permission))
                .then(commandArguments().executes(context -> revoke(context, type)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> warnBranch() {
        return Commands.literal("warn")
                .requires(source -> source.getSender().hasPermission("epicpunishments.punishment.warn"))
                .then(commandArguments().executes(this::createWarning));
    }

    private LiteralArgumentBuilder<CommandSourceStack> historyBranch(
            String name,
            Optional<PunishmentType> type,
            String permission
    ) {
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(permission))
                .then(commandArguments().executes(context -> history(context, type)));
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> commandArguments() {
        return Commands.argument("arguments", StringArgumentType.greedyString()).suggests((context, builder) -> {
            for (Player player : server.getOnlinePlayers()) {
                builder.suggest("player:" + player.getName());
            }
            return builder.buildFuture();
        });
    }

    private int create(CommandContext<CommandSourceStack> context, PunishmentType type) {
        var policy = configurations.current().map(snapshot -> snapshot.punishments()).orElse(null);
        if (policy == null) {
            send(context, "command.not-ready");
            return 0;
        }
        try {
            PunishmentCommandArguments.TargetAndRemainder arguments =
                    PunishmentCommandArguments.withRequiredRemainder(commandArguments(context));
            CreatePunishmentInput input = CreatePunishmentInput.parse(
                    arguments.remainder(),
                    new PunishmentDurationParser(policy.maximumDuration())
            );
            return submitCreate(context, type, arguments.target(), input);
        } catch (IllegalArgumentException exception) {
            send(context, "punishment.invalid-input", Map.of("error", exception.getMessage()));
            return 0;
        }
    }

    private int createWarning(CommandContext<CommandSourceStack> context) {
        try {
            PunishmentCommandArguments.TargetAndRemainder arguments =
                    PunishmentCommandArguments.withRequiredRemainder(commandArguments(context));
            return submitCreate(context, PunishmentType.WARNING, arguments.target(), new CreatePunishmentInput(
                    Optional.empty(),
                    arguments.remainder()
            ));
        } catch (IllegalArgumentException exception) {
            send(context, "punishment.invalid-input", Map.of("error", exception.getMessage()));
            return 0;
        }
    }

    private int submitCreate(
            CommandContext<CommandSourceStack> context,
            PunishmentType type,
            String target,
            CreatePunishmentInput input
    ) {
        Invocation invocation = capture(context);
        if (invocation == null) {
            return 0;
        }
        Optional<PunishmentCommandRuntime> available = runtime.get();
        if (available.isEmpty()) {
            send(context, "command.not-ready");
            return 0;
        }
        try {
            available.orElseThrow().service().create(new PlayerModerationRequest(
                    target,
                    type,
                    input.reason(),
                    input.duration(),
                    invocation.actor(),
                    context.getSource().getSender().hasPermission(OVERRIDE_PERMISSION)
            )).whenComplete((result, failure) -> {
                if (failure != null) {
                    failed(invocation.recipient(), failure);
                    return;
                }
                renderModerationResult(invocation.recipient(), result, false);
                if (result.status() == PlayerModerationResult.Status.APPLIED) {
                    Punishment committed = result.punishments().getFirst();
                    logger.info("Created player " + committed.type().name().toLowerCase(Locale.ROOT)
                            + " punishment " + committed.id() + '.');
                    available.orElseThrow().enforcer().apply(committed);
                }
            });
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException exception) {
            dispatcher.send(invocation.recipient(), message("punishment.invalid-input", Map.of(
                    "error", exception.getMessage()
            )));
            return 0;
        }
    }

    private int revoke(CommandContext<CommandSourceStack> context, PunishmentType type) {
        final PunishmentCommandArguments.TargetAndRemainder arguments;
        try {
            arguments = PunishmentCommandArguments.withOptionalRemainder(commandArguments(context));
        } catch (IllegalArgumentException exception) {
            send(context, "punishment.invalid-input", Map.of("error", exception.getMessage()));
            return 0;
        }
        Invocation invocation = capture(context);
        if (invocation == null) {
            return 0;
        }
        Optional<PunishmentCommandRuntime> available = runtime.get();
        if (available.isEmpty()) {
            send(context, "command.not-ready");
            return 0;
        }
        try {
            available.orElseThrow().service().revoke(new PlayerRevocationRequest(
                    arguments.target(),
                    type,
                    arguments.remainder().isEmpty() ? "-" : arguments.remainder(),
                    invocation.actor()
            )).whenComplete((result, failure) -> {
                if (failure != null) {
                    failed(invocation.recipient(), failure);
                } else {
                    renderModerationResult(invocation.recipient(), result, true);
                    if (result.status() == PlayerModerationResult.Status.APPLIED) {
                        result.punishments().forEach(punishment -> logger.info(
                                "Revoked player " + punishment.type().name().toLowerCase(Locale.ROOT)
                                        + " punishment " + punishment.id() + '.'
                        ));
                    }
                }
            });
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException exception) {
            dispatcher.send(invocation.recipient(), message("punishment.invalid-input", Map.of(
                    "error", exception.getMessage()
            )));
            return 0;
        }
    }

    private int history(CommandContext<CommandSourceStack> context, Optional<PunishmentType> type) {
        final PunishmentCommandArguments.HistoryArguments arguments;
        try {
            arguments = PunishmentCommandArguments.history(commandArguments(context));
        } catch (IllegalArgumentException exception) {
            send(context, "punishment.invalid-input", Map.of("error", exception.getMessage()));
            return 0;
        }
        Invocation invocation = capture(context);
        if (invocation == null) {
            return 0;
        }
        Optional<PunishmentCommandRuntime> available = runtime.get();
        var policy = configurations.current().map(snapshot -> snapshot.punishments()).orElse(null);
        if (available.isEmpty() || policy == null) {
            send(context, "command.not-ready");
            return 0;
        }
        available.orElseThrow().service().history(
                arguments.target(),
                type,
                new PageRequest(arguments.page() - 1, policy.historyPageSize())
        ).whenComplete((result, failure) -> {
            if (failure != null) {
                failed(invocation.recipient(), failure);
            } else {
                renderHistory(invocation.recipient(), result);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static String commandArguments(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "arguments");
    }

    private Invocation capture(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (sender instanceof Player player) {
            return new Invocation(
                    Actor.player(player.getUniqueId(), player.getName()),
                    dispatcher.capture(sender)
            );
        }
        if (sender instanceof ConsoleCommandSender) {
            return new Invocation(Actor.console(), dispatcher.capture(sender));
        }
        sender.sendMessage(message("punishment.unsupported-sender"));
        return null;
    }

    private void renderModerationResult(
            CommandRecipient recipient,
            PlayerModerationResult result,
            boolean revocation
    ) {
        switch (result.status()) {
            case APPLIED -> dispatcher.send(recipient, message(
                    revocation ? "punishment.revoked" : "punishment.applied",
                    Map.of(
                            "count", Integer.toString(result.punishments().size()),
                            "player", result.identity().orElseThrow().currentName(),
                            "type", result.punishments().getFirst().type().name().toLowerCase(Locale.ROOT)
                    )
            ));
            case INVALID_TARGET -> dispatcher.send(recipient, message("punishment.invalid-target"));
            case TARGET_NOT_FOUND -> dispatcher.send(recipient, message("punishment.target-not-found"));
            case TARGET_AMBIGUOUS -> dispatcher.send(recipient, message("punishment.target-ambiguous"));
            case TARGET_EXEMPT -> dispatcher.send(recipient, message("punishment.target-exempt"));
            case NO_ACTIVE_PUNISHMENT -> dispatcher.send(recipient, message("punishment.no-active"));
        }
    }

    private void renderHistory(CommandRecipient recipient, PlayerHistoryResult result) {
        if (result.status() != PlayerModerationResult.Status.APPLIED) {
            renderModerationResult(recipient, new PlayerModerationResult(
                    result.status(), result.identity(), java.util.List.of()), false);
            return;
        }
        Page<Punishment> history = result.history().orElseThrow();
        if (history.items().isEmpty()) {
            dispatcher.send(recipient, message("punishment.history-empty"));
            return;
        }
        long pages = Math.max(1L, (history.totalItems() + history.size() - 1L) / history.size());
        dispatcher.send(recipient, message("punishment.history-header", Map.of(
                "player", result.identity().orElseThrow().currentName(),
                "page", Integer.toString(history.page() + 1),
                "pages", Long.toString(pages)
        )));
        Instant now = clock.instant();
        for (Punishment punishment : history.items()) {
            String status = punishment.revocation().isPresent() ? "revoked"
                    : punishment.isActiveAt(now) ? "active" : "expired";
            dispatcher.send(recipient, message("punishment.history-entry", Map.of(
                    "id", punishment.id().toString(),
                    "type", punishment.type().name().toLowerCase(Locale.ROOT),
                    "created", punishment.createdAt().toString(),
                    "status", status,
                    "reason", punishment.reason()
            )));
        }
    }

    private void failed(CommandRecipient recipient, Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        logger.warning("A player moderation command failed with " + cause.getClass().getSimpleName() + '.');
        dispatcher.send(recipient, message("punishment.command-failed"));
    }

    private void send(CommandContext<CommandSourceStack> context, String key) {
        context.getSource().getSender().sendMessage(message(key));
    }

    private void send(CommandContext<CommandSourceStack> context, String key, Map<String, String> values) {
        context.getSource().getSender().sendMessage(message(key, values));
    }

    private Component message(String key) {
        return configurations.current().map(snapshot -> snapshot.messages().message(key))
                .orElse(Component.text("EpicPunishments is still starting."));
    }

    private Component message(String key, Map<String, String> values) {
        return configurations.current().map(snapshot -> snapshot.messages().message(key, values))
                .orElse(Component.text("EpicPunishments is still starting."));
    }

    private record Invocation(Actor actor, CommandRecipient recipient) {
    }
}
