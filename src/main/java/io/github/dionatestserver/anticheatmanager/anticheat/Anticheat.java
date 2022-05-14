package io.github.dionatestserver.anticheatmanager.anticheat;

import io.github.dionatestserver.anticheatmanager.player.DionaPlayer;
import lombok.Getter;
import org.bukkit.plugin.Plugin;

@Getter
public abstract class Anticheat {

    private final Plugin plugin;

    public Anticheat(Plugin plugin) {
        this.plugin = plugin;
    }

    public abstract void onEnable(DionaPlayer dionaPlayer);

    public abstract void onDisable(DionaPlayer dionaPlayer);

}