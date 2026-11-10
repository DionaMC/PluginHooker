package dev.diona.pluginhooker.service;

import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe implementation of {@link PluginRegistry}.
 */
@RequiredArgsConstructor
public final class PluginRegistryImpl implements PluginRegistry {

    private final HookConfig config;
    private final Map<Plugin, HookMode> hookedPlugins = new ConcurrentHashMap<>();
    private final Set<Plugin> unmodifiableHookedView = Collections.unmodifiableSet(hookedPlugins.keySet());
    private final Map<ClassLoader, Plugin> classLoaderToPlugin = new ConcurrentHashMap<>();
    private volatile boolean cacheInitialized = false;
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
        classLoaderToPlugin.put(plugin.getClass().getClassLoader(), plugin);
        return hookedPlugins.put(plugin, mode) == null;
    }

    @Override
    public boolean unregisterPlugin(Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        boolean removed = hookedPlugins.remove(plugin) != null;
        if (removed && playerRegistry != null) {
            playerRegistry.clearPluginFromAllPlayers(plugin);
        }
        return removed;
    }

    public void handlePluginDisable(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        classLoaderToPlugin.remove(plugin.getClass().getClassLoader(), plugin);
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
        hookedPlugins.computeIfPresent(plugin, (k, old) -> mode);
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
        if (classLoader == null) {
            return null;
        }
        Plugin cached = classLoaderToPlugin.get(classLoader);
        if (cached != null) {
            return cached;
        }
        if (!cacheInitialized) {
            refreshClassLoaderCache();
            return classLoaderToPlugin.get(classLoader);
        }
        return null;
    }

    public void refreshClassLoaderCache() {
        try {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            if (plugins != null && plugins.length > 0) {
                for (Plugin plugin : plugins) {
                    if (plugin != null) {
                        classLoaderToPlugin.putIfAbsent(plugin.getClass().getClassLoader(), plugin);
                    }
                }
                cacheInitialized = true;
                return;
            }
        } catch (Throwable ignored) {
        }

        // Fallback during early plugin construction before getPlugins() array is ready
        try {
            Object pm = Bukkit.getPluginManager();
            for (Field field : pm.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object listObj = field.get(pm);
                    if (listObj instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof Plugin plugin) {
                                classLoaderToPlugin.putIfAbsent(plugin.getClass().getClassLoader(), plugin);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
