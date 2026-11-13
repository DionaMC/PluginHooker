package dev.diona.pluginhooker.listener;

import dev.diona.pluginhooker.PluginHookerPlugin;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Manages plugin and player lifecycle events for the sandbox.
 */
@RequiredArgsConstructor
public final class PluginLifecycleListener implements Listener {

    private final PluginHookerPlugin selfPlugin;
    private final HookConfig hookConfig;
    private final PluginRegistryImpl pluginRegistry;
    private final PlayerRegistryImpl playerRegistry;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerRegistry.unregisterPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPluginEnable(PluginEnableEvent event) {
        pluginRegistry.refreshClassLoaderCache();
        Plugin enabled = event.getPlugin();
        if (enabled != null && !enabled.equals(selfPlugin) && !pluginRegistry.isHooked(enabled)) {
            HookMode mode = hookConfig.getConfiguredPluginMode(enabled.getName());
            if (mode != null) {
                pluginRegistry.registerPlugin(enabled, mode);
                selfPlugin.getLogger().info("Auto-hooked configured plugin: " + enabled.getName() + " [" + mode + "]");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        pluginRegistry.handlePluginDisable(event.getPlugin());
    }
}
