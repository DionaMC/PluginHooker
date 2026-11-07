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

    public HookedPlayerImpl registerLogin(Player bukkitPlayer) {
        if (isInvalidOrTemporaryPlayer(bukkitPlayer)) {
            return null;
        }
        UUID uuid = bukkitPlayer.getUniqueId();
        HookedPlayerImpl hookedPlayer = players.computeIfAbsent(
                uuid,
                id -> new HookedPlayerImpl(
                        bukkitPlayer,
                        pluginRegistry,
                        config.getJoinGraceMillis(),
                        false
                )
        );
        hookedPlayer.attachBukkitPlayer(bukkitPlayer);
        hookedPlayer.refreshJoinGrace(config.getJoinGraceMillis());
        bindChannelWithAutoCleanup(bukkitPlayer, hookedPlayer);
        return hookedPlayer;
    }

    @Override
    public HookedPlayerImpl registerPlayer(Player bukkitPlayer) {
        if (isInvalidOrTemporaryPlayer(bukkitPlayer)) {
            return null;
        }
        UUID uuid = bukkitPlayer.getUniqueId();
        HookedPlayerImpl hookedPlayer = players.computeIfAbsent(
                uuid,
                id -> new HookedPlayerImpl(
                        bukkitPlayer,
                        pluginRegistry,
                        config.getJoinGraceMillis(),
                        true
                )
        );
        hookedPlayer.attachBukkitPlayer(bukkitPlayer);
        hookedPlayer.markJoined(config.getJoinGraceMillis());
        bindChannelWithAutoCleanup(bukkitPlayer, hookedPlayer);
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

    private void bindChannelWithAutoCleanup(Player bukkitPlayer, HookedPlayerImpl hookedPlayer) {
        Channel channel = PlayerChannelResolver.getChannel(bukkitPlayer);
        if (channel == null) {
            return;
        }
        HookedPlayer previous = channel.attr(PlayerChannelResolver.HOOKED_PLAYER_KEY).getAndSet(hookedPlayer);
        if (previous == null) {
            UUID uuid = hookedPlayer.getUniqueId();
            channel.closeFuture().addListener(future -> players.remove(uuid, hookedPlayer));
        }
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
        return players.get(player.getUniqueId());
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
