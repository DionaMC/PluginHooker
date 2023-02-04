package io.github.dionatestserver.pluginhooker.plugin;

import io.github.dionatestserver.pluginhooker.PluginHooker;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
public class PluginManager {

    private final Set<Plugin> pluginsToHook = new LinkedHashSet<>();

    public void addPlugin(Plugin plugin) {
        pluginsToHook.add(plugin);
    }

    public void removePlugin(Plugin plugin) {
        if (!pluginsToHook.contains(plugin)) {
            Bukkit.getLogger().warning("Warning: " + plugin.getName() + " is not in the plugin hook list! Ignored!");
            return;
        }
        pluginsToHook.remove(plugin);
        for (DionaPlayer dionaPlayer : PluginHooker.getPlayerManager().getPlayers()) {
            dionaPlayer.getEnabledPlugins().remove(plugin);
        }
    }

}
