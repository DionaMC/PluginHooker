package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.NettyHookEvent;
import dev.diona.pluginhooker.api.event.PlayerChannelCloseHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.HookedPlayerImpl;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginOwnerSlot;
import dev.diona.pluginhooker.service.PluginOwnerSlot.ActivePlugin;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.BukkitEvents;
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
import org.bukkit.plugin.Plugin;

import com.google.common.collect.MapMaker;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.constant.MethodTypeDesc;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
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

    private static final ClassValue<Boolean> TAIL_CONTEXT_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> contextClass) {
            return contextClass.getName().endsWith("$TailContext");
        }
    };

    private static final ClassValue<Boolean> EXCLUDED_HANDLER_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> handlerClass) {
            String name = handlerClass.getName();
            String simpleName = handlerClass.getSimpleName();
            return name.startsWith("net.minecraft.")
                    || name.startsWith("org.bukkit.")
                    || name.startsWith("io.papermc.")
                    || name.startsWith("ca.spottedleaf.")
                    || name.startsWith("com.mojang.")
                    || name.startsWith("com.comphenix.protocol.")
                    || name.startsWith("io.netty.channel.DefaultChannelPipeline$")
                    || name.contains(".packetevents.")
                    || simpleName.startsWith("PacketEvents")
                    || simpleName.equals("InboundPacketInterceptor");
        }
    };

    private final Logger logger;
    private final HookConfig config;
    private final PluginRegistryImpl pluginRegistry;
    private final PlayerRegistryImpl playerRegistry;
    private final ClassLoader selfClassLoader;

    private final ConcurrentMap<ChannelHandlerContext, Plugin> contextToPlugin =
            new MapMaker().weakKeys().makeMap();
    private volatile boolean hasFallbackContexts;

    private final ClassValue<PluginOwnerSlot> handlerOwnerCache = new ClassValue<>() {
        @Override
        protected PluginOwnerSlot computeValue(Class<?> handlerClass) {
            if (EXCLUDED_HANDLER_CACHE.get(handlerClass)) {
                return PluginOwnerSlot.EXCLUDED;
            }
            Plugin owner = pluginRegistry.getPluginByClassLoader(handlerClass.getClassLoader());
            if (owner == null) {
                return PluginOwnerSlot.FALLBACK;
            }
            if (PluginCallerResolver.isSharedPacketFramework(owner)) {
                return PluginOwnerSlot.EXCLUDED;
            }
            return PluginOwnerSlot.forOwner(owner);
        }
    };

    @Override
    public boolean isEnabled() {
        return config.netty().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        return PIPELINE_CLASS.equals(internalClassName) || CONTEXT_CLASS.equals(internalClassName);
    }

    @Override
    public void reset() {
        contextToPlugin.clear();
        hasFallbackContexts = false;
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
                if (name.equals("write")
                        && desc.equals("(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;")) {
                    CodeTransform byteBufGuard = CodeTransforms.guardNettyChannelFutureEntry(
                            HookIds.GUARD_NETTY_WRITE_BYTEBUF,
                            ArgSpec.thisRef(),
                            ArgSpec.param(0),
                            ArgSpec.nullConst(),
                            -1
                    );
                    mb.transformCode(codeModel, byteBufGuard.andThen(handlerCallsTransform));
                } else if (name.equals("write")
                        && desc.equals("(Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)Lio/netty/channel/ChannelFuture;")) {
                    CodeTransform byteBufGuard = CodeTransforms.guardNettyChannelFutureEntry(
                            HookIds.GUARD_NETTY_WRITE_BYTEBUF_TAIL,
                            ArgSpec.thisRef(),
                            ArgSpec.param(0),
                            ArgSpec.param(1),
                            1
                    );
                    mb.transformCode(codeModel, byteBufGuard.andThen(handlerCallsTransform));
                } else if (name.equals("writeAndFlush")
                        && desc.equals("(Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)Lio/netty/channel/ChannelFuture;")) {
                    // Note: 1-arg writeAndFlush(Object) always delegates to 2-arg writeAndFlush(Object, ChannelPromise)
                    CodeTransform byteBufGuard = CodeTransforms.guardNettyChannelFutureEntry(
                            HookIds.GUARD_NETTY_WRITE_BYTEBUF,
                            ArgSpec.thisRef(),
                            ArgSpec.param(0),
                            ArgSpec.param(1),
                            1
                    );
                    mb.transformCode(codeModel, byteBufGuard.andThen(handlerCallsTransform));
                } else if ((name.equals("close") || name.equals("disconnect"))
                        && desc.equals("(Lio/netty/channel/ChannelPromise;)Lio/netty/channel/ChannelFuture;")) {
                    // Note: 0-arg close() and disconnect() always delegate to their 1-arg ChannelPromise overloads
                    CodeTransform closeGuard = CodeTransforms.guardNettyChannelFutureEntry(
                            HookIds.GUARD_NETTY_CLOSE,
                            ArgSpec.thisRef(),
                            ArgSpec.param(0),
                            ArgSpec.nullConst(),
                            0
                    );
                    mb.transformCode(codeModel, closeGuard.andThen(handlerCallsTransform));
                } else {
                    mb.transformCode(codeModel, handlerCallsTransform);
                }
            } else {
                mb.with(element);
            }
        });
    }

    /**
     * Intercepts direct {@code channel.write/writeAndFlush(ByteBuf[, promise])} and
     * {@code ctx.write/writeAndFlush(ByteBuf[, promise])} calls initiated by disabled plugins
     * (including completely obfuscated shaded PacketEvents).
     */
    public boolean handleWriteByteBuf(Object ctxObj, Object msg, Object promiseObj, boolean requireTailContext) {
        if (!(msg instanceof ByteBuf buf) || !pluginRegistry.hasHookedPlugins()) {
            return false;
        }
        boolean isTail = TAIL_CONTEXT_CACHE.get(ctxObj.getClass());
        if (requireTailContext && !isTail) {
            return false;
        }
        if (!(ctxObj instanceof ChannelHandlerContext ctx)) {
            return false;
        }
        HookedPlayer hookedPlayer = PlayerChannelResolver.getHookedPlayer(ctx.channel());
        if (hookedPlayer == null || !hookedPlayer.hasAnyDisabledPlugin()) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveDirectByteBufWriteCaller(pluginRegistry, selfClassLoader, isTail);
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from writing raw ByteBuf (" + buf.readableBytes() + " bytes)"
                        + " to player " + hookedPlayer.getName());
            }
            ReferenceCountUtil.safeRelease(msg);
            if (promiseObj instanceof ChannelPromise promise) {
                promise.trySuccess();
            }
            return true;
        }
        return false;
    }

    /**
     * Intercepts direct {@code channel.close()}/{@code disconnect()} and {@code ctx.close()}/{@code disconnect()}
     * calls initiated by hooked plugins (e.g. GrimAC's or Vulcan's {@code user.closeConnection()}).
     */
    public boolean handleChannelClose(Object ctxObj, Object promiseObj) {
        if (!pluginRegistry.hasHookedPlugins() || !(ctxObj instanceof ChannelHandlerContext ctx)) {
            return false;
        }
        HookedPlayer hookedPlayer = PlayerChannelResolver.getHookedPlayer(ctx.channel());
        if (hookedPlayer == null || (!hookedPlayer.hasAnyDisabledPlugin() && !config.bukkitApi().isCallKickEvent())) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveDirectChannelCloseCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return false;
        }

        boolean enabled = hookedPlayer.isPluginEnabled(caller);
        boolean defaultBlock = !enabled && config.bukkitApi().isBlockDisabledKick();
        boolean shouldBlock = BukkitEvents.fireCancellable(
                config.bukkitApi().isCallKickEvent(),
                defaultBlock,
                () -> new PlayerChannelCloseHookEvent(caller, hookedPlayer, ctx.channel(), enabled, defaultBlock)
        );

        if (shouldBlock) {
            if (config.isDebug()) {
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from closing Netty channel of player " + hookedPlayer.getName());
            }
            if (promiseObj instanceof ChannelPromise promise) {
                promise.trySuccess();
            }
            return true;
        }
        return false;
    }

    public void handlePipelineAdd(Object ctxObj) {
        if (!(ctxObj instanceof ChannelHandlerContext ctx)) {
            return;
        }
        ChannelHandler handler = ctx.handler();
        if (handler == null || EXCLUDED_HANDLER_CACHE.get(handler.getClass())) {
            return;
        }

        // 1. Check the handler's own ClassLoader first (resolved directly on hot path without contextToPlugin)
        Plugin owner = pluginRegistry.getPluginByClassLoader(handler.getClass().getClassLoader());
        if (owner != null) {
            return;
        }

        // 2. If the handler is a standard Netty class, check if the direct non-Netty caller is a plugin
        owner = PluginCallerResolver.resolveDirectNettyCaller(pluginRegistry, selfClassLoader);
        if (owner != null && !PluginCallerResolver.isSharedPacketFramework(owner)) {
            contextToPlugin.put(ctx, owner);
            hasFallbackContexts = true;
        }
    }

    public void handleNettyDispatch(int isOutbound, Object handlerObj, Object ctxObj, Object msg, Object promiseObj) throws Exception {
        ChannelHandlerContext ctx = (ChannelHandlerContext) ctxObj;
        boolean outbound = isOutbound != 0;

        if (pluginRegistry.hasHookedPlugins()) {
            ActivePlugin active = resolveHookedHandler(handlerObj, ctx);
            if (active != null) {
                boolean callEvent = config.netty().isCallEvent();
                if (active.mode() != HookMode.BLACKLIST
                        || callEvent
                        || playerRegistry.hasPlayersWithDisabledOverrides()) {
                    HookedPlayer hookedPlayer = PlayerChannelResolver.getHookedPlayer(ctx.channel());
                    if (hookedPlayer != null) {
                        if (!HookedPlayerImpl.isAllowed(hookedPlayer, active.plugin(), active.mode())) {
                            bypassHandler(ctx, msg, promiseObj, outbound);
                            return;
                        }
                        if (BukkitEvents.fireCancellable(
                                callEvent,
                                false,
                                () -> new NettyHookEvent(active.plugin(), hookedPlayer, msg, outbound)
                        )) {
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

    private ActivePlugin resolveHookedHandler(Object handlerObj, ChannelHandlerContext ctx) {
        PluginOwnerSlot slot = handlerOwnerCache.get(handlerObj.getClass());
        if (slot.isExcluded()) {
            return null;
        }
        if (!slot.isFallback()) {
            return slot.resolveActive(pluginRegistry);
        }
        return hasFallbackContexts ? ActivePlugin.of(contextToPlugin.get(ctx), pluginRegistry) : null;
    }

    private static void bypassHandler(ChannelHandlerContext ctx, Object msg, Object promiseObj, boolean outbound) {
        if (outbound) {
            ctx.write(msg, (ChannelPromise) promiseObj);
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
