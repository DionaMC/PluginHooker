package dev.diona.pluginhooker.patch.impl;

import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import dev.diona.pluginhooker.api.event.ProtocolLibHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.HookedPlayerImpl;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.BukkitEvents;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import dev.diona.pluginhooker.util.ProtocolLibReadOnly;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
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

    private final Logger logger;
    private final HookConfig config;
    private final PluginRegistryImpl pluginRegistry;
    private final PlayerRegistryImpl playerRegistry;
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
                    CodeTransforms.guardProtocolLibListenerCalls(
                            HookIds.GUARD_PROTOCOLLIB_OUTBOUND,
                            HookIds.GUARD_PROTOCOLLIB_INBOUND
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
        if (!playerRegistry.canBlockAnyPlayerAction() || !(playerObj instanceof Player player)) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(player);
        if (!playerRegistry.hasDisabledPlugins(player, hookedPlayer)) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveDirectProtocolLibCaller(pluginRegistry, selfClassLoader);
        if (!playerRegistry.isPluginDisabled(player, hookedPlayer, caller)) {
            return false;
        }
        if (config.isDebug()) {
            String packetDesc = packetObj != null ? packetObj.toString() : "null";
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from sending ProtocolLib packet (" + packetDesc + ")"
                    + " to player " + player.getName());
        }
        return true;
    }

    public boolean handlePacket(Object eventObj, Object listenerObj, boolean outbound) {
        if (!pluginRegistry.hasHookedPlugins()
                || !(eventObj instanceof PacketEvent event)
                || !(listenerObj instanceof PacketListener listener)
                || event.isPlayerTemporary()) {
            return false;
        }

        Plugin plugin = listener.getPlugin();
        HookMode mode = pluginRegistry.getHookMode(plugin);
        if (mode == null) {
            return false;
        }
        boolean callEvent = config.protocolLib().isCallEvent();
        if (mode == HookMode.BLACKLIST && !callEvent && !playerRegistry.hasPlayersWithDisabledOverrides()) {
            return false;
        }

        Player bukkitPlayer = event.getPlayer();
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        if (hookedPlayer == null) {
            return !PlayerRegistryImpl.isAllowed(bukkitPlayer, null, plugin, mode);
        }

        if (!HookedPlayerImpl.isAllowed(hookedPlayer, plugin, mode)) {
            if (hookedPlayer.isInJoinGracePeriod()) {
                ProtocolLibReadOnly.invokeReadOnlyListener(event, listener, outbound);
            }
            return true;
        }

        return BukkitEvents.fireCancellable(
                callEvent,
                false,
                () -> new ProtocolLibHookEvent(plugin, hookedPlayer, listener, event, outbound)
        );
    }
}
