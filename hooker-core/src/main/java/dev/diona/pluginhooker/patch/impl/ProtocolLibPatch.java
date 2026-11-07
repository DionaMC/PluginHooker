package dev.diona.pluginhooker.patch.impl;

import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import dev.diona.pluginhooker.api.event.ProtocolLibHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;
import java.util.logging.Logger;

/**
 * Hooks ProtocolLib's {@code InboundPacketListenerSet}, {@code OutboundPacketListenerSet},
 * {@code AsyncListenerHandler}, and active packet sending/receiving in {@code PacketFilterManager}.
 */
@RequiredArgsConstructor
public final class ProtocolLibPatch extends ClassPatch {

    private static final String INBOUND_SET = "com/comphenix/protocol/injector/collection/InboundPacketListenerSet";
    private static final String OUTBOUND_SET = "com/comphenix/protocol/injector/collection/OutboundPacketListenerSet";
    private static final String ASYNC_HANDLER = "com/comphenix/protocol/async/AsyncListenerHandler";
    private static final String PACKET_FILTER_MANAGER = "com/comphenix/protocol/injector/PacketFilterManager";

    private static final ClassDesc ASYNC_HANDLER_DESC =
            ClassDesc.ofInternalName(ASYNC_HANDLER);
    private static final ClassDesc PACKET_LISTENER_DESC =
            ClassDesc.ofInternalName("com/comphenix/protocol/events/PacketListener");

    private final Logger logger;
    private final HookConfig config;
    private final PluginRegistry pluginRegistry;
    private final PlayerRegistry playerRegistry;
    private final ClassLoader selfClassLoader;

    @Override
    public boolean isEnabled() {
        return config.protocolLib().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        return INBOUND_SET.equals(internalClassName)
                || OUTBOUND_SET.equals(internalClassName)
                || ASYNC_HANDLER.equals(internalClassName)
                || PACKET_FILTER_MANAGER.equals(internalClassName);
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        if (PACKET_FILTER_MANAGER.equals(internalClassName)) {
            return ClassTransform.transformingMethodBodies(
                    method -> {
                        String name = method.methodName().stringValue();
                        int params = method.methodTypeSymbol().parameterCount();
                        return ((name.equals("sendServerPacket") || name.equals("receiveClientPacket")) && params == 4)
                                || (name.equals("sendWirePacket") && params == 2);
                    },
                    CodeTransforms.guardVoidEntry(
                            HookIds.GUARD_PROTOCOLLIB_SEND_PACKET,
                            ArgSpec.param(0),
                            ArgSpec.param(1),
                            ArgSpec.nullConst()
                    )
            );
        }

        if (ASYNC_HANDLER.equals(internalClassName)) {
            return ClassTransform.transformingMethodBodies(
                    method -> method.methodName().stringValue().startsWith("lambda$processPacket$")
                            && method.methodTypeSymbol().parameterCount() == 1,
                    CodeTransforms.guardVoidEntry(
                            HookIds.GUARD_PROTOCOLLIB_ASYNC,
                            ArgSpec.param(0),
                            ArgSpec.getThisField(ASYNC_HANDLER_DESC, "listener", PACKET_LISTENER_DESC),
                            ArgSpec.nullConst()
                    )
            );
        }

        int hookId = OUTBOUND_SET.equals(internalClassName)
                ? HookIds.GUARD_PROTOCOLLIB_OUTBOUND
                : HookIds.GUARD_PROTOCOLLIB_INBOUND;

        return ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("invokeListener")
                        && method.methodTypeSymbol().parameterCount() == 2,
                CodeTransforms.guardVoidEntry(
                        hookId,
                        ArgSpec.param(0),
                        ArgSpec.param(1),
                        ArgSpec.nullConst()
                )
        );
    }

    public boolean handleSendOrReceivePacket(Object playerObj, Object packetObj) {
        if (!(playerObj instanceof Player bukkitPlayer) || !pluginRegistry.hasHookedPlugins()) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        if (hookedPlayer == null) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveDirectProtocolLibCaller(pluginRegistry, selfClassLoader);
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                String packetDesc = packetObj != null ? packetObj.toString() : "null";
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from sending ProtocolLib packet (" + packetDesc + ")"
                        + " to player " + bukkitPlayer.getName());
            }
            return true;
        }
        return false;
    }

    public boolean handleAsyncPacket(Object eventObj, Object listenerObj) {
        if (!(eventObj instanceof PacketEvent event)) {
            return false;
        }
        return handlePacket(event, listenerObj, event.isServerPacket());
    }

    public boolean handlePacket(Object eventObj, Object listenerObj, boolean outbound) {
        if (!(eventObj instanceof PacketEvent event) || !(listenerObj instanceof PacketListener listener)) {
            return false;
        }
        if (event.isPlayerTemporary()) {
            return false;
        }

        Plugin plugin = listener.getPlugin();
        if (!pluginRegistry.isHooked(plugin)) {
            return false;
        }

        HookedPlayer hookedPlayer = playerRegistry.getPlayer(event.getPlayer());
        if (hookedPlayer == null) {
            return false;
        }

        if (!hookedPlayer.isPluginAllowed(plugin)) {
            return true;
        }

        if (config.protocolLib().isCallEvent()) {
            ProtocolLibHookEvent hookEvent = new ProtocolLibHookEvent(
                    plugin,
                    hookedPlayer,
                    listener,
                    event,
                    outbound
            );
            Bukkit.getPluginManager().callEvent(hookEvent);
            return hookEvent.isCancelled();
        }
        return false;
    }
}
