package io.github.dionatestserver.pluginhooker.examples;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class ExampleHook {

    private final Plugin pluginToHook;

    public ExampleHook(Plugin pluginToHook) {
        this.pluginToHook = pluginToHook;
    }

    public void hookPlugin() {
        DionaPluginHooker.getPluginManager().addPlugin(pluginToHook);
    }

    public void unHookPlugin() {
        DionaPluginHooker.getPluginManager().removePlugin(pluginToHook);
    }

    public void enablePluginForPlayer(Player player) {
        DionaPlayer dionaPlayer = DionaPluginHooker.getPlayerManager().getDionaPlayer(player);
        if (dionaPlayer == null) {
            return;
        }
        dionaPlayer.enablePlugin(pluginToHook);
    }

    public void disablePluginForPlayer(Player player) {
        DionaPlayer dionaPlayer = DionaPluginHooker.getPlayerManager().getDionaPlayer(player);
        if (dionaPlayer == null) {
            return;
        }
        dionaPlayer.disablePlugin(pluginToHook);
    }

}
