package io.github.dionatestserver.pluginhooker.plugin;

import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import lombok.Getter;
import org.bukkit.plugin.Plugin;

@Getter
public abstract class DionaPlugin {

    private final Plugin plugin;

    public DionaPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    public abstract void onEnable(DionaPlayer dionaPlayer);

    public abstract void onDisable(DionaPlayer dionaPlayer);

}