package dev.diona.pluginhooker.service;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe implementation of {@link PlayerRegistry}.
 */
@RequiredArgsConstructor
public final class PlayerRegistryImpl implements PlayerRegistry {

    private final Map<UUID, HookedPlayerImpl> players = new ConcurrentHashMap<>();
    private final PluginRegistry pluginRegistry;
    private final HookConfig config;

    public HookedPlayerImpl registerPlayer(Player bukkitPlayer) {
        HookedPlayerImpl hookedPlayer = new HookedPlayerImpl(
                bukkitPlayer,
                pluginRegistry,
                config.getJoinGraceMillis()
        );
        players.put(bukkitPlayer.getUniqueId(), hookedPlayer);
        PlayerChannelResolver.bindPlayerToChannel(bukkitPlayer, hookedPlayer);
        return hookedPlayer;
    }

    public void unregisterPlayer(Player bukkitPlayer) {
        if (bukkitPlayer == null) {
            return;
        }
        players.remove(bukkitPlayer.getUniqueId());
        PlayerChannelResolver.unbindPlayerFromChannel(bukkitPlayer);
    }

    public void clearPluginFromAllPlayers(Plugin plugin) {
        for (HookedPlayerImpl player : players.values()) {
            player.resetPlugin(plugin);
        }
    }

    @Override
    public HookedPlayer getPlayer(Player player) {
        return player != null ? players.get(player.getUniqueId()) : null;
    }

    @Override
    public HookedPlayer getPlayer(UUID uuid) {
        return uuid != null ? players.get(uuid) : null;
    }

    @Override
    public Collection<HookedPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }
}
