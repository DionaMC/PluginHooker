package dev.diona.pluginhooker.listener;

import dev.diona.pluginhooker.service.PluginRegistryImpl;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Manages plugin lifecycle events for the sandbox.
 */
@RequiredArgsConstructor
public final class PlayerConnectionListener implements Listener {

    private final PluginRegistryImpl pluginRegistry;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPluginEnable(PluginEnableEvent event) {
        pluginRegistry.refreshClassLoaderCache();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        pluginRegistry.unregisterPlugin(event.getPlugin());
    }
}
