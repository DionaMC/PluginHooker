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
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;

/**
 * Hooks ProtocolLib's {@code InboundPacketListenerSet} and {@code OutboundPacketListenerSet}.
 */
@RequiredArgsConstructor
public final class ProtocolLibPatch extends ClassPatch {

    private static final String INBOUND_SET = "com/comphenix/protocol/injector/collection/InboundPacketListenerSet";
    private static final String OUTBOUND_SET = "com/comphenix/protocol/injector/collection/OutboundPacketListenerSet";

    private final HookConfig config;
    private final PluginRegistry pluginRegistry;
    private final PlayerRegistry playerRegistry;

    @Override
    public boolean isEnabled() {
        return config.protocolLib().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        return INBOUND_SET.equals(internalClassName) || OUTBOUND_SET.equals(internalClassName);
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
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
