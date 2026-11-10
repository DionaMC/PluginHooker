package dev.diona.pluginhooker.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reflection and fast-path utilities for inspecting Bukkit/Spigot/Paper plugin {@link ClassLoader}s
 * (including Spigot/Paper {@code libraryLoader}s and early {@code <init>}/{@code onLoad} plugin fields).
 */
public final class PluginClassLoaders {

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

    private PluginClassLoaders() {
    }

    public static boolean isNonPluginLoader(ClassLoader loader) {
        return loader == null
                || loader == SERVER_CLASS_LOADER
                || loader == SYSTEM_CLASS_LOADER
                || loader == PLATFORM_CLASS_LOADER;
    }

    public static boolean hasPluginAccessor(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        LoaderAccessors accessors = LOADER_ACCESSORS.get(loader.getClass());
        return accessors.primaryPluginGetter() != null || accessors.fallbackPluginGetter() != null;
    }

    public static ClassLoader extractLibraryLoader(ClassLoader pluginClassLoader) {
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

    public static Plugin extractPluginFromLoader(ClassLoader loader) {
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

    public static void scanActivePlugins(Consumer<Plugin> consumer) {
        if (Bukkit.getServer() == null) {
            return;
        }
        try {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            if (plugins != null && plugins.length > 0) {
                for (Plugin plugin : plugins) {
                    if (plugin != null) {
                        consumer.accept(plugin);
                    }
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
                    if (field.get(pm) instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof Plugin plugin) {
                                consumer.accept(plugin);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
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
