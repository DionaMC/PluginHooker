package dev.diona.pluginhooker.service;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
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
    private final PluginRegistryImpl pluginRegistry;

    private final Set<Plugin> explicitlyEnabled = ConcurrentHashMap.newKeySet();
    private final Set<Plugin> explicitlyDisabled = ConcurrentHashMap.newKeySet();
    private volatile boolean hasEnabledOverrides;
    private volatile boolean hasDisabledOverrides;
    private volatile long joinGraceUntilMillis;

    public HookedPlayerImpl(Player bukkitPlayer, PluginRegistryImpl pluginRegistry, long joinGraceDurationMillis) {
        this.bukkitPlayer = bukkitPlayer;
        this.uniqueId = bukkitPlayer.getUniqueId();
        this.pluginRegistry = pluginRegistry;
        refreshJoinGrace(joinGraceDurationMillis);
    }

    @Override
    public String getName() {
        return bukkitPlayer.getName();
    }

    public void updateState(Player bukkitPlayer, long joinGraceDurationMillis) {
        if (bukkitPlayer != null) {
            this.bukkitPlayer = bukkitPlayer;
        }
        refreshJoinGrace(joinGraceDurationMillis);
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
        if (explicitlyDisabled.remove(plugin)) {
            hasDisabledOverrides = !explicitlyDisabled.isEmpty();
        }
        explicitlyEnabled.add(plugin);
        hasEnabledOverrides = true;
    }

    @Override
    public void disablePlugin(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        if (explicitlyEnabled.remove(plugin)) {
            hasEnabledOverrides = !explicitlyEnabled.isEmpty();
        }
        if (pluginRegistry.isHooked(plugin)) {
            explicitlyDisabled.add(plugin);
            hasDisabledOverrides = true;
        }
    }

    @Override
    public void resetPlugin(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        if (explicitlyEnabled.remove(plugin)) {
            hasEnabledOverrides = !explicitlyEnabled.isEmpty();
        }
        if (explicitlyDisabled.remove(plugin)) {
            hasDisabledOverrides = !explicitlyDisabled.isEmpty();
        }
    }

    @Override
    public boolean isPluginEnabled(Plugin plugin) {
        return isPluginEnabled(plugin, pluginRegistry.getHookMode(plugin));
    }

    public boolean isPluginEnabled(Plugin plugin, HookMode mode) {
        if (mode == null) {
            return true;
        }
        return switch (mode) {
            case WHITELIST -> hasEnabledOverrides && explicitlyEnabled.contains(plugin);
            case BLACKLIST -> !hasDisabledOverrides || !explicitlyDisabled.contains(plugin);
        };
    }

    public static boolean isAllowed(HookedPlayer player, Plugin plugin, HookMode mode) {
        if (player instanceof HookedPlayerImpl impl) {
            return impl.isPluginEnabled(plugin, mode);
        }
        return player.isPluginEnabled(plugin);
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

    public boolean hasExplicitlyDisabledPlugins() {
        return hasDisabledOverrides;
    }

    @Override
    public boolean hasAnyDisabledPlugin() {
        if (hasDisabledOverrides) {
            return true;
        }
        if (!pluginRegistry.hasWhitelistPlugins()) {
            return false;
        }
        if (!hasEnabledOverrides) {
            return true;
        }
        for (Plugin plugin : pluginRegistry.getHookedPlugins()) {
            if (!isPluginEnabled(plugin)) {
                return true;
            }
        }
        return false;
    }
}
