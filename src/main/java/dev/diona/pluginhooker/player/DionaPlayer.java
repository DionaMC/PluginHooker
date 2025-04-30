package dev.diona.pluginhooker.player;

import dev.diona.pluginhooker.PluginHooker;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public class DionaPlayer {

    private final Player player;

    private final Set<Plugin> enabledPlugins = new HashSet<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @Setter
    private boolean packetEventsHooked = false;

    public DionaPlayer(Player player) {
        this.player = player;
    }

    public void enablePlugin(Plugin plugin) {
        if (!PluginHooker.getPluginManager().getPluginsToHook().contains(plugin)) {
            Bukkit.getLogger().warning("Warning: " + plugin.getName() + " is not in the plugin hook list! Ignored!");
            return;
        }
        enabledPlugins.add(plugin);
    }

    public void disablePlugin(Plugin plugin) {
        enabledPlugins.remove(plugin);
    }

    public boolean isPluginEnabled(Plugin plugin) {
        return enabledPlugins.contains(plugin);
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public void setInitialized(boolean initialized) {
        this.initialized.set(initialized);
    }
}
