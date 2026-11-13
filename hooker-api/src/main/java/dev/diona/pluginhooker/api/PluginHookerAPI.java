package dev.diona.pluginhooker.api;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Primary entry point for interacting with the PluginHooker v2 API.
 */
public final class PluginHookerAPI {

    private static Provider provider;

    private PluginHookerAPI() {
    }

    /**
     * Internal registration method called by PluginHooker core on initialization.
     *
     * @param apiProvider the API provider implementation
     */
    public static void setProvider(Provider apiProvider) {
        provider = apiProvider;
    }

    /**
     * Returns the {@link PlayerRegistry} instance.
     *
     * @return the player registry
     */
    public static PlayerRegistry getPlayerRegistry() {
        return getProvider().getPlayerRegistry();
    }

    /**
     * Returns the {@link PluginRegistry} instance.
     *
     * @return the plugin registry
     */
    public static PluginRegistry getPluginRegistry() {
        return getProvider().getPluginRegistry();
    }

    /**
     * Convenience method to get a {@link HookedPlayer} from a Bukkit {@link Player}.
     *
     * @param player the Bukkit player
     * @return the hooked player, or null if not online
     */
    public static HookedPlayer getPlayer(Player player) {
        return getPlayerRegistry().getPlayer(player);
    }

    /**
     * Convenience method to check if a plugin is currently allowed to interact with a player.
     *
     * @param plugin the plugin
     * @param player the Bukkit player
     * @return true if allowed
     */
    public static boolean isAllowed(Plugin plugin, Player player) {
        if (plugin == null || player == null) {
            return true;
        }
        HookMode mode = getPluginRegistry().getHookMode(plugin);
        if (mode == null) {
            return true;
        }
        HookedPlayer hookedPlayer = getPlayer(player);
        if (hookedPlayer != null) {
            return hookedPlayer.isPluginEnabled(plugin);
        }
        return mode != HookMode.WHITELIST
                || player.getClass().getName().startsWith("com.comphenix.protocol.injector.");
    }

    private static Provider getProvider() {
        if (provider == null) {
            throw new IllegalStateException("PluginHookerAPI has not been initialized yet!");
        }
        return provider;
    }

    /**
     * Backing service provider interface implemented by hooker-core.
     */
    public interface Provider {
        PlayerRegistry getPlayerRegistry();

        PluginRegistry getPluginRegistry();
    }
}
