package io.github.dionatestserver.pluginhooker;

import io.github.dionatestserver.pluginhooker.hook.HookerManager;
import io.github.dionatestserver.pluginhooker.listeners.PlayerListener;
import io.github.dionatestserver.pluginhooker.player.PlayerManager;
import io.github.dionatestserver.pluginhooker.plugin.PluginManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class Diona extends JavaPlugin {

    @Getter
    private static final HookerManager hookerManager;

    static {
        hookerManager = new HookerManager();
        hookerManager.injectEventHandler();
    }

    @Getter
    private static Diona instance;

    @Getter
    private PluginManager pluginManager;
    @Getter
    private PlayerManager playerManager;


    @Override
    public void onLoad() {
        instance = this;

        pluginManager = new PluginManager();
        playerManager = new PlayerManager();

        hookerManager.injectPacketHandler();
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
