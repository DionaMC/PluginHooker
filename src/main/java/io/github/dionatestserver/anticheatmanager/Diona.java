package io.github.dionatestserver.anticheatmanager;

import io.github.dionatestserver.anticheatmanager.anticheat.Anticheat;
import io.github.dionatestserver.anticheatmanager.anticheat.AnticheatManager;
import io.github.dionatestserver.anticheatmanager.anticheat.DionaPlayer;
import io.github.dionatestserver.anticheatmanager.anticheat.PlayerManager;
import io.github.dionatestserver.anticheatmanager.hook.*;
import io.github.dionatestserver.anticheatmanager.listeners.PlayerListener;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class Diona extends JavaPlugin {

    @Getter
    private static Diona instance;

    private HookerManager hookerManager;
    private PlayerManager playerManager;
    private AnticheatManager anticheatManager;


    @Override
    public void onLoad() {
        instance = this;

        (hookerManager = new HookerManager()).init();
        (anticheatManager = new AnticheatManager()).init();
        playerManager = new PlayerManager();
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(),this);
        Anticheat noCheatPlus = new Anticheat(Bukkit.getPluginManager().getPlugin("NoCheatPlus")) {
            @Override
            public void onEnable(DionaPlayer dionaPlayer) {
                System.out.println("AnticheatManager.onEnable");
            }

            @Override
            public void onDisable(DionaPlayer dionaPlayer) {
                System.out.println("AnticheatManager.onEnable");
            }
        };
        this.getAnticheatManager().addAnticheat(noCheatPlus);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
