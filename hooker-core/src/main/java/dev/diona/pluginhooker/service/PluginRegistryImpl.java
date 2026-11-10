package dev.diona.pluginhooker.service;

import com.google.common.collect.MapMaker;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe implementation of {@link PluginRegistry}.
 */
@RequiredArgsConstructor
public final class PluginRegistryImpl implements PluginRegistry {

    private static final ClassLoader PLATFORM_CLASS_LOADER = ClassLoader.getPlatformClassLoader();
    private static final ClassLoader SYSTEM_CLASS_LOADER = ClassLoader.getSystemClassLoader();
    private static final ClassLoader SERVER_CLASS_LOADER = Bukkit.class.getClassLoader();

    private record LoaderAccessors(
            MethodHandle libraryLoaderGetter,
            MethodHandle primaryPluginGetter,
            MethodHandle fallbackPluginGetter
    ) {
    }

    private static final ClassValue<LoaderAccessors> LOADER_ACCESSORS = new ClassValue<>() {
        @Override
        protected LoaderAccessors computeValue(Class<?> loaderClass) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle libGetter = findLibraryLoaderGetter(lookup, loaderClass);
            MethodHandle primaryPlugin = findPublicPluginMethod(lookup, loaderClass);
            MethodHandle fallbackPlugin = null;

            for (Class<?> c = loaderClass; c != null && c != ClassLoader.class && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !Plugin.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        MethodHandle mh = lookup.unreflectGetter(f)
                                .asType(MethodType.methodType(Plugin.class, Object.class));
                        if (primaryPlugin == null) {
                            primaryPlugin = mh;
                        } else if (fallbackPlugin == null) {
                            fallbackPlugin = mh;
                        }
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
            return new LoaderAccessors(libGetter, primaryPlugin, fallbackPlugin);
        }
    };

    private final HookConfig config;
    private final Map<Plugin, HookMode> hookedPlugins = new ConcurrentHashMap<>();
    private final Set<Plugin> unmodifiableHookedView = Collections.unmodifiableSet(hookedPlugins.keySet());
    private final ConcurrentMap<ClassLoader, Plugin> classLoaderToPlugin =
            new MapMaker().weakKeys().makeMap();
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

    public boolean hasWhitelistPlugins() {
        return hasWhitelistPlugins;
    }

    private void updateWhitelistFlag() {
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
        if (isNonPluginLoader(classLoader)) {
            return null;
        }
        Plugin cached = classLoaderToPlugin.get(classLoader);
        if (cached != null) {
            return cached;
        }
        return resolveUncachedClassLoader(classLoader);
    }

    public void refreshClassLoaderCache() {
        if (Bukkit.getServer() == null) {
            return;
        }
        try {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            if (plugins != null && plugins.length > 0) {
                for (Plugin plugin : plugins) {
                    registerPluginLoaders(plugin);
                }
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
                                registerPluginLoaders(plugin);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void registerPluginLoaders(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        ClassLoader mainLoader = plugin.getClass().getClassLoader();
        if (isNonPluginLoader(mainLoader)) {
            return;
        }
        classLoaderToPlugin.put(mainLoader, plugin);
        ClassLoader libLoader = extractLibraryLoader(mainLoader);
        if (!isNonPluginLoader(libLoader) && libLoader != mainLoader) {
            classLoaderToPlugin.put(libLoader, plugin);
        }
    }

    private Plugin resolveUncachedClassLoader(ClassLoader classLoader) {
        Plugin direct = extractPluginFromLoader(classLoader);
        if (direct != null) {
            registerPluginLoaders(direct);
            return direct;
        }

        refreshClassLoaderCache();
        Plugin afterRefresh = classLoaderToPlugin.get(classLoader);
        if (afterRefresh != null) {
            return afterRefresh;
        }

        for (ClassLoader parent = classLoader.getParent(); !isNonPluginLoader(parent); parent = parent.getParent()) {
            Plugin parentOwner = classLoaderToPlugin.get(parent);
            if (parentOwner == null) {
                parentOwner = extractPluginFromLoader(parent);
                if (parentOwner != null) {
                    registerPluginLoaders(parentOwner);
                }
            }
            if (parentOwner != null) {
                classLoaderToPlugin.putIfAbsent(classLoader, parentOwner);
                return parentOwner;
            }
        }
        return null;
    }

    private static boolean isNonPluginLoader(ClassLoader loader) {
        return loader == null
                || loader == SERVER_CLASS_LOADER
                || loader == SYSTEM_CLASS_LOADER
                || loader == PLATFORM_CLASS_LOADER;
    }

    private static ClassLoader extractLibraryLoader(ClassLoader pluginClassLoader) {
        MethodHandle getter = LOADER_ACCESSORS.get(pluginClassLoader.getClass()).libraryLoaderGetter();
        if (getter == null) {
            return null;
        }
        try {
            return (ClassLoader) getter.invokeExact((Object) pluginClassLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Plugin extractPluginFromLoader(ClassLoader loader) {
        LoaderAccessors accessors = LOADER_ACCESSORS.get(loader.getClass());
        if (accessors.primaryPluginGetter() != null) {
            try {
                Plugin p = (Plugin) accessors.primaryPluginGetter().invokeExact((Object) loader);
                if (p != null) {
                    return p;
                }
            } catch (Throwable ignored) {
            }
        }
        if (accessors.fallbackPluginGetter() != null) {
            try {
                return (Plugin) accessors.fallbackPluginGetter().invokeExact((Object) loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static MethodHandle findLibraryLoaderGetter(MethodHandles.Lookup lookup, Class<?> loaderClass) {
        for (Class<?> c = loaderClass; c != null && c != ClassLoader.class && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("libraryLoader");
                if (!Modifier.isStatic(f.getModifiers()) && ClassLoader.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return lookup.unreflectGetter(f).asType(MethodType.methodType(ClassLoader.class, Object.class));
                }
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        return null;
    }

    private static MethodHandle findPublicPluginMethod(MethodHandles.Lookup lookup, Class<?> loaderClass) {
        try {
            Method m = loaderClass.getMethod("getPlugin");
            if (!Modifier.isStatic(m.getModifiers()) && Plugin.class.isAssignableFrom(m.getReturnType())) {
                m.setAccessible(true);
                return lookup.unreflect(m).asType(MethodType.methodType(Plugin.class, Object.class));
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }
}
