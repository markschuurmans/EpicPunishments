package net.epicpunishments;

import net.epicpunishments.command.CommandManager;
import net.epicpunishments.command.EpicPunishmentsCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class EpicPunishments extends JavaPlugin {

    @Override
    public void onEnable() {
        CommandManager.register(this, new EpicPunishmentsCommand(this));
    }
}
