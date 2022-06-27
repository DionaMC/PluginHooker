package io.github.dionatestserver.pluginhooker;

import io.github.dionatestserver.pluginhooker.config.ConfigManager;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.HookerManager;
import io.github.dionatestserver.pluginhooker.listeners.PlayerListener;
import io.github.dionatestserver.pluginhooker.player.PlayerManager;
import io.github.dionatestserver.pluginhooker.plugin.PluginManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class DionaPluginHooker extends JavaPlugin {

    @Getter
    private static DionaPluginHooker instance;

    @Getter
    private static HookerManager hookerManager;
    @Getter
    private static PluginManager pluginManager;
    @Getter
    private static PlayerManager playerManager;
    @Getter
    private static ConfigManager configManager;

    public DionaPluginHooker() {
        instance = this;

        configManager = new ConfigManager();
        configManager.loadConfig(DionaConfig.class);

        pluginManager = new PluginManager();
        playerManager = new PlayerManager();
        hookerManager = new HookerManager();
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
