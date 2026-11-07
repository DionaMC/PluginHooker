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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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

        // Transform AbstractChannelHandlerContext: intercept ChannelInboundHandler.channelRead and ChannelOutboundHandler.write
        CodeTransform replaceRead = CodeTransforms.replaceInterfaceCall(
                "io/netty/channel/ChannelInboundHandler",
                "channelRead",
                "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V",
                "dispatchNettyRead",
                BRIDGE_READ_DESC
        );

        CodeTransform replaceWrite = CodeTransforms.replaceInterfaceCall(
                "io/netty/channel/ChannelOutboundHandler",
                "write",
                "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)V",
                "dispatchNettyWrite",
                BRIDGE_WRITE_DESC
        );

        return ClassTransform.transformingMethodBodies(replaceRead.andThen(replaceWrite));
    }

    public void handlePipelineAdd(Object ctxObj) {
        if (!(ctxObj instanceof ChannelHandlerContext ctx)) {
            return;
        }
        ChannelHandler handler = ctx.handler();
        if (handler == null) {
            return;
        }

        // 1. Check the handler's own ClassLoader first (avoids misattributing ProtocolLib/PacketEvents internal handlers)
        Plugin owner = pluginRegistry.getPluginByClassLoader(handler.getClass().getClassLoader());
        if (owner == null) {
            // 2. If the handler is a standard Netty class, walk the call stack to see which plugin added it
            owner = PluginCallerResolver.resolveCallerPlugin(pluginRegistry, selfClassLoader);
        }

        if (owner != null
                && !owner.getName().equalsIgnoreCase("ProtocolLib")
                && !owner.getName().equalsIgnoreCase("packetevents")) {
            contextToPlugin.put(ctx, owner);
        }
    }

    public void handleNettyDispatch(int isOutbound, Object handlerObj, Object ctxObj, Object msg, Object promiseObj) throws Exception {
        ChannelHandlerContext ctx = (ChannelHandlerContext) ctxObj;
        boolean outbound = isOutbound != 0;

        Plugin plugin = resolveHandlerPlugin(handlerObj, ctx);
        if (plugin != null && pluginRegistry.isHooked(plugin)) {
            HookedPlayer hookedPlayer = resolveHookedPlayer(ctx.channel());
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

        if (outbound) {
            ((ChannelOutboundHandler) handlerObj).write(ctx, msg, (ChannelPromise) promiseObj);
        } else {
            ((ChannelInboundHandler) handlerObj).channelRead(ctx, msg);
        }
    }

    private Plugin resolveHandlerPlugin(Object handlerObj, ChannelHandlerContext ctx) {
        Plugin plugin = pluginRegistry.getPluginByClassLoader(handlerObj.getClass().getClassLoader());
        if (plugin != null) {
            if (plugin.getName().equalsIgnoreCase("ProtocolLib")
                    || plugin.getName().equalsIgnoreCase("packetevents")) {
                return null;
            }
            return plugin;
        }
        return contextToPlugin.get(ctx);
    }

    private HookedPlayer resolveHookedPlayer(Channel channel) {
        HookedPlayer hookedPlayer = PlayerChannelResolver.getHookedPlayer(channel);
        if (hookedPlayer != null) {
            return hookedPlayer;
        }
        Player onlinePlayer = PlayerChannelResolver.findOnlinePlayerByChannel(channel);
        if (onlinePlayer != null) {
            hookedPlayer = playerRegistry.getPlayer(onlinePlayer);
            if (hookedPlayer != null) {
                PlayerChannelResolver.bindPlayerToChannel(onlinePlayer, hookedPlayer);
                return hookedPlayer;
            }
        }
        return null;
    }

    private static void bypassHandler(ChannelHandlerContext ctx, Object msg, Object promiseObj, boolean outbound) {
        if (outbound) {
            ctx.write(msg, (ChannelPromise) promiseObj);
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
