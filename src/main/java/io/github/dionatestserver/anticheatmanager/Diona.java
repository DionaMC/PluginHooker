package io.github.dionatestserver.anticheatmanager;

import io.github.dionatestserver.anticheatmanager.anticheat.Anticheat;
import io.github.dionatestserver.anticheatmanager.anticheat.AnticheatManager;
import io.github.dionatestserver.anticheatmanager.anticheat.DionaPlayer;
import io.github.dionatestserver.anticheatmanager.anticheat.PlayerManager;
import io.github.dionatestserver.anticheatmanager.hook.HookerManager;
import io.github.dionatestserver.anticheatmanager.listeners.PlayerListener;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class Diona extends JavaPlugin {

    @Getter
    private static HookerManager hookerManager;

    static {
        //我真jb服了 某些反作弊这么喜欢在onload注册事件吗 啊？
        System.out.println("Diona.static initializer");
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

        Bukkit.getPluginManager().registerEvents(new PlayerListener(),this);
        Anticheat noCheatPlus = new Anticheat(Bukkit.getPluginManager().getPlugin("Matrix")) {
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
