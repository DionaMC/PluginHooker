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
     * Returns true if the given plugin is a shared packet framework (standalone PacketEvents or ProtocolLib)
     * rather than a downstream consumer plugin.
     */
    public static boolean isSharedPacketFramework(Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        String name = plugin.getName();
        return name.equalsIgnoreCase("packetevents") || name.equalsIgnoreCase("ProtocolLib");
    }

    /**
     * Walks the call stack to find the nearest calling {@link Plugin} (excluding PluginHooker itself,
     * standalone PacketEvents, and ProtocolLib).
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
                if (plugin != null && !isSharedPacketFramework(plugin)) {
                    return plugin;
                }
            }
            return null;
        });
    }

    /**
     * Resolves the direct plugin caller that invoked a Netty pipeline operation.
     * Stops immediately if the first non-Netty frame belongs to the Minecraft server rather than a plugin,
     * preventing server-internal pipeline modifications from being misattributed to plugins lower on the stack.
     */
    public static Plugin resolveDirectNettyCaller(PluginRegistry registry, ClassLoader selfLoader) {
        return STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                String className = callerClass.getName();
                if (className.startsWith("io.netty.") || className.startsWith("dev.diona.pluginhooker.")) {
                    continue;
                }
                // The first frame outside io.netty is the direct caller of pipeline.add*
                return registry.getPluginByClassLoader(loader);
            }
            return null;
        });
    }

    /**
     * Fast caller resolver for NMS {@code sendPacket}/{@code send}.
     * Stops immediately if the caller of {@code sendPacket} is another NMS/CraftBukkit server class,
     * avoiding full stack walks on normal server packet traffic.
     */
    public static Plugin resolveDirectNmsCaller(PluginRegistry registry, ClassLoader selfLoader, Class<?> nmsConnectionClass) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            boolean passedSendMethod = false;
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                if (callerClass == nmsConnectionClass || callerClass.isAssignableFrom(nmsConnectionClass)) {
                    passedSendMethod = true;
                    continue;
                }
                if (!passedSendMethod) {
                    continue;
                }
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    return null;
                }
                Plugin plugin = registry.getPluginByClassLoader(loader);
                if (plugin != null) {
                    return registry.isHooked(plugin) ? plugin : null;
                }
                // If the direct caller of sendPacket is a server class (loader not a plugin), stop immediately
                return null;
            }
            return null;
        });
    }

    /**
     * Fast-path resolver that walks the call stack and returns the first calling {@link Plugin}
     * that is currently hooked in {@link PluginRegistry}.
     *
     * @param registry   the plugin registry
     * @param selfLoader PluginHooker's own ClassLoader
     * @return the hooked calling plugin, or null if none is on the call stack
     */
    public static Plugin resolveHookedCaller(PluginRegistry registry, ClassLoader selfLoader) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                Plugin plugin = registry.getPluginByClassLoader(loader);
                if (plugin != null && registry.isHooked(plugin)) {
                    return plugin;
                }
            }
            return null;
        });
    }
}
