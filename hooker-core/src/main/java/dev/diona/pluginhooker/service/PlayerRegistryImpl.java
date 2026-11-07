package dev.diona.pluginhooker.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
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

    public HookedPlayerImpl registerPreLogin(UUID uuid, String name) {
        if (uuid == null) {
            return null;
        }
        return players.computeIfAbsent(
                uuid,
                id -> new HookedPlayerImpl(
                        id,
                        name,
                        null,
                        pluginRegistry,
                        config.getJoinGraceMillis()
                )
        );
    }

    @Override
    public HookedPlayerImpl registerPlayer(Player bukkitPlayer) {
        if (bukkitPlayer == null) {
            return null;
        }
        HookedPlayerImpl hookedPlayer = players.computeIfAbsent(
                bukkitPlayer.getUniqueId(),
                uuid -> new HookedPlayerImpl(
                        bukkitPlayer,
                        pluginRegistry,
                        config.getJoinGraceMillis()
                )
        );
        hookedPlayer.attachBukkitPlayer(bukkitPlayer);
        hookedPlayer.refreshJoinGrace(config.getJoinGraceMillis());
        PlayerChannelResolver.bindPlayerToChannel(bukkitPlayer, hookedPlayer);
        syncPacketEventsChannel(bukkitPlayer);
        return hookedPlayer;
    }

    @Override
    public void unregisterPlayer(Player bukkitPlayer) {
        if (bukkitPlayer == null) {
            return;
        }
        players.remove(bukkitPlayer.getUniqueId());
        PlayerChannelResolver.unbindPlayerFromChannel(bukkitPlayer);
        cleanupPacketEventsChannel(bukkitPlayer.getUniqueId());
    }

    public void unregisterPlayer(UUID uuid) {
        if (uuid != null) {
            players.remove(uuid);
            cleanupPacketEventsChannel(uuid);
        }
    }

    /**
     * Synchronizes the player's live Netty {@link Channel} and {@link Player} reference with standalone PacketEvents
     * if present, preventing stale closed channel references in {@code ProtocolManager.CHANNELS} after an early
     * uninjected disconnect.
     */
    private static void syncPacketEventsChannel(Player bukkitPlayer) {
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            return;
        }
        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null) {
                return;
            }
            Channel liveChannel = PlayerChannelResolver.getChannel(bukkitPlayer);
            if (liveChannel == null || !liveChannel.isOpen()) {
                return;
            }
            ProtocolManager pm = api.getProtocolManager();
            UUID uuid = bukkitPlayer.getUniqueId();
            Object existingChannel = pm.getChannel(uuid);
            if (existingChannel != liveChannel) {
                pm.setChannel(uuid, liveChannel);
            }
            User user = pm.getUser(liveChannel);
            if (user == null) {
                try {
                    Class<?> initClass = Class.forName(
                            "io.github.retrooper.packetevents.injector.connection.ServerConnectionInitializer",
                            true,
                            api.getClass().getClassLoader()
                    );
                    Method initChannel = initClass.getMethod("initChannel", Object.class, ConnectionState.class);
                    initChannel.invoke(null, liveChannel, ConnectionState.PLAY);
                    user = pm.getUser(liveChannel);
                } catch (Throwable ignored) {
                }
            }
            if (user != null) {
                api.getInjector().setPlayer(liveChannel, bukkitPlayer);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cleanupPacketEventsChannel(UUID uuid) {
        if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            return;
        }
        try {
            PacketEventsAPI<?> api = PacketEvents.getAPI();
            if (api == null) {
                return;
            }
            ProtocolManager pm = api.getProtocolManager();
            Object existingChannel = pm.getChannel(uuid);
            if (existingChannel instanceof Channel ch && (!ch.isOpen() || pm.getUser(ch) == null)) {
                ProtocolManager.CHANNELS.remove(uuid);
            }
        } catch (Throwable ignored) {
        }
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
