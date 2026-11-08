package dev.diona.pluginhooker.service;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import io.netty.channel.Channel;
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

    @Override
    public HookedPlayerImpl registerPlayer(Player bukkitPlayer) {
        if (isInvalidOrTemporaryPlayer(bukkitPlayer)) {
            return null;
        }
        Channel channel = PlayerChannelResolver.getChannel(bukkitPlayer);
        boolean realChannel = PlayerChannelResolver.isRealNetworkChannel(channel);
        long graceMillis = realChannel ? config.getJoinGraceMillis() : 0L;
        UUID uuid = bukkitPlayer.getUniqueId();
        HookedPlayerImpl hookedPlayer = players.compute(uuid, (id, existing) -> {
            if (existing != null && existing.getBukkitPlayer() == bukkitPlayer) {
                existing.updateState(bukkitPlayer, graceMillis);
                return existing;
            }
            return new HookedPlayerImpl(bukkitPlayer, pluginRegistry, graceMillis);
        });
        if (realChannel) {
            PlayerChannelResolver.bindPlayerToChannel(
                    channel,
                    hookedPlayer,
                    () -> players.remove(uuid, hookedPlayer)
            );
        }
        return hookedPlayer;
    }

    @Override
    public void unregisterPlayer(Player bukkitPlayer) {
        if (isInvalidOrTemporaryPlayer(bukkitPlayer)) {
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
        if (isInvalidOrTemporaryPlayer(player)) {
            return null;
        }
        HookedPlayerImpl hookedPlayer = players.get(player.getUniqueId());
        if (hookedPlayer != null) {
            return hookedPlayer;
        }
        Channel channel = PlayerChannelResolver.getChannel(player);
        if (!PlayerChannelResolver.isRealNetworkChannel(channel) || channel.isOpen()) {
            return registerPlayer(player);
        }
        return null;
    }

    @Override
    public HookedPlayer getPlayer(UUID uuid) {
        return uuid != null ? players.get(uuid) : null;
    }

    @Override
    public Collection<HookedPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }

    private static boolean isInvalidOrTemporaryPlayer(Player player) {
        return player == null || player.getClass().getName().startsWith("com.comphenix.protocol.injector.");
    }
}
