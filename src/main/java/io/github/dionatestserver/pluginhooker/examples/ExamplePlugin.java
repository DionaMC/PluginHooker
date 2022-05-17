package io.github.dionatestserver.pluginhooker.examples;

import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import io.github.dionatestserver.pluginhooker.plugin.DionaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ExamplePlugin extends DionaPlugin {

    public ExamplePlugin() {
        super(Bukkit.getPluginManager().getPlugin("plugin name"));
    }

    @Override
    public void onEnable(DionaPlayer dionaPlayer) {
        // do something on plugin enable
    }

    @Override
    public void onDisable(DionaPlayer dionaPlayer) {
        // do something on plugin disable
    }
}
