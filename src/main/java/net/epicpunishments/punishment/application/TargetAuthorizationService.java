package net.epicpunishments.punishment.application;

import net.epicpunishments.common.domain.Actor;
import net.epicpunishments.common.domain.ActorType;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class TargetAuthorizationService {
    private final BooleanSupplier consoleBypassesExempt;

    public TargetAuthorizationService(boolean consoleBypassesExempt) {
        this(() -> consoleBypassesExempt);
    }

    public TargetAuthorizationService(BooleanSupplier consoleBypassesExempt) {
        this.consoleBypassesExempt = Objects.requireNonNull(consoleBypassesExempt, "consoleBypassesExempt");
    }

    public boolean mayPunish(Actor actor, boolean hasOverridePermission, boolean targetExempt) {
        Objects.requireNonNull(actor, "actor");
        return !targetExempt
                || hasOverridePermission
                || (actor.type() == ActorType.CONSOLE && consoleBypassesExempt.getAsBoolean());
    }
}
