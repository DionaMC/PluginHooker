package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import io.netty.channel.ChannelHandler;
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
     * Fast-path resolver that walks the call stack and returns the direct calling {@link Plugin}
     * if it is currently hooked in {@link PluginRegistry}.
     * Stops immediately if the caller is the Minecraft server, a Netty pipeline frame, a shared packet
     * framework, or an unhooked plugin so normal server/framework calls are never misattributed to a
     * hooked plugin frame deeper on the call stack.
     *
     * @param registry   the plugin registry
     * @param selfLoader PluginHooker's own ClassLoader
     * @return the hooked calling plugin, or null if the direct caller is not a hooked plugin
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
                String className = callerClass.getName();
                if (className.startsWith("org.bukkit.")
                        || className.startsWith("io.papermc.")
                        || className.startsWith("com.destroystokyo.")
                        || className.startsWith("dev.diona.pluginhooker.")) {
                    continue;
                }
                if (className.startsWith("io.netty.")) {
                    return null;
                }
                Plugin plugin = registry.getPluginByClassLoader(loader);
                if (plugin == null || isSharedPacketFramework(plugin)) {
                    return null;
                }
                return registry.isHooked(plugin) ? plugin : null;
            }
            return null;
        });
    }

    /**
     * Resolves the direct plugin caller of ProtocolLib's {@code PacketFilterManager#sendServerPacket},
     * {@code receiveClientPacket}, or {@code sendWirePacket}.
     * <p>
     * Only walks past {@code PacketFilterManager} overload delegations and {@code ScheduledPacket},
     * returning {@code null} immediately if the call originated from ProtocolLib's own internal machinery
     * (such as {@code AsyncMarker} or {@code PacketSendingQueue}).
     */
    public static Plugin resolveDirectProtocolLibCaller(PluginRegistry registry, ClassLoader selfLoader) {
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
                String className = callerClass.getName();
                if (className.startsWith("dev.diona.pluginhooker.")
                        || className.equals("com.comphenix.protocol.injector.PacketFilterManager")
                        || className.equals("com.comphenix.protocol.events.ScheduledPacket")) {
                    continue;
                }
                if (className.startsWith("com.comphenix.protocol.") || className.startsWith("io.netty.")) {
                    return null;
                }
                Plugin plugin = registry.getPluginByClassLoader(loader);
                if (plugin == null || isSharedPacketFramework(plugin)) {
                    return null;
                }
                return registry.isHooked(plugin) ? plugin : null;
            }
            return null;
        });
    }

    /**
     * Resolves the direct plugin caller of PacketEvents' {@code ProtocolManagerImpl#sendPacket},
     * {@code writePacket}, or {@code receivePacket} (for both standalone and shaded PacketEvents).
     */
    public static Plugin resolveDirectPacketEventsSendCaller(PluginRegistry registry, ClassLoader selfLoader) {
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
                String className = callerClass.getName();
                if (className.startsWith("dev.diona.pluginhooker.")) {
                    continue;
                }
                String simpleName = callerClass.getSimpleName();
                if (simpleName.equals("ProtocolManagerImpl")
                        || simpleName.equals("ProtocolManager")
                        || simpleName.equals("PlayerManagerImpl")
                        || simpleName.equals("PlayerManager")
                        || simpleName.equals("User")) {
                    continue;
                }
                if (className.startsWith("io.netty.") || ChannelHandler.class.isAssignableFrom(callerClass)) {
                    return null;
                }
                Plugin plugin = registry.getPluginByClassLoader(loader);
                if (plugin == null || isSharedPacketFramework(plugin)) {
                    return null;
                }
                return registry.isHooked(plugin) ? plugin : null;
            }
            return null;
        });
    }

    /**
     * Resolves the calling plugin for a direct {@code channel.write/writeAndFlush(ByteBuf)} or
     * {@code ctx.write/writeAndFlush(ByteBuf)} call.
     * <p>
     * If the 1-arg {@code write(Object)} or {@code writeAndFlush(Object)} was invoked from inside a
     * {@link ChannelHandler}'s own {@code channelRead} / {@code write} / {@code encode} / {@code decode}
     * pipeline processing (e.g. ViaVersion or PacketEvents compression/relocation), returns {@code null}
     * immediately so pipeline-internal forwarding is never blocked as an active packet send.
     */
    public static Plugin resolveDirectByteBufWriteCaller(PluginRegistry registry, ClassLoader selfLoader) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                Class<?> callerClass = frame.getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                String className = callerClass.getName();
                if (className.startsWith("io.netty.") || className.startsWith("dev.diona.pluginhooker.")) {
                    continue;
                }
                // If the direct caller of 1-arg write/writeAndFlush is a ChannelHandler (or inside a pipeline
                // encode/decode/write/channelRead callback), this is pipeline forwarding, not an active send!
                if (ChannelHandler.class.isAssignableFrom(callerClass)) {
                    return null;
                }
                String methodName = frame.getMethodName();
                if (methodName.equals("encode")
                        || methodName.equals("decode")
                        || methodName.equals("channelRead")
                        || methodName.equals("handleClientBoundPacket")
                        || methodName.equals("handleServerBoundPacket")
                        || methodName.equals("handleCompression")
                        || methodName.equals("compress")
                        || methodName.equals("decompress")) {
                    return null;
                }
                Plugin plugin = registry.getPluginByClassLoader(loader);
                if (plugin == null) {
                    return null;
                }
                if (isSharedPacketFramework(plugin)) {
                    // Standalone PacketEvents is already guarded at ProtocolManagerImpl; do not walk past it
                    // into Netty pipeline frames!
                    return null;
                }
                return registry.isHooked(plugin) ? plugin : null;
            }
            return null;
        });
    }

    /**
     * Resolves the calling plugin for a direct {@code channel.close()}/{@code disconnect()} or
     * {@code ctx.close()}/{@code disconnect()} call (including via PacketEvents' {@code User#closeConnection()}
     * or {@code ChannelHelper#close(Object)}).
     * <p>
     * Returns {@code null} if {@code close}/{@code disconnect} is merely being forwarded along the pipeline
     * by a {@link ChannelHandler}'s own {@code close}/{@code disconnect} callback or initiated by the server.
     */
    public static Plugin resolveDirectChannelCloseCaller(PluginRegistry registry, ClassLoader selfLoader) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                Class<?> callerClass = frame.getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                String className = callerClass.getName();
                if (className.startsWith("io.netty.") || className.startsWith("dev.diona.pluginhooker.")) {
                    continue;
                }
                String simpleName = callerClass.getSimpleName();
                if (simpleName.equals("ChannelOperatorImpl")
                        || simpleName.equals("NettyChannelOperator")
                        || simpleName.equals("ChannelOperator")
                        || simpleName.equals("ChannelHelper")
                        || simpleName.equals("User")) {
                    continue;
                }
                String methodName = frame.getMethodName();
                if (ChannelHandler.class.isAssignableFrom(callerClass)
                        && (methodName.equals("close") || methodName.equals("disconnect"))) {
                    return null;
                }
                Plugin plugin = registry.getPluginByClassLoader(loader);
                if (plugin == null || isSharedPacketFramework(plugin)) {
                    return null;
                }
                return registry.isHooked(plugin) ? plugin : null;
            }
            return null;
        });
    }
}
