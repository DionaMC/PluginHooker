package io.github.dionatestserver.pluginhooker;

import io.github.dionatestserver.pluginhooker.config.ConfigManager;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.HookerManager;
import io.github.dionatestserver.pluginhooker.listeners.PlayerListener;
import io.github.dionatestserver.pluginhooker.listeners.TestListener;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import io.github.dionatestserver.pluginhooker.player.PlayerManager;
import io.github.dionatestserver.pluginhooker.plugin.DionaPlugin;
import io.github.dionatestserver.pluginhooker.plugin.PluginManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class Diona extends JavaPlugin {

    @Getter
    private static Diona instance;

    @Getter
    private static HookerManager hookerManager;
    @Getter
    private static PluginManager pluginManager;
    @Getter
    private static PlayerManager playerManager;
    @Getter
    private static ConfigManager configManager;

    public Diona() {
        instance = this;

        configManager = new ConfigManager();
        configManager.loadConfig(DionaConfig.class);

        hookerManager = new HookerManager();
        hookerManager.injectEventHandler();

        pluginManager = new PluginManager();
        playerManager = new PlayerManager();
    }

    @Override
    public void onLoad() {
        hookerManager.injectPacketHandler();
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);

        //测试代码
        Bukkit.getPluginManager().registerEvents(new TestListener(), this);

        Diona.getPluginManager().addPlugin(new DionaPlugin(Bukkit.getPluginManager().getPlugin("Matrix")) {
            @Override
            public void onEnable(DionaPlayer dionaPlayer) {

            }

            @Override
            public void onDisable(DionaPlayer dionaPlayer) {

            }
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
