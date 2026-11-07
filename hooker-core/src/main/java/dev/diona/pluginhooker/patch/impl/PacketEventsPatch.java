package dev.diona.pluginhooker.patch.impl;

import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.player.User;
import dev.diona.pluginhooker.api.event.PacketEventsHookEvent;
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
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Logger;

/**
 * Hooks PacketEvents' {@code EventManager}, {@code PacketReceiveEvent}, and {@code PacketSendEvent}.
 * Supports both standalone PacketEvents and shaded/relocated PacketEvents instances.
 */
@RequiredArgsConstructor
public final class PacketEventsPatch extends ClassPatch {

    private static final String EVENT_MANAGER_CLASS = "com/github/retrooper/packetevents/event/EventManager";
    private static final String PACKET_RECEIVE_CLASS = "com/github/retrooper/packetevents/event/PacketReceiveEvent";
    private static final String PACKET_SEND_CLASS = "com/github/retrooper/packetevents/event/PacketSendEvent";
    private static final String PROTOCOL_MANAGER_ABSTRACT_SUFFIX = "packetevents/impl/netty/manager/protocol/ProtocolManagerAbstract";
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

    private static final ClassValue<Optional<MethodHandle>> GET_PLAYER_HANDLE = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> eventClass) {
            try {
                Method m = eventClass.getMethod("getPlayer");
                m.setAccessible(true);
                return Optional.of(
                        MethodHandles.lookup()
                                .unreflect(m)
                                .asType(MethodType.methodType(Object.class, Object.class))
                );
            } catch (ReflectiveOperationException e) {
                return Optional.empty();
            }
        }
    };

    private static final ClassValue<Optional<MethodHandle>> UNWRAP_LISTENER_HANDLE = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> listenerClass) {
            try {
                Field f = listenerClass.getDeclaredField("this$0");
                f.setAccessible(true);
                return Optional.of(
                        MethodHandles.lookup()
                                .unreflectGetter(f)
                                .asType(MethodType.methodType(Object.class, Object.class))
                );
            } catch (ReflectiveOperationException e) {
                return Optional.empty();
            }
        }
    };

    private final Logger logger;
    private final HookConfig config;
    private final PluginRegistry pluginRegistry;
    private final PlayerRegistry playerRegistry;
    private final ClassLoader selfClassLoader;

    private final Map<Object, Plugin> listenerToPlugin =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public boolean isEnabled() {
        return config.packetEvents().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        return EVENT_MANAGER_CLASS.equals(internalClassName)
                || PACKET_RECEIVE_CLASS.equals(internalClassName)
                || PACKET_SEND_CLASS.equals(internalClassName)
                || isProtocolManagerClass(internalClassName);
    }

    private static boolean isProtocolManagerClass(String internalClassName) {
        return internalClassName.endsWith(PROTOCOL_MANAGER_IMPL_SUFFIX)
                || internalClassName.endsWith(PROTOCOL_MANAGER_ABSTRACT_SUFFIX);
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        if (isProtocolManagerClass(internalClassName)) {
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

        if (EVENT_MANAGER_CLASS.equals(internalClassName)) {
            return ClassTransform.transformingMethods(
                    method -> {
                        String name = method.methodName().stringValue();
                        return (name.equals("registerListenerNoRecalculation")
                                || name.equals("unregisterListenerNoRecalculation"))
                                && method.methodTypeSymbol().parameterCount() == 1;
                    },
                    (mb, element) -> {
                        if (element instanceof CodeModel codeModel) {
                            String name = codeModel.parent()
                                    .map(m -> m.methodName().stringValue())
                                    .orElse("");
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
                        } else {
                            mb.with(element);
                        }
                    }
            );
        }

        int hookId = PACKET_SEND_CLASS.equals(internalClassName)
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
        if (listener == null || isInternalPacketEventsListener(listener.getClass())) {
            return;
        }
        Plugin owner = resolveListenerOwner(listener);
        if (owner == null) {
            owner = PluginCallerResolver.resolveCallerPlugin(pluginRegistry, selfClassLoader);
        }
        if (owner != null && !owner.getName().equalsIgnoreCase("packetevents")) {
            listenerToPlugin.put(listener, owner);
        }
    }

    public void handleListenerUnregister(Object listener) {
        if (listener != null) {
            listenerToPlugin.remove(listener);
        }
    }

    public boolean handleSendPacket(Object protocolManagerObj, Object channelObj, Object byteBufObj) {
        if (!pluginRegistry.hasHookedPlugins() || !(channelObj instanceof Channel channel)) {
            return false;
        }
        HookedPlayer hookedPlayer = PlayerChannelResolver.getHookedPlayer(channel);
        if (hookedPlayer == null && protocolManagerObj instanceof ProtocolManager pm) {
            User user = pm.getUser(channel);
            if (user != null && user.getUUID() != null) {
                hookedPlayer = playerRegistry.getPlayer(user.getUUID());
                if (hookedPlayer != null) {
                    channel.attr(PlayerChannelResolver.HOOKED_PLAYER_KEY).set(hookedPlayer);
                }
            }
        }
        if (hookedPlayer == null) {
            Player onlinePlayer = PlayerChannelResolver.findOnlinePlayerByChannel(channel);
            if (onlinePlayer != null) {
                hookedPlayer = playerRegistry.getPlayer(onlinePlayer);
                if (hookedPlayer != null) {
                    PlayerChannelResolver.bindPlayerToChannel(onlinePlayer, hookedPlayer);
                }
            }
            if (hookedPlayer == null) {
                return false;
            }
        }
        Plugin caller = pluginRegistry.getPluginByClassLoader(protocolManagerObj.getClass().getClassLoader());
        if (caller == null || caller.getName().equalsIgnoreCase("packetevents")) {
            caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        }
        if (caller != null && pluginRegistry.isHooked(caller) && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                int bytes = byteBufObj instanceof ByteBuf buf ? buf.readableBytes() : -1;
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from sending PacketEvents packet (" + bytes + " bytes)"
                        + " to player " + hookedPlayer.getName());
            }
            ReferenceCountUtil.safeRelease(byteBufObj);
            return true;
        }
        return false;
    }

    public boolean handlePacketEvent(Object listener, Object event, boolean outbound) {
        if (listener == null || event == null) {
            return false;
        }
        if (IGNORED_EVENT_SIMPLE_NAMES.contains(event.getClass().getSimpleName())) {
            return false;
        }
        if (isInternalPacketEventsListener(listener.getClass())) {
            return false;
        }

        Plugin plugin = listenerToPlugin.get(listener);
        if (plugin == null) {
            plugin = resolveListenerOwner(listener);
            if (plugin != null) {
                listenerToPlugin.put(listener, plugin);
            }
        }
        if (plugin == null || !pluginRegistry.isHooked(plugin)) {
            return false;
        }

        HookedPlayer hookedPlayer = resolveHookedPlayer(event);
        if (hookedPlayer == null) {
            return false;
        }

        if (!hookedPlayer.isPluginAllowed(plugin)) {
            return true;
        }

        if (config.packetEvents().isCallEvent()
                && listener instanceof PacketListenerCommon commonListener
                && event instanceof PacketEvent packetEvent) {
            PacketEventsHookEvent hookEvent = new PacketEventsHookEvent(
                    plugin,
                    hookedPlayer,
                    commonListener,
                    packetEvent,
                    outbound
            );
            Bukkit.getPluginManager().callEvent(hookEvent);
            return hookEvent.isCancelled();
        }
        return false;
    }

    /**
     * Resolves the owning {@link Plugin} of a PacketEvents listener, unwrapping {@code PacketListener$1.this$0}
     * when a plugin implements {@code PacketListener} and registers via {@code PacketListener#asAbstract}.
     */
    private Plugin resolveListenerOwner(Object listener) {
        Plugin owner = pluginRegistry.getPluginByClassLoader(listener.getClass().getClassLoader());
        if (owner != null && !owner.getName().equalsIgnoreCase("packetevents")) {
            return owner;
        }
        Optional<MethodHandle> unwrapOpt = UNWRAP_LISTENER_HANDLE.get(listener.getClass());
        if (unwrapOpt.isPresent()) {
            try {
                Object inner = unwrapOpt.get().invokeExact(listener);
                if (inner != null) {
                    Plugin innerOwner = pluginRegistry.getPluginByClassLoader(inner.getClass().getClassLoader());
                    if (innerOwner != null && !innerOwner.getName().equalsIgnoreCase("packetevents")) {
                        return innerOwner;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Never block PacketEvents' own internal state-tracking listeners.
     */
    private static boolean isInternalPacketEventsListener(Class<?> listenerClass) {
        String name = listenerClass.getName();
        String simpleName = listenerClass.getSimpleName();
        return name.contains(".packetevents.manager.")
                || name.contains(".packetevents.util.")
                || simpleName.equals("InternalPacketListener")
                || simpleName.equals("InternalBukkitPacketListener");
    }

    private HookedPlayer resolveHookedPlayer(Object event) {
        if (event instanceof ProtocolPacketEvent ppe) {
            if (ppe.getPlayer() instanceof Player p) {
                HookedPlayer hp = playerRegistry.getPlayer(p);
                if (hp != null) {
                    return hp;
                }
            }
            if (ppe.getChannel() instanceof Channel ch) {
                HookedPlayer hp = PlayerChannelResolver.getHookedPlayer(ch);
                if (hp != null) {
                    return hp;
                }
            }
            User user = ppe.getUser();
            if (user != null && user.getUUID() != null) {
                return playerRegistry.getPlayer(user.getUUID());
            }
            return null;
        }
        Player bukkitPlayer = extractPlayer(event);
        return bukkitPlayer != null ? playerRegistry.getPlayer(bukkitPlayer) : null;
    }

    private static Player extractPlayer(Object event) {
        Optional<MethodHandle> mhOpt = GET_PLAYER_HANDLE.get(event.getClass());
        if (mhOpt.isEmpty()) {
            return null;
        }
        try {
            Object playerObj = mhOpt.get().invokeExact(event);
            return playerObj instanceof Player p ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
