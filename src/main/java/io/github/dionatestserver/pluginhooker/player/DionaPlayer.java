package io.github.dionatestserver.pluginhooker.player;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;

@Getter
public class DionaPlayer {

    private final Player player;

    private final Set<Plugin> enabledPlugins;

    public DionaPlayer(Player player) {
        this.player = player;
        this.enabledPlugins = new HashSet<>();
    }

    public void enablePlugin(Plugin plugin) {
        if (!DionaPluginHooker.getPluginManager().getPluginsToHook().contains(plugin)) {
            Bukkit.getLogger().warning("Warning: " + plugin.getName() + " is not in the plugin hook list! Ignored!");
            return;
        }
        enabledPlugins.add(plugin);
    }

    public void disablePlugin(Plugin plugin) {
        enabledPlugins.remove(plugin);
    }


}
