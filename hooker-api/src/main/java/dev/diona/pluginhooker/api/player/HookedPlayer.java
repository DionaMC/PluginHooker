package dev.diona.pluginhooker.api.player;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * Represents a player managed by the PluginHooker sandbox.
 */
public interface HookedPlayer {

    /**
     * Returns the underlying Bukkit {@link Player}.
     *
     * @return the Bukkit player
     */
    Player getBukkitPlayer();

    /**
     * Returns the player's unique ID.
     *
     * @return the player UUID
     */
    UUID getUniqueId();

    /**
     * Returns the player's username.
     *
     * @return the player name
     */
    String getName();

    /**
     * Explicitly enables a hooked plugin for this player.
     *
     * @param plugin the hooked plugin to enable
     */
    void enablePlugin(Plugin plugin);

    /**
     * Explicitly disables a hooked plugin for this player.
     *
     * @param plugin the hooked plugin to disable
     */
    void disablePlugin(Plugin plugin);

    /**
     * Clears any explicit per-player override for the given plugin,
     * reverting to the plugin's {@link dev.diona.pluginhooker.api.plugin.HookMode}.
     *
     * @param plugin the hooked plugin
     */
    void resetPlugin(Plugin plugin);

    /**
     * Checks whether the specified hooked plugin is active for this player.
     *
     * @param plugin the hooked plugin
     * @return true if active for this player
     */
    boolean isPluginEnabled(Plugin plugin);

    /**
     * Returns an unmodifiable set of hooked plugins currently enabled for this player.
     *
     * @return enabled hooked plugins
     */
    Set<Plugin> getEnabledPlugins();

    /**
     * Checks if the player is currently within the post-join initialization grace period.
     * During this period, initial packets are allowed through so plugins can initialize player data.
     *
     * @return true if in the join grace period
     */
    boolean isInJoinGracePeriod();

    /**
     * Fast-path check returning whether at least one hooked plugin is currently disabled for this player.
     *
     * @return true if one or more hooked plugins are disabled for this player
     */
    default boolean hasAnyDisabledPlugin() {
        return true;
    }
}
