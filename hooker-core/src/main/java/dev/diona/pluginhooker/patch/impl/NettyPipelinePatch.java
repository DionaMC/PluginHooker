package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.NettyHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.constant.MethodTypeDesc;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Logger;

/**
 * Hooks Netty's {@code DefaultChannelPipeline} and {@code AbstractChannelHandlerContext}.
 * Intercepts {@code channelRead} and {@code write} directly at the context invocation site without replacing
 * the underlying {@link ChannelHandler}, preserving full pipeline removal and lifecycle compatibility.
 */
@RequiredArgsConstructor
public final class NettyPipelinePatch extends ClassPatch {

    private static final String PIPELINE_CLASS = "io/netty/channel/DefaultChannelPipeline";
    private static final String CONTEXT_CLASS = "io/netty/channel/AbstractChannelHandlerContext";

    private static final Set<String> PIPELINE_ADD_METHODS = Set.of(
            "addFirst0",
            "addLast0",
            "addBefore0",
            "addAfter0",
            "replace0"
    );

    private static final MethodTypeDesc BRIDGE_READ_DESC = MethodTypeDesc.ofDescriptor(
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
    );

    private static final MethodTypeDesc BRIDGE_WRITE_DESC = MethodTypeDesc.ofDescriptor(
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
    );

    private final Logger logger;
    private final HookConfig config;
    private final PluginRegistry pluginRegistry;
    private final PlayerRegistry playerRegistry;
    private final ClassLoader selfClassLoader;

    private final Map<ChannelHandlerContext, Plugin> contextToPlugin =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public boolean isEnabled() {
        return config.netty().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        return PIPELINE_CLASS.equals(internalClassName) || CONTEXT_CLASS.equals(internalClassName);
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        if (PIPELINE_CLASS.equals(internalClassName)) {
            return ClassTransform.transformingMethods(
                    method -> PIPELINE_ADD_METHODS.contains(method.methodName().stringValue())
                            && method.methodTypeSymbol().parameterCount() >= 1,
                    (mb, element) -> {
                        if (element instanceof CodeModel codeModel) {
                            // In all addFirst0/addLast0/addBefore0/addAfter0/replace0 across Netty 4.0~4.2,
                            // the last parameter is the newly added AbstractChannelHandlerContext.
                            int paramCount = codeModel.parent()
                                    .map(m -> m.methodTypeSymbol().parameterCount())
                                    .orElse(1);
                            mb.transformCode(
                                    codeModel,
                                    CodeTransforms.notifyBeforeReturn(
                                            HookIds.NOTIFY_NETTY_HANDLER_ADD,
                                            ArgSpec.param(paramCount - 1),
                                            ArgSpec.nullConst()
                                    )
                            );
                        } else {
                            mb.with(element);
                        }
                    }
            );
        }

        // Transform AbstractChannelHandlerContext: intercept both invokeinterface (Netty 4.0/4.1)
        // and devirtualized invokevirtual (Netty 4.2 ChannelDuplexHandler / ChannelOutboundHandlerAdapter)
        CodeTransform replaceRead = CodeTransforms.replaceNettyHandlerCall(
                "channelRead",
                "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V",
                "dispatchNettyRead",
                BRIDGE_READ_DESC
        );

        CodeTransform replaceWrite = CodeTransforms.replaceNettyHandlerCall(
                "write",
                "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)V",
                "dispatchNettyWrite",
                BRIDGE_WRITE_DESC
        );

        CodeTransform handlerCallsTransform = replaceRead.andThen(replaceWrite);

        return ClassTransform.transformingMethods((mb, element) -> {
            if (element instanceof CodeModel codeModel) {
                var methodOpt = codeModel.parent();
                String name = methodOpt.map(m -> m.methodName().stringValue()).orElse("");
                String desc = methodOpt.map(m -> m.methodType().stringValue()).orElse("");
                if ((name.equals("write") || name.equals("writeAndFlush"))
                        && desc.equals("(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;")) {
                    CodeTransform byteBufGuard = CodeTransforms.guardNettyChannelFutureEntry(
                            HookIds.GUARD_NETTY_WRITE_BYTEBUF,
                            ArgSpec.thisRef(),
                            ArgSpec.param(0),
                            ArgSpec.nullConst()
                    );
                    mb.transformCode(codeModel, byteBufGuard.andThen(handlerCallsTransform));
                } else {
                    mb.transformCode(codeModel, handlerCallsTransform);
                }
            } else {
                mb.with(element);
            }
        });
    }

    /**
     * Intercepts direct {@code channel.write/writeAndFlush(ByteBuf)} and {@code ctx.write/writeAndFlush(ByteBuf)}
     * calls initiated by disabled plugins (including completely obfuscated shaded PacketEvents).
     */
    public boolean handleWriteByteBuf(Object ctxObj, Object msg) {
        if (!(msg instanceof ByteBuf buf) || !pluginRegistry.hasHookedPlugins()) {
            return false;
        }
        if (!(ctxObj instanceof ChannelHandlerContext ctx)) {
            return false;
        }
        HookedPlayer hookedPlayer = PlayerChannelResolver.resolveHookedPlayer(ctx.channel(), playerRegistry);
        if (hookedPlayer == null) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveDirectByteBufWriteCaller(pluginRegistry, selfClassLoader);
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from writing raw ByteBuf (" + buf.readableBytes() + " bytes)"
                        + " to player " + hookedPlayer.getName());
            }
            ReferenceCountUtil.safeRelease(msg);
            return true;
        }
        return false;
    }

    public void handlePipelineAdd(Object ctxObj) {
        if (!(ctxObj instanceof ChannelHandlerContext ctx)) {
            return;
        }
        ChannelHandler handler = ctx.handler();
        if (handler == null || isExcludedHandler(handler.getClass())) {
            return;
        }

        // 1. Check the handler's own ClassLoader first
        Plugin owner = pluginRegistry.getPluginByClassLoader(handler.getClass().getClassLoader());
        if (owner == null) {
            // 2. If the handler is a standard Netty class, check if the direct non-Netty caller is a plugin
            owner = PluginCallerResolver.resolveDirectNettyCaller(pluginRegistry, selfClassLoader);
        }

        if (owner != null && !PluginCallerResolver.isSharedPacketFramework(owner)) {
            contextToPlugin.put(ctx, owner);
        }
    }

    public void handleNettyDispatch(int isOutbound, Object handlerObj, Object ctxObj, Object msg, Object promiseObj) throws Exception {
        ChannelHandlerContext ctx = (ChannelHandlerContext) ctxObj;
        boolean outbound = isOutbound != 0;

        if (pluginRegistry.hasHookedPlugins()) {
            Plugin plugin = resolveHandlerPlugin(handlerObj, ctx);
            if (plugin != null && pluginRegistry.isHooked(plugin)) {
                HookedPlayer hookedPlayer = PlayerChannelResolver.resolveHookedPlayer(ctx.channel(), playerRegistry);
                if (hookedPlayer != null) {
                    if (!hookedPlayer.isPluginAllowed(plugin)) {
                        bypassHandler(ctx, msg, promiseObj, outbound);
                        return;
                    }
                    if (config.netty().isCallEvent()) {
                        NettyHookEvent hookEvent = new NettyHookEvent(plugin, hookedPlayer, msg, outbound);
                        Bukkit.getPluginManager().callEvent(hookEvent);
                        if (hookEvent.isCancelled()) {
                            bypassHandler(ctx, msg, promiseObj, outbound);
                            return;
                        }
                    }
                }
            }
        }

        if (outbound) {
            ((ChannelOutboundHandler) handlerObj).write(ctx, msg, (ChannelPromise) promiseObj);
        } else {
            ((ChannelInboundHandler) handlerObj).channelRead(ctx, msg);
        }
    }

    private Plugin resolveHandlerPlugin(Object handlerObj, ChannelHandlerContext ctx) {
        Class<?> handlerClass = handlerObj.getClass();
        if (isExcludedHandler(handlerClass)) {
            return null;
        }
        Plugin plugin = pluginRegistry.getPluginByClassLoader(handlerClass.getClassLoader());
        if (plugin != null) {
            return PluginCallerResolver.isSharedPacketFramework(plugin) ? null : plugin;
        }
        return contextToPlugin.get(ctx);
    }

    /**
     * Excludes server-internal handlers, ProtocolLib injectors, and PacketEvents handlers
     * (both standalone and shaded PacketEvents are hooked at the {@code EventManager}/{@code PacketEvent}
     * layer by {@link PacketEventsPatch}, preserving {@code PacketEventsEncoder#setHold} queue ordering
     * and {@code handleCompression} pipeline relocation).
     */
    private static boolean isExcludedHandler(Class<?> handlerClass) {
        String name = handlerClass.getName();
        String simpleName = handlerClass.getSimpleName();
        return name.startsWith("net.minecraft.")
                || name.startsWith("org.bukkit.")
                || name.startsWith("io.papermc.")
                || name.startsWith("ca.spottedleaf.")
                || name.startsWith("com.mojang.")
                || name.startsWith("com.comphenix.protocol.")
                || name.contains(".packetevents.")
                || simpleName.startsWith("PacketEvents")
                || simpleName.equals("InboundPacketInterceptor");
    }

    private static void bypassHandler(ChannelHandlerContext ctx, Object msg, Object promiseObj, boolean outbound) {
        if ( msg instanceof ByteBuf buf && !buf.isReadable()) {
            ReferenceCountUtil.safeRelease(msg);
            if (outbound && promiseObj instanceof ChannelPromise promise) {
                promise.trySuccess();
            }
            return;
        }
        if (outbound) {
            ctx.write(msg, (ChannelPromise) promiseObj);
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
