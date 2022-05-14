package io.github.dionatestserver.anticheatmanager;

import io.github.dionatestserver.anticheatmanager.anticheat.AnticheatManager;
import io.github.dionatestserver.anticheatmanager.anticheat.PlayerManager;
import io.github.dionatestserver.anticheatmanager.hook.HookerManager;
import io.github.dionatestserver.anticheatmanager.listeners.PlayerListener;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class Diona extends JavaPlugin {

    @Getter
    private static final HookerManager hookerManager;

    static {
        (hookerManager = new HookerManager()).init();
        hookerManager.injectEventHandler();
    }

    @Getter
    private static Diona instance;

    private PlayerManager playerManager;
    private AnticheatManager anticheatManager;


    @Override
    public void onLoad() {
        instance = this;

        (anticheatManager = new AnticheatManager()).init();
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
