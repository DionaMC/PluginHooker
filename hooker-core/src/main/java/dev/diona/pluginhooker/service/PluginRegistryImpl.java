package dev.diona.pluginhooker.service;

import com.google.common.collect.MapMaker;
import dev.diona.pluginhooker.agent.BridgeInjector;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.util.PluginClassLoaders;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe implementation of {@link PluginRegistry}.
 */
@RequiredArgsConstructor
public final class PluginRegistryImpl implements PluginRegistry {

    private final HookConfig config;
    private final Map<Plugin, HookMode> hookedPlugins = new ConcurrentHashMap<>();
    private final Set<Plugin> unmodifiableHookedView = Collections.unmodifiableSet(hookedPlugins.keySet());
    private final ConcurrentMap<ClassLoader, Plugin> classLoaderToPlugin =
            new MapMaker().weakKeys().makeMap();
    private final Set<ClassLoader> knownNonPluginLoaders =
            Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());
    private final AtomicInteger version = new AtomicInteger(1);
    private volatile boolean hasWhitelistPlugins = false;
    @Setter
    private PlayerRegistryImpl playerRegistry;

    @Override
    public boolean registerPlugin(Plugin plugin) {
        return registerPlugin(plugin, config.getDefaultHookMode());
    }

    @Override
    public boolean registerPlugin(Plugin plugin, HookMode mode) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(mode, "mode");
        registerPluginLoaders(plugin);
        boolean added = hookedPlugins.put(plugin, mode) == null;
        updateWhitelistFlag();
        return added;
    }

    @Override
    public boolean unregisterPlugin(Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        boolean removed = hookedPlugins.remove(plugin) != null;
        if (removed) {
            updateWhitelistFlag();
            if (playerRegistry != null) {
                playerRegistry.clearPluginFromAllPlayers(plugin);
            }
        }
        return removed;
    }

    public void handlePluginDisable(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        classLoaderToPlugin.values().removeIf(mapped -> mapped == plugin);
        unregisterPlugin(plugin);
    }

    @Override
    public boolean isHooked(Plugin plugin) {
        return plugin != null && hookedPlugins.containsKey(plugin);
    }

    @Override
    public HookMode getHookMode(Plugin plugin) {
        return plugin != null ? hookedPlugins.get(plugin) : null;
    }

    @Override
    public void setHookMode(Plugin plugin, HookMode mode) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(mode, "mode");
        if (hookedPlugins.computeIfPresent(plugin, (k, old) -> mode) != null) {
            updateWhitelistFlag();
        }
    }

    public int getVersion() {
        return version.get();
    }

    public boolean hasWhitelistPlugins() {
        return hasWhitelistPlugins;
    }

    private void updateWhitelistFlag() {
        version.incrementAndGet();
        BridgeInjector.setHasHookedPlugins(!hookedPlugins.isEmpty());
        for (HookMode mode : hookedPlugins.values()) {
            if (mode == HookMode.WHITELIST) {
                hasWhitelistPlugins = true;
                return;
            }
        }
        hasWhitelistPlugins = false;
    }

    @Override
    public boolean hasHookedPlugins() {
        return !hookedPlugins.isEmpty();
    }

    @Override
    public Set<Plugin> getHookedPlugins() {
        return unmodifiableHookedView;
    }

    @Override
    public Plugin getPluginByClassLoader(ClassLoader classLoader) {
        if (PluginClassLoaders.isNonPluginLoader(classLoader)) {
            return null;
        }
        Plugin cached = classLoaderToPlugin.get(classLoader);
        if (cached != null) {
            return cached;
        }
        if (knownNonPluginLoaders.contains(classLoader)) {
            return null;
        }
        return resolveUncachedClassLoader(classLoader);
    }

    public void refreshClassLoaderCache() {
        knownNonPluginLoaders.clear();
        PluginClassLoaders.scanActivePlugins(this::registerPluginLoaders);
    }

    private void registerPluginLoaders(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        ClassLoader mainLoader = plugin.getClass().getClassLoader();
        if (PluginClassLoaders.isNonPluginLoader(mainLoader)) {
            return;
        }
        classLoaderToPlugin.put(mainLoader, plugin);
        knownNonPluginLoaders.remove(mainLoader);
        ClassLoader libLoader = PluginClassLoaders.extractLibraryLoader(mainLoader);
        if (!PluginClassLoaders.isNonPluginLoader(libLoader) && libLoader != mainLoader) {
            classLoaderToPlugin.put(libLoader, plugin);
            knownNonPluginLoaders.remove(libLoader);
        }
    }

    private Plugin resolveUncachedClassLoader(ClassLoader classLoader) {
        Plugin direct = PluginClassLoaders.extractPluginFromLoader(classLoader);
        if (direct != null) {
            registerPluginLoaders(direct);
            return direct;
        }

        PluginClassLoaders.scanActivePlugins(this::registerPluginLoaders);
        Plugin afterRefresh = classLoaderToPlugin.get(classLoader);
        if (afterRefresh != null) {
            return afterRefresh;
        }

        boolean hasPotentialPluginAccessor = PluginClassLoaders.hasPluginAccessor(classLoader);
        for (ClassLoader parent = classLoader.getParent();
             !PluginClassLoaders.isNonPluginLoader(parent);
             parent = parent.getParent()) {
            if (PluginClassLoaders.hasPluginAccessor(parent)) {
                hasPotentialPluginAccessor = true;
            }
            Plugin parentOwner = classLoaderToPlugin.get(parent);
            if (parentOwner == null) {
                parentOwner = PluginClassLoaders.extractPluginFromLoader(parent);
                if (parentOwner != null) {
                    registerPluginLoaders(parentOwner);
                }
            }
            if (parentOwner != null) {
                classLoaderToPlugin.putIfAbsent(classLoader, parentOwner);
                knownNonPluginLoaders.remove(classLoader);
                return parentOwner;
            }
        }
        if (!hasPotentialPluginAccessor) {
            knownNonPluginLoaders.add(classLoader);
        }
        return null;
    }
}
