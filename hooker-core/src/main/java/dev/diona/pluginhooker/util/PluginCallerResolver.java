package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import io.netty.channel.ChannelHandler;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Resolves the calling Bukkit {@link Plugin} from the current call stack using JDK {@link StackWalker}.
 */
public final class PluginCallerResolver {

    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final StackWalker DIRECT_STACK_WALKER =
            StackWalker.getInstance(Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE), 6);

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
        return DIRECT_STACK_WALKER.walk(frames -> {
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
        return DIRECT_STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                Plugin owner = registry.getPluginByClassLoader(loader);
                if (owner != null) {
                    if (isSharedPacketFramework(owner)) {
                        return null;
                    }
                    return registry.isHooked(owner) ? owner : null;
                }
                String className = callerClass.getName();
                if (className.equals("org.bukkit.plugin.RegisteredListener")
                        || className.equals("org.bukkit.plugin.SimplePluginManager")
                        || className.equals("io.papermc.paper.plugin.manager.PaperEventManager")) {
                    return null;
                }
                if (className.startsWith("org.bukkit.")
                        || className.startsWith("io.papermc.")
                        || className.startsWith("com.destroystokyo.")
                        || className.startsWith("net.kyori.adventure.")
                        || className.startsWith("net.md_5.bungee.api.chat.")
                        || className.startsWith("dev.diona.pluginhooker.")) {
                    continue;
                }
                return null;
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
        return DIRECT_STACK_WALKER.walk(frames -> {
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
                return resolveHookedPluginFromLoader(registry, loader);
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
        return DIRECT_STACK_WALKER.walk(frames -> {
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
                return resolveHookedPluginFromLoader(registry, loader);
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
    public static Plugin resolveDirectByteBufWriteCaller(
            PluginRegistry registry,
            ClassLoader selfLoader,
            boolean fromTailContext
    ) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return DIRECT_STACK_WALKER.walk(frames -> {
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
                if (!fromTailContext) {
                    // When invoked on a mid-pipeline ChannelHandlerContext (ctx.write/writeAndFlush) from inside a
                    // ChannelHandler or codec method, this is pipeline forwarding rather than an active send.
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
                }
                return resolveHookedPluginFromLoader(registry, loader);
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
        return DIRECT_STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            boolean seenHookedEntry = false;
            Plugin candidate = null;
            int postCandidateDepth = 0;

            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                Class<?> callerClass = frame.getDeclaringClass();
                String className = callerClass.getName();
                String methodName = frame.getMethodName();

                if (className.startsWith("dev.diona.pluginhooker.")) {
                    continue;
                }

                if (candidate == null) {
                    if (className.equals("io.netty.channel.AbstractChannelHandlerContext")
                            && (methodName.equals("close") || methodName.equals("disconnect"))) {
                        if (frame.getDescriptor().startsWith("()")) {
                            // Unhooked 0-arg close()/disconnect() delegating into 1-arg close(ChannelPromise)
                            continue;
                        }
                        if (!seenHookedEntry) {
                            seenHookedEntry = true;
                            continue;
                        }
                        // Already checked by an outer 1-arg disconnect(ChannelPromise) / close(ChannelPromise)
                        return null;
                    }
                    if (className.startsWith("io.netty.")) {
                        if (methodName.equals("invokeClose") || methodName.equals("invokeDisconnect")) {
                            return null;
                        }
                        continue;
                    }
                    ClassLoader loader = callerClass.getClassLoader();
                    if (loader == null || loader == selfLoader) {
                        return null;
                    }
                    String simpleName = callerClass.getSimpleName();
                    if (simpleName.equals("ChannelOperatorImpl")
                            || simpleName.equals("NettyChannelOperator")
                            || simpleName.equals("ChannelOperator")
                            || simpleName.equals("ChannelHelper")
                            || simpleName.equals("User")) {
                        continue;
                    }
                    if (ChannelHandler.class.isAssignableFrom(callerClass)
                            && (methodName.equals("close") || methodName.equals("disconnect"))) {
                        return null;
                    }
                    candidate = resolveHookedPluginFromLoader(registry, loader);
                    if (candidate == null) {
                        return null;
                    }
                } else {
                    // Ensure the hooked plugin frame was not merely forwarding an already-active pipeline
                    // close/disconnect (e.g. via a helper method inside ChannelOutboundHandler#close)
                    if (className.equals("io.netty.channel.AbstractChannelHandlerContext")
                            && (methodName.equals("close")
                                || methodName.equals("invokeClose")
                                || methodName.equals("disconnect")
                                || methodName.equals("invokeDisconnect"))) {
                        return null;
                    }
                    if (++postCandidateDepth >= 8) {
                        break;
                    }
                }
            }
            return candidate;
        });
    }

    private static Plugin resolveHookedPluginFromLoader(PluginRegistry registry, ClassLoader loader) {
        Plugin plugin = registry.getPluginByClassLoader(loader);
        if (plugin == null || isSharedPacketFramework(plugin)) {
            return null;
        }
        return registry.isHooked(plugin) ? plugin : null;
    }
}
