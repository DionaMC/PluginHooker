package dev.diona.pluginhooker.player;

import com.comphenix.protocol.injector.SortedPacketListenerList;
import dev.diona.pluginhooker.PluginHooker;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public class DionaPlayer {

    private final Player player;

    private final Set<Plugin> enabledPlugins = new HashSet<>();

    // cached ProtocolLib listener list
    @Setter
    private SortedPacketListenerList receivedCachedListeners;

    @Setter
    private SortedPacketListenerList sendingCachedListeners;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public DionaPlayer(Player player) {
        this.player = player;
    }

    public void enablePlugin(Plugin plugin) {
        if (!PluginHooker.getPluginManager().getPluginsToHook().contains(plugin)) {
            Bukkit.getLogger().warning("Warning: " + plugin.getName() + " is not in the plugin hook list! Ignored!");
            return;
        }
        enabledPlugins.add(plugin);
        removeCachedListener();
    }

    public void disablePlugin(Plugin plugin) {
        enabledPlugins.remove(plugin);
        removeCachedListener();
    }

    public boolean isPluginEnabled(Plugin plugin) {
        return enabledPlugins.contains(plugin);
    }

    public synchronized void removeCachedListener() {
        sendingCachedListeners = null;
        receivedCachedListeners = null;
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public void setInitialized() {
        this.initialized.set(true);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DionaPlayer that = (DionaPlayer) o;
        return player.equals(that.player);
    }

    @Override
    public int hashCode() {
        return Objects.hash(player);
    }
}
