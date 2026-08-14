package net.epicpunishments.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

public final class EpicPunishments extends JavaPlugin {
    private PluginContainer container;

    @Override
    public void onEnable() {
        container = PluginContainer.create(this);
        container.enable();
    }

    @Override
    public void onDisable() {
        if (container != null) {
            container.close();
            container = null;
        }
    }
}
