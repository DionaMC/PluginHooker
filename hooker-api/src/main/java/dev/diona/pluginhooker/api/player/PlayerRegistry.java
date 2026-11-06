package dev.diona.pluginhooker.api.player;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

/**
 * Registry of online {@link HookedPlayer} instances.
 */
public interface PlayerRegistry {

    /**
     * Gets the {@link HookedPlayer} wrapper for a Bukkit player.
     *
     * @param player the Bukkit player
     * @return the hooked player instance, or null if offline/unregistered
     */
    HookedPlayer getPlayer(Player player);

    /**
     * Gets the {@link HookedPlayer} wrapper by UUID.
     *
     * @param uuid the player's UUID
     * @return the hooked player instance, or null if offline/unregistered
     */
    HookedPlayer getPlayer(UUID uuid);

    /**
     * Returns an unmodifiable collection of all currently tracked players.
     *
     * @return collection of hooked players
     */
    Collection<HookedPlayer> getPlayers();
}
