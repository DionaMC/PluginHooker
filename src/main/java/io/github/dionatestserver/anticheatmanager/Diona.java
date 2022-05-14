package io.github.dionatestserver.anticheatmanager;

import io.github.dionatestserver.anticheatmanager.anticheat.*;
import io.github.dionatestserver.anticheatmanager.hook.HookerManager;
import io.github.dionatestserver.anticheatmanager.listeners.PlayerListener;
import io.github.dionatestserver.anticheatmanager.player.PlayerManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class Diona extends JavaPlugin {

    private static final HookerManager hookerManager;

    static {
        //我真jb服了 某些反作弊这么喜欢在onload注册事件吗 啊？
        System.out.println("Diona.static initializer");
        hookerManager = new HookerManager();
        hookerManager.injectEventHandler();
    }

    @Getter
    private static Diona instance;

    @Getter
    private AnticheatManager anticheatManager;
    @Getter
    private PlayerManager playerManager;


    @Override
    public void onLoad() {
        instance = this;

        anticheatManager = new AnticheatManager();
        playerManager = new PlayerManager();

        hookerManager.injectPacketHandler();
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);
        // Provide apis
        Bukkit.getServicesManager().register(AnticheatManager.class, this.anticheatManager, this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(PlayerManager.class, this.playerManager, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
