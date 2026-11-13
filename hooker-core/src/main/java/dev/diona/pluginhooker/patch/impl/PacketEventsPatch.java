package dev.diona.pluginhooker.patch.impl;

import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.player.User;
import com.google.common.collect.MapMaker;
import dev.diona.pluginhooker.api.event.PacketEventsHookEvent;
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
import dev.diona.pluginhooker.util.PacketEventsReflector;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * Hooks standalone and shaded PacketEvents' {@code EventManager}, {@code PacketReceiveEvent},
 * {@code PacketSendEvent}, and {@code ProtocolManagerImpl} active packet-sending methods.
 */
@RequiredArgsConstructor
public final class PacketEventsPatch extends ClassPatch {

    private static final String EVENT_MANAGER_SUFFIX = "packetevents/event/EventManager";
    private static final String PACKET_RECEIVE_SUFFIX = "packetevents/event/PacketReceiveEvent";
    private static final String PACKET_SEND_SUFFIX = "packetevents/event/PacketSendEvent";
    private static final String PROTOCOL_MANAGER_IMPL_SUFFIX = "packetevents/manager/protocol/ProtocolManagerImpl";

    private static final Set<String> PROTOCOL_SEND_METHODS = Set.of(
            "sendPacket",
            "sendPacketSilently",
            "writePacket",
            "writePacketSilently",
            "receivePacket",
            "receivePacketSilently"
    );

    private static final Set<String> IGNORED_EVENT_SIMPLE_NAMES = Set.of(
            "PacketHandshakeReceiveEvent",
            "PacketStatusReceiveEvent",
            "PacketLoginReceiveEvent",
            "PacketConfigReceiveEvent",
            "PacketHandshakeSendEvent",
            "PacketStatusSendEvent",
            "PacketLoginSendEvent",
            "PacketConfigSendEvent"
    );

    private static final ClassValue<Boolean> IGNORED_EVENT_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> eventClass) {
            return IGNORED_EVENT_SIMPLE_NAMES.contains(eventClass.getSimpleName());
        }
    };

    private static final ClassValue<Boolean> INTERNAL_LISTENER_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> listenerClass) {
            String name = listenerClass.getName();
            String simpleName = listenerClass.getSimpleName();
            return name.contains(".packetevents.manager.")
                    || name.contains(".packetevents.util.")
                    || name.contains(".packetevents.bukkit.")
                    || name.contains(".packetevents.injector.")
                    || simpleName.equals("InternalPacketListener")
                    || simpleName.equals("InternalBukkitPacketListener");
        }
    };

    private final Logger logger;
    private final HookConfig config;
    private final PluginRegistryImpl pluginRegistry;
    private final PlayerRegistryImpl playerRegistry;
    private final ClassLoader selfClassLoader;

    private final ConcurrentMap<Object, Plugin> listenerToPlugin =
            new MapMaker().weakKeys().makeMap();
    private volatile boolean hasFallbackListeners;

    private final ClassValue<PluginOwnerSlot> listenerOwnerCache = new ClassValue<>() {
        @Override
        protected PluginOwnerSlot computeValue(Class<?> listenerClass) {
            if (INTERNAL_LISTENER_CACHE.get(listenerClass)) {
                return PluginOwnerSlot.EXCLUDED;
            }
            Plugin owner = pluginRegistry.getPluginByClassLoader(listenerClass.getClassLoader());
            if (owner != null && !PluginCallerResolver.isSharedPacketFramework(owner)) {
                return PluginOwnerSlot.forOwner(owner);
            }
            return PluginOwnerSlot.FALLBACK;
        }
    };

    @Override
    public boolean isEnabled() {
        return config.packetEvents().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        return internalClassName.endsWith(EVENT_MANAGER_SUFFIX)
                || internalClassName.endsWith(PACKET_RECEIVE_SUFFIX)
                || internalClassName.endsWith(PACKET_SEND_SUFFIX)
                || internalClassName.endsWith(PROTOCOL_MANAGER_IMPL_SUFFIX);
    }

    @Override
    public void reset() {
        listenerToPlugin.clear();
        hasFallbackListeners = false;
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        if (internalClassName.endsWith(PROTOCOL_MANAGER_IMPL_SUFFIX)) {
            return ClassTransform.transformingMethodBodies(
                    method -> PROTOCOL_SEND_METHODS.contains(method.methodName().stringValue())
                            && method.methodTypeSymbol().parameterCount() == 2,
                    CodeTransforms.guardVoidEntry(
                            HookIds.GUARD_PACKETEVENTS_SEND_PACKET,
                            ArgSpec.thisRef(),
                            ArgSpec.param(0),
                            ArgSpec.param(1)
                    )
            );
        }

        if (internalClassName.endsWith(EVENT_MANAGER_SUFFIX)) {
            return ClassTransform.transformingMethods(
                    method -> {
                        String name = method.methodName().stringValue();
                        int params = method.methodTypeSymbol().parameterCount();
                        return ((name.equals("registerListenerNoRecalculation")
                                || name.equals("unregisterListenerNoRecalculation")) && params == 1)
                                || (name.equals("unregisterAllListeners") && params == 0);
                    },
                    (mb, element) -> {
                        if (element instanceof CodeModel codeModel) {
                            String name = codeModel.parent()
                                    .map(m -> m.methodName().stringValue())
                                    .orElse("");
                            if (name.equals("unregisterAllListeners")) {
                                mb.transformCode(
                                        codeModel,
                                        CodeTransforms.notifyAtEntry(
                                                HookIds.NOTIFY_PACKETEVENTS_UNREGISTER_ALL,
                                                ArgSpec.thisRef(),
                                                ArgSpec.nullConst()
                                        )
                                );
                            } else {
                                int hookId = name.equals("registerListenerNoRecalculation")
                                        ? HookIds.NOTIFY_PACKETEVENTS_REGISTER
                                        : HookIds.NOTIFY_PACKETEVENTS_UNREGISTER;
                                mb.transformCode(
                                        codeModel,
                                        CodeTransforms.notifyAtEntry(
                                                hookId,
                                                ArgSpec.param(0),
                                                ArgSpec.nullConst()
                                        )
                                );
                            }
                        } else {
                            mb.with(element);
                        }
                    }
            );
        }

        int hookId = internalClassName.endsWith(PACKET_SEND_SUFFIX)
                ? HookIds.GUARD_PACKETEVENTS_SEND
                : HookIds.GUARD_PACKETEVENTS_RECEIVE;

        return ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("call")
                        && method.methodTypeSymbol().parameterCount() == 1,
                CodeTransforms.guardVoidEntry(
                        hookId,
                        ArgSpec.param(0),
                        ArgSpec.thisRef(),
                        ArgSpec.nullConst()
                )
        );
    }

    public void handleListenerRegister(Object listener) {
        if (listener == null || INTERNAL_LISTENER_CACHE.get(listener.getClass())) {
            return;
        }
        Plugin owner = resolveListenerOwner(listener);
        if (owner != null) {
            return;
        }
        owner = PluginCallerResolver.resolveCallerPlugin(pluginRegistry, selfClassLoader);
        if (owner != null && !PluginCallerResolver.isSharedPacketFramework(owner)) {
            listenerToPlugin.put(listener, owner);
            hasFallbackListeners = true;
        }
    }

    public void handleListenerUnregister(Object listener) {
        if (listener != null && hasFallbackListeners) {
            listenerToPlugin.remove(listener);
            hasFallbackListeners = !listenerToPlugin.isEmpty();
        }
    }

    public void handleUnregisterAllListeners(Object eventManager) {
        if (eventManager == null || !hasFallbackListeners) {
            return;
        }
        Object[] current = PacketEventsReflector.extractListenersArray(eventManager);
        if (current != null) {
            for (Object listener : current) {
                if (listener != null) {
                    listenerToPlugin.remove(listener);
                }
            }
        }
        hasFallbackListeners = !listenerToPlugin.isEmpty();
    }

    public boolean handleSendPacket(Object protocolManagerObj, Object channelObj, Object byteBufObj) {
        if (!playerRegistry.canBlockAnyPlayerAction() || !(channelObj instanceof Channel channel)) {
            return false;
        }
        HookedPlayer hookedPlayer = resolveChannelPlayer(protocolManagerObj, channel);
        if (hookedPlayer != null) {
            if (!hookedPlayer.hasAnyDisabledPlugin()) {
                return false;
            }
        } else if (!pluginRegistry.hasWhitelistPlugins()
                || PlayerChannelResolver.isRealNetworkChannel(PlayerChannelResolver.unwrapChannelProxy(channel))) {
            return false;
        }

        Plugin caller = PluginCallerResolver.resolveDirectPacketEventsSendCaller(pluginRegistry, selfClassLoader);
        boolean blocked = hookedPlayer != null
                ? (caller != null && !hookedPlayer.isPluginEnabled(caller))
                : (caller != null && pluginRegistry.getHookMode(caller) == HookMode.WHITELIST);
        if (!blocked) {
            return false;
        }
        if (config.isDebug()) {
            int bytes = byteBufObj instanceof ByteBuf buf ? buf.readableBytes() : -1;
            String target = hookedPlayer != null ? "player " + hookedPlayer.getName() : "untracked channel";
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from sending PacketEvents packet (" + bytes + " bytes) to " + target);
        }
        ReferenceCountUtil.safeRelease(byteBufObj);
        return true;
    }

    private HookedPlayer resolveChannelPlayer(Object protocolManagerObj, Channel channel) {
        HookedPlayer hookedPlayer = PlayerChannelResolver.getHookedPlayer(channel);
        if (hookedPlayer == null
                && protocolManagerObj instanceof ProtocolManager pm
                && PlayerChannelResolver.isRealNetworkChannel(PlayerChannelResolver.unwrapChannelProxy(channel))) {
            User user = pm.getUser(channel);
            if (user != null && user.getUUID() != null) {
                hookedPlayer = playerRegistry.getPlayer(user.getUUID());
            }
        }
        return hookedPlayer;
    }

    public boolean handlePacketEvent(Object listener, Object event, boolean outbound) {
        if (!pluginRegistry.hasHookedPlugins() || listener == null || event == null) {
            return false;
        }
        if (IGNORED_EVENT_CACHE.get(event.getClass())) {
            return false;
        }

        ActivePlugin active = resolveHookedListener(listener);
        if (active == null) {
            return false;
        }
        boolean callEvent = config.packetEvents().isCallEvent();
        if (active.mode() == HookMode.BLACKLIST && !callEvent && !playerRegistry.hasPlayersWithDisabledOverrides()) {
            return false;
        }

        HookedPlayer hookedPlayer = PacketEventsReflector.resolveEventPlayer(event, playerRegistry);
        if (hookedPlayer == null) {
            Player bukkitPlayer = active.mode() == HookMode.WHITELIST
                    ? PacketEventsReflector.extractEventBukkitPlayer(event)
                    : null;
            return !PlayerRegistryImpl.isAllowed(bukkitPlayer, null, active.plugin(), active.mode());
        }

        if (!HookedPlayerImpl.isAllowed(hookedPlayer, active.plugin(), active.mode())) {
            if (hookedPlayer.isInJoinGracePeriod()) {
                PacketEventsReflector.invokeReadOnlyListener(listener, event, outbound);
            }
            return true;
        }

        if (listener instanceof PacketListenerCommon commonListener && event instanceof PacketEvent packetEvent) {
            return BukkitEvents.fireCancellable(
                    callEvent,
                    false,
                    () -> new PacketEventsHookEvent(active.plugin(), hookedPlayer, commonListener, packetEvent, outbound)
            );
        }
        return false;
    }

    private ActivePlugin resolveHookedListener(Object listener) {
        PluginOwnerSlot slot = listenerOwnerCache.get(listener.getClass());
        if (slot.isExcluded()) {
            return null;
        }
        if (!slot.isFallback()) {
            return slot.resolveActive(pluginRegistry);
        }
        Object inner = PacketEventsReflector.unwrapListener(listener);
        if (inner != null) {
            PluginOwnerSlot innerSlot = listenerOwnerCache.get(inner.getClass());
            if (!innerSlot.isExcluded() && !innerSlot.isFallback()) {
                return innerSlot.resolveActive(pluginRegistry);
            }
        }
        return hasFallbackListeners ? ActivePlugin.of(listenerToPlugin.get(listener), pluginRegistry) : null;
    }

    /**
     * Resolves the owning {@link Plugin} of a PacketEvents listener during registration.
     */
    private Plugin resolveListenerOwner(Object listener) {
        Plugin owner = pluginRegistry.getPluginByClassLoader(listener.getClass().getClassLoader());
        if (owner != null && !PluginCallerResolver.isSharedPacketFramework(owner)) {
            return owner;
        }
        Object inner = PacketEventsReflector.unwrapListener(listener);
        if (inner != null) {
            Plugin innerOwner = pluginRegistry.getPluginByClassLoader(inner.getClass().getClassLoader());
            if (innerOwner != null && !PluginCallerResolver.isSharedPacketFramework(innerOwner)) {
                return innerOwner;
            }
        }
        return null;
    }
}
