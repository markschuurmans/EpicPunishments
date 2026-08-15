package net.epicpunishments.interaction.command;

import net.epicpunishments.interaction.listener.PaperPunishmentEnforcer;
import net.epicpunishments.punishment.application.PlayerPunishmentService;

import java.util.Objects;

public record PunishmentCommandRuntime(
        PlayerPunishmentService service,
        PaperPunishmentEnforcer enforcer
) {
    public PunishmentCommandRuntime {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(enforcer, "enforcer");
    }
}
