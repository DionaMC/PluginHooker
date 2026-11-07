package dev.diona.pluginhooker.service;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe per-player sandbox state implementation.
 */
public final class HookedPlayerImpl implements HookedPlayer {

    @Getter
    private volatile Player bukkitPlayer;
    @Getter
    private final UUID uniqueId;
    @Getter
    private volatile String name;
    private final PluginRegistry pluginRegistry;

    private final Set<Plugin> explicitlyEnabled = ConcurrentHashMap.newKeySet();
    private final Set<Plugin> explicitlyDisabled = ConcurrentHashMap.newKeySet();
    private volatile long joinGraceUntilMillis;

    public HookedPlayerImpl(Player bukkitPlayer, PluginRegistry pluginRegistry, long joinGraceDurationMillis) {
        this(bukkitPlayer.getUniqueId(), bukkitPlayer.getName(), bukkitPlayer, pluginRegistry, joinGraceDurationMillis);
    }

    public HookedPlayerImpl(UUID uniqueId, String name, Player bukkitPlayer, PluginRegistry pluginRegistry, long joinGraceDurationMillis) {
        this.uniqueId = uniqueId;
        this.name = name != null ? name : "";
        this.bukkitPlayer = bukkitPlayer;
        this.pluginRegistry = pluginRegistry;
        refreshJoinGrace(joinGraceDurationMillis);
    }

    public void attachBukkitPlayer(Player bukkitPlayer) {
        if (bukkitPlayer != null) {
            this.bukkitPlayer = bukkitPlayer;
            this.name = bukkitPlayer.getName();
        }
    }

    public void refreshJoinGrace(long joinGraceDurationMillis) {
        this.joinGraceUntilMillis = joinGraceDurationMillis > 0
                ? System.currentTimeMillis() + joinGraceDurationMillis
                : 0L;
    }

    @Override
    public void enablePlugin(Plugin plugin) {
        if (plugin == null || !pluginRegistry.isHooked(plugin)) {
            return;
        }
        explicitlyDisabled.remove(plugin);
        explicitlyEnabled.add(plugin);
    }

    @Override
    public void disablePlugin(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        explicitlyEnabled.remove(plugin);
        if (pluginRegistry.isHooked(plugin)) {
            explicitlyDisabled.add(plugin);
        }
    }

    @Override
    public void resetPlugin(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        explicitlyEnabled.remove(plugin);
        explicitlyDisabled.remove(plugin);
    }

    @Override
    public boolean isPluginAllowed(Plugin plugin) {
        if (plugin == null || !pluginRegistry.isHooked(plugin)) {
            return true;
        }
        if (isInJoinGracePeriod()) {
            return true;
        }
        return isPluginEnabled(plugin);
    }

    @Override
    public boolean isPluginEnabled(Plugin plugin) {
        HookMode mode = pluginRegistry.getHookMode(plugin);
        if (mode == null) {
            return true;
        }
        return switch (mode) {
            case WHITELIST -> explicitlyEnabled.contains(plugin);
            case BLACKLIST -> !explicitlyDisabled.contains(plugin);
        };
    }

    @Override
    public Set<Plugin> getEnabledPlugins() {
        return pluginRegistry.getHookedPlugins().stream()
                .filter(this::isPluginEnabled)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isInJoinGracePeriod() {
        return joinGraceUntilMillis > 0L && System.currentTimeMillis() < joinGraceUntilMillis;
    }
}
