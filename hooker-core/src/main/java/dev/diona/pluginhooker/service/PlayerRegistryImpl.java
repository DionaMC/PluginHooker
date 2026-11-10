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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe implementation of {@link PlayerRegistry}.
 */
@RequiredArgsConstructor
public final class PlayerRegistryImpl implements PlayerRegistry {

    private final Map<UUID, HookedPlayerImpl> players = new ConcurrentHashMap<>();
    private final Map<String, HookedPlayerImpl> playersByLowerName = new ConcurrentHashMap<>();
    private final PluginRegistryImpl pluginRegistry;
    private final HookConfig config;

    @Override
    public HookedPlayerImpl registerPlayer(Player bukkitPlayer) {
        if (PlayerChannelResolver.isInvalidOrTemporaryPlayer(bukkitPlayer)) {
            return null;
        }
        Channel channel = PlayerChannelResolver.getChannel(bukkitPlayer);
        boolean realChannel = PlayerChannelResolver.isRealNetworkChannel(channel);
        if (realChannel && !channel.isOpen()) {
            return null;
        }
        long graceMillis = realChannel ? config.getJoinGraceMillis() : 0L;
        UUID uuid = bukkitPlayer.getUniqueId();
        HookedPlayerImpl hookedPlayer = players.compute(uuid, (id, existing) -> {
            if (existing != null && existing.getBukkitPlayer() == bukkitPlayer) {
                existing.updateState(bukkitPlayer, graceMillis);
                return existing;
            }
            return new HookedPlayerImpl(bukkitPlayer, pluginRegistry, graceMillis);
        });
        String name = bukkitPlayer.getName();
        if (name != null && !name.isEmpty()) {
            playersByLowerName.put(name.toLowerCase(Locale.ROOT), hookedPlayer);
        }
        if (realChannel) {
            PlayerChannelResolver.bindPlayerToChannel(
                    channel,
                    hookedPlayer,
                    closedPlayer -> {
                        players.remove(uuid, closedPlayer);
                        String closedName = closedPlayer.getName();
                        if (closedName != null) {
                            playersByLowerName.remove(closedName.toLowerCase(Locale.ROOT), closedPlayer);
                        }
                    }
            );
        }
        return hookedPlayer;
    }

    @Override
    public void unregisterPlayer(Player bukkitPlayer) {
        if (PlayerChannelResolver.isInvalidOrTemporaryPlayer(bukkitPlayer)) {
            return;
        }
        HookedPlayerImpl removed = players.remove(bukkitPlayer.getUniqueId());
        String name = bukkitPlayer.getName();
        if (name != null) {
            if (removed != null) {
                playersByLowerName.remove(name.toLowerCase(Locale.ROOT), removed);
            } else {
                playersByLowerName.remove(name.toLowerCase(Locale.ROOT));
            }
        }
        PlayerChannelResolver.unbindPlayerFromChannel(bukkitPlayer);
    }

    public HookedPlayer getPlayerByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return playersByLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    public void clearPluginFromAllPlayers(Plugin plugin) {
        for (HookedPlayerImpl player : players.values()) {
            player.resetPlugin(plugin);
        }
    }

    /**
     * Fast check returning whether at least one tracked online player currently has a disabled plugin.
     */
    public boolean hasAnyPlayerWithDisabledPlugin() {
        if (players.isEmpty() || !pluginRegistry.hasHookedPlugins()) {
            return false;
        }
        if (pluginRegistry.hasWhitelistPlugins()) {
            return true;
        }
        for (HookedPlayerImpl player : players.values()) {
            if (player.hasExplicitlyDisabledPlugins()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public HookedPlayer getPlayer(Player player) {
        if (PlayerChannelResolver.isInvalidOrTemporaryPlayer(player)) {
            return null;
        }
        HookedPlayerImpl hookedPlayer = players.get(player.getUniqueId());
        if (hookedPlayer != null) {
            return hookedPlayer;
        }
        Channel channel = PlayerChannelResolver.getChannel(player);
        if (PlayerChannelResolver.isRealNetworkChannel(channel) && channel.isOpen()) {
            return registerPlayer(player);
        }
        return null;
    }

    @Override
    public HookedPlayer getPlayer(UUID uuid) {
        return uuid != null ? players.get(uuid) : null;
    }

    /**
     * Fast-path lookup that checks the UUID map first before falling back to channel validation/registration.
     */
    public HookedPlayer getPlayerFast(Player player) {
        if (player == null) {
            return null;
        }
        HookedPlayerImpl cached = players.get(player.getUniqueId());
        return cached != null ? cached : getPlayer(player);
    }

    @Override
    public Collection<HookedPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }
}
