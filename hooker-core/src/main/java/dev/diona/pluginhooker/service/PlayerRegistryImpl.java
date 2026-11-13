package dev.diona.pluginhooker.service;

import dev.diona.pluginhooker.PluginHookerPlugin;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe implementation of {@link PlayerRegistry}.
 */
@RequiredArgsConstructor
public final class PlayerRegistryImpl implements PlayerRegistry {

    private final Map<UUID, HookedPlayerImpl> players = new ConcurrentHashMap<>();
    private final Map<String, HookedPlayerImpl> playersByLowerName = new ConcurrentHashMap<>();
    private final AtomicInteger disabledOverridePlayerCount = new AtomicInteger();
    private final PluginRegistryImpl pluginRegistry;
    private final HookConfig config;

    @Override
    public HookedPlayerImpl registerPlayer(Player bukkitPlayer) {
        if (PlayerChannelResolver.isInvalidOrTemporaryPlayer(bukkitPlayer)) {
            return null;
        }
        Channel channel = PlayerChannelResolver.getChannel(bukkitPlayer);
        if (channel != null && !PlayerChannelResolver.isOpenRealNetworkChannel(channel)) {
            return null;
        }
        boolean realChannel = channel != null;
        long graceMillis = realChannel ? config.getJoinGraceMillis() : 0L;
        UUID uuid = bukkitPlayer.getUniqueId();
        HookedPlayerImpl hookedPlayer = players.compute(uuid, (id, existing) -> {
            if (existing != null) {
                if (existing.getBukkitPlayer() == bukkitPlayer) {
                    existing.updateState(bukkitPlayer, graceMillis);
                    return existing;
                }
                existing.markUnregistered();
            }
            return new HookedPlayerImpl(bukkitPlayer, pluginRegistry, this, graceMillis);
        });
        String name = bukkitPlayer.getName();
        if (name != null && !name.isEmpty()) {
            playersByLowerName.put(name.toLowerCase(Locale.ROOT), hookedPlayer);
        }
        if (realChannel) {
            PlayerChannelResolver.bindPlayerToChannel(
                    channel,
                    hookedPlayer,
                    (closedChannel, closedPlayer) -> scheduleChannelCloseCleanup(closedChannel, uuid, closedPlayer)
            );
        }
        return hookedPlayer;
    }

    private void scheduleChannelCloseCleanup(Channel closedChannel, UUID uuid, HookedPlayer closedPlayer) {
        PluginHookerPlugin selfPlugin = PluginHookerPlugin.getInstance();
        if (selfPlugin != null && Bukkit.getServer() != null && selfPlugin.isEnabled()) {
            try {
                // Defer fallback cleanup past the next main-thread tick so disconnect teardown events
                // (InventoryCloseEvent, VehicleExitEvent, PlayerQuitEvent) still observe the player's
                // HookedPlayer state before PluginLifecycleListener#onPlayerQuit unregisters at MONITOR.
                Bukkit.getScheduler().runTaskLater(
                        selfPlugin,
                        () -> cleanupClosedChannel(closedChannel, uuid, closedPlayer),
                        20L
                );
                return;
            } catch (RuntimeException ignored) {
                // Fall through to immediate cleanup if plugin/server is shutting down
            }
        }
        cleanupClosedChannel(closedChannel, uuid, closedPlayer);
    }

    private void cleanupClosedChannel(Channel closedChannel, UUID uuid, HookedPlayer closedPlayer) {
        PlayerChannelResolver.unbindPlayerFromChannel(closedChannel, closedPlayer);
        if (players.remove(uuid, closedPlayer) && closedPlayer instanceof HookedPlayerImpl impl) {
            impl.markUnregistered();
        }
        String closedName = closedPlayer.getName();
        if (closedName != null) {
            playersByLowerName.remove(closedName.toLowerCase(Locale.ROOT), closedPlayer);
        }
    }

    @Override
    public void unregisterPlayer(Player bukkitPlayer) {
        if (PlayerChannelResolver.isInvalidOrTemporaryPlayer(bukkitPlayer)) {
            return;
        }
        HookedPlayerImpl[] removedRef = new HookedPlayerImpl[1];
        players.computeIfPresent(bukkitPlayer.getUniqueId(), (id, existing) -> {
            if (existing.getBukkitPlayer() == bukkitPlayer) {
                existing.markUnregistered();
                removedRef[0] = existing;
                return null;
            }
            return existing;
        });
        HookedPlayerImpl removed = removedRef[0];
        if (removed != null) {
            String name = bukkitPlayer.getName();
            if (name != null) {
                playersByLowerName.remove(name.toLowerCase(Locale.ROOT), removed);
            }
        }
        Channel channel = PlayerChannelResolver.getChannel(bukkitPlayer);
        if (channel != null) {
            if (removed != null) {
                PlayerChannelResolver.unbindPlayerFromChannel(channel, removed);
            } else {
                HookedPlayer channelBound = PlayerChannelResolver.getHookedPlayer(channel);
                if (channelBound != null && channelBound.getBukkitPlayer() == bukkitPlayer) {
                    PlayerChannelResolver.unbindPlayerFromChannel(channel, channelBound);
                }
            }
        }
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

    void adjustDisabledOverridePlayers(int delta) {
        disabledOverridePlayerCount.addAndGet(delta);
    }

    public boolean hasPlayersWithDisabledOverrides() {
        return disabledOverridePlayerCount.get() > 0;
    }

    /**
     * O(1) fast check returning whether at least one tracked online player currently has a disabled plugin.
     */
    public boolean hasAnyPlayerWithDisabledPlugin() {
        if (disabledOverridePlayerCount.get() > 0) {
            return pluginRegistry.hasHookedPlugins();
        }
        return !players.isEmpty() && pluginRegistry.hasWhitelistPlugins();
    }

    /**
     * O(1) fast check returning whether any hooked plugin can currently be blocked for a target player
     * (either via explicit per-player overrides or via {@link HookMode#WHITELIST}, including untracked NPC/bot players).
     */
    public boolean canBlockAnyPlayerAction() {
        if (pluginRegistry.hasWhitelistPlugins()) {
            return true;
        }
        return disabledOverridePlayerCount.get() > 0 && pluginRegistry.hasHookedPlugins();
    }

    /**
     * Returns true if the given {@link Plugin} (with known non-null {@link HookMode}) is allowed to interact
     * with the resolved {@link Player} / {@link HookedPlayer}.
     * <ul>
     *   <li>{@code bukkitPlayer == null} or temporary proxy: allowed ({@code true})</li>
     *   <li>Tracked {@link HookedPlayer}: delegates to {@link HookedPlayerImpl#isAllowed}</li>
     *   <li>Untracked valid {@link Player} (e.g. NPC / bot without a real network channel): blocked in {@link HookMode#WHITELIST}</li>
     * </ul>
     */
    public static boolean isAllowed(
            Player bukkitPlayer,
            HookedPlayer hookedPlayer,
            Plugin plugin,
            HookMode mode
    ) {
        if (hookedPlayer != null) {
            return HookedPlayerImpl.isAllowed(hookedPlayer, plugin, mode);
        }
        return mode != HookMode.WHITELIST || PlayerChannelResolver.isInvalidOrTemporaryPlayer(bukkitPlayer);
    }

    /**
     * Evaluates whether {@code plugin} is currently allowed to interact with {@code player}.
     */
    public boolean isAllowed(Plugin plugin, Player player) {
        if (plugin == null || player == null) {
            return true;
        }
        HookMode mode = pluginRegistry.getHookMode(plugin);
        if (mode == null) {
            return true;
        }
        return isAllowed(player, getPlayer(player), plugin, mode);
    }

    /**
     * Fast pre-check before walking the call stack: returns true if at least one hooked plugin is currently
     * disabled for the resolved {@link Player} / {@link HookedPlayer} target.
     */
    public boolean hasDisabledPlugins(Player bukkitPlayer, HookedPlayer hookedPlayer) {
        if (hookedPlayer != null) {
            return hookedPlayer.hasAnyDisabledPlugin();
        }
        return pluginRegistry.hasWhitelistPlugins()
                && !PlayerChannelResolver.isInvalidOrTemporaryPlayer(bukkitPlayer);
    }

    /**
     * Returns true if {@code plugin} is currently disabled for the resolved {@link Player} / {@link HookedPlayer}
     * target (supporting both tracked {@link HookedPlayer}s and untracked NPC/bot {@link Player}s in
     * {@link HookMode#WHITELIST}).
     */
    public boolean isPluginDisabled(Player bukkitPlayer, HookedPlayer hookedPlayer, Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        if (hookedPlayer != null) {
            return !hookedPlayer.isPluginEnabled(plugin);
        }
        return pluginRegistry.getHookMode(plugin) == HookMode.WHITELIST
                && !PlayerChannelResolver.isInvalidOrTemporaryPlayer(bukkitPlayer);
    }

    @Override
    public HookedPlayer getPlayer(Player player) {
        if (PlayerChannelResolver.isInvalidOrTemporaryPlayer(player)) {
            return null;
        }
        HookedPlayerImpl hookedPlayer = players.get(player.getUniqueId());
        if (hookedPlayer != null && hookedPlayer.getBukkitPlayer() == player) {
            return hookedPlayer;
        }
        Channel channel = PlayerChannelResolver.getChannel(player);
        HookedPlayer channelPlayer = PlayerChannelResolver.getHookedPlayer(channel);
        if (channelPlayer != null && channelPlayer.getBukkitPlayer() == player) {
            return channelPlayer;
        }
        if (PlayerChannelResolver.isOpenRealNetworkChannel(channel)) {
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
}
