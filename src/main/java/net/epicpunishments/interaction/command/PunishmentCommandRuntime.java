package net.epicpunishments.interaction.command;

import net.epicpunishments.interaction.listener.PaperPunishmentEnforcer;
import net.epicpunishments.interaction.listener.PaperPunishmentNotifications;
import net.epicpunishments.punishment.application.AddressPunishmentService;
import net.epicpunishments.punishment.application.PlayerPunishmentService;

import java.util.Objects;

public record PunishmentCommandRuntime(
        PlayerPunishmentService service,
        AddressPunishmentService addressService,
        PaperPunishmentEnforcer enforcer,
        PaperPunishmentNotifications notifications
) {
    public PunishmentCommandRuntime {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(addressService, "addressService");
        Objects.requireNonNull(enforcer, "enforcer");
        Objects.requireNonNull(notifications, "notifications");
    }
}
