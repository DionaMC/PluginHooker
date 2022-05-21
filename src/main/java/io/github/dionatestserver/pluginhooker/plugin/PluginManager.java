package io.github.dionatestserver.pluginhooker.plugin;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
public class PluginManager {

    private final Set<Plugin> PluginsToHook = new LinkedHashSet<>();

    public void addPlugin(Plugin plugin) {
        PluginsToHook.add(plugin);
    }

    public void removePlugin(Plugin plugin) {
        if (!PluginsToHook.contains(plugin)) {
            Bukkit.getLogger().warning("Warning: " + plugin.getName() + " is not in plugin hook list! Ignored!");
            return;
        }
        PluginsToHook.remove(plugin);
        for (DionaPlayer dionaPlayer : DionaPluginHooker.getPlayerManager().getPlayers()) {
            dionaPlayer.getEnabledPlugins().remove(plugin);
        }
    }

}
