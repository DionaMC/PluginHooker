package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import io.netty.channel.ChannelHandler;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Resolves the calling Bukkit {@link Plugin} from the current call stack using JDK 25 {@link StackWalker}
 * with {@link StackWalker.Option#DROP_METHOD_INFO} and precomputed {@link ClassValue} bitmasks so hot-path
 * stack walks perform zero string prefix/simple-name matching.
 */
public final class PluginCallerResolver {

    private static final StackWalker CLASS_STACK_WALKER = StackWalker.getInstance(
            Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.DROP_METHOD_INFO),
            8
    );
    private static final StackWalker METHOD_STACK_WALKER = StackWalker.getInstance(
            Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE),
            8
    );

    private static final int FLAG_SELF_PACKAGE = 1;
    private static final int FLAG_NETTY = 1 << 1;
    private static final int FLAG_ABSTRACT_HANDLER_CTX = 1 << 2;
    private static final int FLAG_CHANNEL_HANDLER = 1 << 3;
    private static final int FLAG_EVENT_BOUNDARY = 1 << 4;
    private static final int FLAG_API_WRAPPER = 1 << 5;
    private static final int FLAG_PROTOCOLLIB_DELEGATE = 1 << 6;
    private static final int FLAG_PROTOCOLLIB_INTERNAL = 1 << 7;
    private static final int FLAG_PE_SEND_DELEGATE = 1 << 8;
    private static final int FLAG_PE_CLOSE_DELEGATE = 1 << 9;
    private static final int FLAG_NETTY_CODEC_HANDLER = 1 << 10;
    private static final int FLAG_PE_INJECTOR = 1 << 11;

    private static final Set<String> PE_SEND_DELEGATE_SIMPLE_NAMES = Set.of(
            "ProtocolManagerImpl",
            "ProtocolManagerAbstract",
            "ProtocolManager",
            "PlayerManagerImpl",
            "PlayerManagerAbstract",
            "PlayerManager",
            "User"
    );

    private static final Set<String> PE_CLOSE_DELEGATE_SIMPLE_NAMES = Set.of(
            "ChannelOperatorImpl",
            "ChannelOperatorModernImpl",
            "NettyChannelOperator",
            "ChannelOperator",
            "ChannelHelper",
            "User"
    );

    private static final Set<String> CODEC_FORWARDING_METHODS = Set.of(
            "write",
            "encode",
            "decode",
            "channelRead",
            "handleClientBoundPacket",
            "handleServerBoundPacket",
            "handleCompression",
            "compress",
            "decompress"
    );

    private static final ClassValue<Integer> CLASS_FLAGS = new ClassValue<>() {
        @Override
        protected Integer computeValue(Class<?> clazz) {
            String name = clazz.getName();
            String simpleName = clazz.getSimpleName();
            int flags = 0;

            if (name.startsWith("dev.diona.pluginhooker.")) {
                flags |= FLAG_SELF_PACKAGE | FLAG_API_WRAPPER;
            }
            if (name.startsWith("io.netty.")) {
                flags |= FLAG_NETTY;
                if (name.equals("io.netty.channel.AbstractChannelHandlerContext")) {
                    flags |= FLAG_ABSTRACT_HANDLER_CTX;
                }
                if (ChannelHandler.class.isAssignableFrom(clazz)
                        && !name.startsWith("io.netty.channel.DefaultChannelPipeline$")) {
                    flags |= FLAG_NETTY_CODEC_HANDLER;
                }
            }
            if (ChannelHandler.class.isAssignableFrom(clazz)) {
                flags |= FLAG_CHANNEL_HANDLER;
            }
            if (name.equals("org.bukkit.plugin.RegisteredListener")
                    || name.equals("org.bukkit.plugin.SimplePluginManager")
                    || name.equals("io.papermc.paper.plugin.manager.PaperEventManager")) {
                flags |= FLAG_EVENT_BOUNDARY;
            }
            if (name.startsWith("org.bukkit.")
                    || name.startsWith("io.papermc.")
                    || name.startsWith("com.destroystokyo.")
                    || name.startsWith("net.kyori.adventure.")
                    || name.startsWith("net.md_5.bungee.api.chat.")) {
                flags |= FLAG_API_WRAPPER;
            }
            if (name.startsWith("com.comphenix.protocol.")) {
                flags |= FLAG_PROTOCOLLIB_INTERNAL;
                if (name.equals("com.comphenix.protocol.injector.PacketFilterManager")
                        || name.equals("com.comphenix.protocol.events.ScheduledPacket")) {
                    flags |= FLAG_PROTOCOLLIB_DELEGATE;
                }
            }
            if (PE_SEND_DELEGATE_SIMPLE_NAMES.contains(simpleName)
                    || name.contains(".packetevents.manager.protocol.")
                    || name.contains(".packetevents.manager.player.")) {
                flags |= FLAG_PE_SEND_DELEGATE;
            }
            if (PE_CLOSE_DELEGATE_SIMPLE_NAMES.contains(simpleName)
                    || name.contains(".packetevents.netty.channel.")) {
                flags |= FLAG_PE_CLOSE_DELEGATE;
            }
            if (name.contains(".packetevents.injector.")) {
                flags |= FLAG_PE_INJECTOR;
            }
            return flags;
        }
    };

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
        int len = name.length();
        if (len == 11) {
            return name.equalsIgnoreCase("ProtocolLib");
        }
        return len == 12 && name.equalsIgnoreCase("packetevents");
    }

    /**
     * Walks the call stack to find the nearest calling {@link Plugin} (excluding PluginHooker itself,
     * standalone PacketEvents, and ProtocolLib).
     */
    public static Plugin resolveCallerPlugin(PluginRegistry registry, ClassLoader selfLoader) {
        return CLASS_STACK_WALKER.walk(frames -> {
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
     * Stops immediately if the first non-Netty frame belongs to the Minecraft server rather than a plugin.
     */
    public static Plugin resolveDirectNettyCaller(PluginRegistry registry, ClassLoader selfLoader) {
        return CLASS_STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                if ((CLASS_FLAGS.get(callerClass) & (FLAG_NETTY | FLAG_SELF_PACKAGE)) != 0) {
                    continue;
                }
                return registry.getPluginByClassLoader(loader);
            }
            return null;
        });
    }

    /**
     * Fast-path resolver that walks the call stack and returns the direct calling {@link Plugin}
     * if it is currently hooked in {@link PluginRegistry}.
     */
    public static Plugin resolveHookedCaller(PluginRegistry registry, ClassLoader selfLoader) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return CLASS_STACK_WALKER.walk(frames -> {
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
                int flags = CLASS_FLAGS.get(callerClass);
                if ((flags & FLAG_EVENT_BOUNDARY) != 0) {
                    return null;
                }
                if ((flags & FLAG_API_WRAPPER) != 0) {
                    continue;
                }
                return null;
            }
            return null;
        });
    }

    /**
     * Returns true if {@code directCallerObj} comes from a bootstrap-loaded reflection frame
     * (or is absent), meaning {@link #resolveReflectiveNmsCaller} must walk the stack to find
     * the real caller.
     */
    public static boolean isReflectiveNmsCall(Object directCallerObj) {
        return !(directCallerObj instanceof Class<?> directCaller) || directCaller.getClassLoader() == null;
    }

    /**
     * O(1) zero-walk resolver for non-reflective NMS connection calls using the C2-intrinsified
     * {@link StackWalker#getCallerClass()} result. Rejects vanilla server calls in ~1 ns via
     * pointer comparison without touching {@code ClassValue}, {@code MethodHandle}, or player maps.
     */
    public static Plugin resolveFastDirectNmsCaller(
            PluginRegistry registry,
            ClassLoader selfLoader,
            Class<?> connectionClass,
            Class<?> directCaller
    ) {
        if (directCaller.isAssignableFrom(connectionClass)) {
            return null;
        }
        ClassLoader loader = directCaller.getClassLoader();
        return loader == null || loader == selfLoader ? null : resolveHookedPluginFromLoader(registry, loader);
    }

    /**
     * Fallback stack-walking resolver used only when an instrumented NMS connection method was invoked
     * reflectively via {@code Method.invoke} or {@code MethodHandle} ({@code directCaller.getClassLoader() == null}).
     */
    public static Plugin resolveReflectiveNmsCaller(
            PluginRegistry registry,
            ClassLoader selfLoader,
            Class<?> connectionClass
    ) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return CLASS_STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            boolean skippedConnectionEntry = false;
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                if ((CLASS_FLAGS.get(callerClass) & FLAG_SELF_PACKAGE) != 0) {
                    continue;
                }
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null) {
                    continue;
                }
                if (!skippedConnectionEntry && callerClass.isAssignableFrom(connectionClass)) {
                    skippedConnectionEntry = true;
                    continue;
                }
                if (loader == selfLoader) {
                    return null;
                }
                return resolveHookedPluginFromLoader(registry, loader);
            }
            return null;
        });
    }

    /**
     * Resolves the direct plugin caller of ProtocolLib's {@code PacketFilterManager#sendServerPacket},
     * {@code receiveClientPacket}, or {@code sendWirePacket}.
     */
    public static Plugin resolveDirectProtocolLibCaller(PluginRegistry registry, ClassLoader selfLoader) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return CLASS_STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                int flags = CLASS_FLAGS.get(callerClass);
                if ((flags & (FLAG_SELF_PACKAGE | FLAG_PROTOCOLLIB_DELEGATE)) != 0) {
                    continue;
                }
                if ((flags & (FLAG_PROTOCOLLIB_INTERNAL | FLAG_NETTY)) != 0) {
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
        return CLASS_STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                Class<?> callerClass = iterator.next().getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                int flags = CLASS_FLAGS.get(callerClass);
                if ((flags & (FLAG_SELF_PACKAGE | FLAG_PE_SEND_DELEGATE)) != 0) {
                    continue;
                }
                if ((flags & (FLAG_NETTY | FLAG_PE_INJECTOR)) != 0) {
                    return null;
                }
                return resolveHookedPluginFromLoader(registry, loader);
            }
            return null;
        });
    }

    private static final Object CONTINUE_WALK = new Object();

    /**
     * Resolves the calling plugin for a direct {@code channel.write/writeAndFlush(msg)} or
     * {@code ctx.write/writeAndFlush(msg)} call (including calls routed through standalone or shaded
     * PacketEvents {@code ChannelHelper}).
     */
    public static Plugin resolveDirectOutboundWriteCaller(
            PluginRegistry registry,
            ClassLoader selfLoader,
            boolean fromTailContext
    ) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        if (fromTailContext) {
            return CLASS_STACK_WALKER.walk(frames -> {
                var iterator = frames.iterator();
                while (iterator.hasNext()) {
                    Class<?> callerClass = iterator.next().getDeclaringClass();
                    ClassLoader loader = callerClass.getClassLoader();
                    if (loader == null || loader == selfLoader) {
                        continue;
                    }
                    int flags = CLASS_FLAGS.get(callerClass);
                    if ((flags & (FLAG_NETTY | FLAG_SELF_PACKAGE)) != 0) {
                        continue;
                    }
                    Object step = resolveWriteLoaderStep(registry, loader, flags);
                    if (step == CONTINUE_WALK) {
                        continue;
                    }
                    return (Plugin) step;
                }
                return null;
            });
        }

        return METHOD_STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                Class<?> callerClass = frame.getDeclaringClass();
                ClassLoader loader = callerClass.getClassLoader();
                if (loader == null || loader == selfLoader) {
                    continue;
                }
                int flags = CLASS_FLAGS.get(callerClass);
                if ((flags & FLAG_NETTY_CODEC_HANDLER) != 0) {
                    return null;
                }
                if ((flags & (FLAG_NETTY | FLAG_SELF_PACKAGE)) != 0) {
                    continue;
                }
                if (CODEC_FORWARDING_METHODS.contains(frame.getMethodName())) {
                    return null;
                }
                Object step = resolveWriteLoaderStep(registry, loader, flags);
                if (step == CONTINUE_WALK) {
                    continue;
                }
                return (Plugin) step;
            }
            return null;
        });
    }

    private static Object resolveWriteLoaderStep(PluginRegistry registry, ClassLoader loader, int flags) {
        Plugin plugin = registry.getPluginByClassLoader(loader);
        if (plugin == null) {
            return null;
        }
        if (isSharedPacketFramework(plugin)) {
            return (flags & (FLAG_PE_SEND_DELEGATE | FLAG_PE_CLOSE_DELEGATE)) != 0 ? CONTINUE_WALK : null;
        }
        return registry.isHooked(plugin) ? plugin : null;
    }

    /**
     * Resolves the calling plugin for a direct {@code channel.close()}/{@code disconnect()} or
     * {@code ctx.close()}/{@code disconnect()} call while ignoring pipeline close propagation.
     */
    public static Plugin resolveDirectChannelCloseCaller(PluginRegistry registry, ClassLoader selfLoader) {
        if (!registry.hasHookedPlugins()) {
            return null;
        }
        return METHOD_STACK_WALKER.walk(frames -> {
            var iterator = frames.iterator();
            String entryCloseMethod = null;
            boolean adjacentToEntry = false;
            Plugin candidate = null;
            int postCandidateDepth = 0;

            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                Class<?> callerClass = frame.getDeclaringClass();
                int flags = CLASS_FLAGS.get(callerClass);

                if ((flags & FLAG_SELF_PACKAGE) != 0) {
                    continue;
                }

                if (candidate == null) {
                    if ((flags & FLAG_ABSTRACT_HANDLER_CTX) != 0) {
                        String methodName = frame.getMethodName();
                        if (isCloseOrDisconnectMethod(methodName)) {
                            if (entryCloseMethod == null) {
                                entryCloseMethod = methodName;
                                adjacentToEntry = true;
                                continue;
                            }
                            if (adjacentToEntry && methodName.equals(entryCloseMethod)) {
                                // Unhooked 0-arg close()/disconnect() delegating directly into 1-arg overload
                                adjacentToEntry = false;
                                continue;
                            }
                            return null;
                        }
                    }
                    adjacentToEntry = false;
                    if ((flags & FLAG_NETTY) != 0) {
                        continue;
                    }

                    ClassLoader loader = callerClass.getClassLoader();
                    if (loader == null || loader == selfLoader) {
                        return null;
                    }
                    if ((flags & FLAG_PE_CLOSE_DELEGATE) != 0) {
                        continue;
                    }
                    if ((flags & FLAG_CHANNEL_HANDLER) != 0) {
                        String methodName = frame.getMethodName();
                        if (methodName.equals("close") || methodName.equals("disconnect")) {
                            return null;
                        }
                    }
                    candidate = resolveHookedPluginFromLoader(registry, loader);
                    if (candidate == null) {
                        return null;
                    }
                } else {
                    if ((flags & FLAG_ABSTRACT_HANDLER_CTX) != 0
                            && isCloseOrDisconnectMethod(frame.getMethodName())) {
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

    private static boolean isCloseOrDisconnectMethod(String methodName) {
        return methodName.equals("close")
                || methodName.equals("invokeClose")
                || methodName.equals("disconnect")
                || methodName.equals("invokeDisconnect");
    }

    private static Plugin resolveHookedPluginFromLoader(PluginRegistry registry, ClassLoader loader) {
        Plugin plugin = registry.getPluginByClassLoader(loader);
        if (plugin == null || isSharedPacketFramework(plugin)) {
            return null;
        }
        return registry.isHooked(plugin) ? plugin : null;
    }
}
