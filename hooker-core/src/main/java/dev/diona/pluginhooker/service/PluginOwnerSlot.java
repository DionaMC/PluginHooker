package dev.diona.pluginhooker.service;

import dev.diona.pluginhooker.api.plugin.HookMode;
import org.bukkit.plugin.Plugin;

import java.lang.ref.WeakReference;

/**
 * Version-synced {@link ClassValue} cache slot tracking the owning {@link Plugin} and active {@link HookMode}
 * of a Netty {@code ChannelHandler} or PacketEvents listener class without per-packet map or WeakReference lookups.
 */
public final class PluginOwnerSlot {

    public record ActivePlugin(Plugin plugin, HookMode mode) {
        public static ActivePlugin of(Plugin plugin, PluginRegistryImpl registry) {
            if (plugin == null) {
                return null;
            }
            HookMode mode = registry.getHookMode(plugin);
            return mode != null ? new ActivePlugin(plugin, mode) : null;
        }
    }

    public static final PluginOwnerSlot EXCLUDED = new PluginOwnerSlot(true, false, null);
    public static final PluginOwnerSlot FALLBACK = new PluginOwnerSlot(false, true, null);

    private final boolean excluded;
    private final boolean fallback;
    private final WeakReference<Plugin> ownerRef;
    private volatile int syncedVersion;
    private volatile ActivePlugin active;

    private PluginOwnerSlot(boolean excluded, boolean fallback, WeakReference<Plugin> ownerRef) {
        this.excluded = excluded;
        this.fallback = fallback;
        this.ownerRef = ownerRef;
    }

    public static PluginOwnerSlot forOwner(Plugin owner) {
        return new PluginOwnerSlot(false, false, new WeakReference<>(owner));
    }

    public boolean isExcluded() {
        return excluded;
    }

    public boolean isFallback() {
        return fallback;
    }

    public ActivePlugin resolveActive(PluginRegistryImpl registry) {
        int currentVersion = registry.getVersion();
        if (syncedVersion == currentVersion) {
            return active;
        }
        Plugin owner = ownerRef.get();
        if (owner == null) {
            active = null;
            syncedVersion = currentVersion;
            return null;
        }
        ActivePlugin resolved = ActivePlugin.of(owner, registry);
        active = resolved;
        syncedVersion = currentVersion;
        return resolved;
    }
}
