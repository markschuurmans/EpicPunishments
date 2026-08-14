package net.epicPunishments;

import net.epicPunishments.command.CommandManager;
import net.epicPunishments.command.EpicPunishmentsCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class EpicPunishments extends JavaPlugin {

    @Override
    public void onEnable() {
        CommandManager.register(this, new EpicPunishmentsCommand(this));
    }
}
