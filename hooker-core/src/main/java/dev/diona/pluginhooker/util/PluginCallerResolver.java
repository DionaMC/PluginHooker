package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import org.bukkit.plugin.Plugin;

/**
 * Resolves the calling Bukkit {@link Plugin} from the current call stack using JDK {@link StackWalker}.
 */
public final class PluginCallerResolver {

    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private PluginCallerResolver() {
    }

    /**
     * Walks the call stack to find the nearest calling {@link Plugin} (excluding PluginHooker itself).
     *
     * @param registry   the plugin registry providing O(1) ClassLoader -> Plugin lookup
     * @param selfLoader PluginHooker's own ClassLoader to ignore
     * @return the calling plugin, or null if called by the server or an unknown source
     */
    public static Plugin resolveCallerPlugin(PluginRegistry registry, ClassLoader selfLoader) {
        return STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                Plugin plugin = registry.getPluginByClassLoader(loader);
                if (plugin != null) {
                    return plugin;
                }
            }
            return null;
        });
    }

    /**
     * Fast-path resolver that returns the calling {@link Plugin} only if it is currently hooked.
     *
     * @param registry   the plugin registry
     * @param selfLoader PluginHooker's own ClassLoader
     * @return the hooked calling plugin, or null if none or not hooked
     */
    public static Plugin resolveHookedCaller(PluginRegistry registry, ClassLoader selfLoader) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        Plugin caller = resolveCallerPlugin(registry, selfLoader);
        return (caller != null && registry.isHooked(caller)) ? caller : null;
    }
}
