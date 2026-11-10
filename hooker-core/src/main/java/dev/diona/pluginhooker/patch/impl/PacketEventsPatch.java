package dev.diona.pluginhooker.patch.impl;

import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
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

    private record ShadedEventAccessors(
            MethodHandle getPlayer,
            MethodHandle getChannel,
            MethodHandle getUser,
            MethodHandle isCancelled,
            MethodHandle setCancelled,
            MethodHandle needsReEncode,
            MethodHandle markForReEncode,
            MethodHandle getLastUsedWrapper,
            MethodHandle setLastUsedWrapper,
            MethodHandle hasPostTasks,
            MethodHandle getPostTasks,
            MethodHandle hasTasksAfterSend,
            MethodHandle getTasksAfterSend,
            MethodHandle invokeListener
    ) {
    }

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

    private static final ClassValue<ShadedEventAccessors> SHADED_EVENT_ACCESSORS = new ClassValue<>() {
        @Override
        protected ShadedEventAccessors computeValue(Class<?> eventClass) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            return new ShadedEventAccessors(
                    findNoArgGetter(lookup, eventClass, "getPlayer"),
                    findNoArgGetter(lookup, eventClass, "getChannel"),
                    findNoArgGetter(lookup, eventClass, "getUser"),
                    findBooleanGetter(lookup, eventClass, "isCancelled"),
                    findBooleanSetter(lookup, eventClass, "setCancelled"),
                    findBooleanGetter(lookup, eventClass, "needsReEncode"),
                    findBooleanSetter(lookup, eventClass, "markForReEncode"),
                    findNoArgGetter(lookup, eventClass, "getLastUsedWrapper"),
                    findOneArgSetter(lookup, eventClass, "setLastUsedWrapper"),
                    findBooleanGetter(lookup, eventClass, "hasPostTasks"),
                    findListGetter(lookup, eventClass, "getPostTasks"),
                    findBooleanGetter(lookup, eventClass, "hasTasksAfterSend"),
                    findListGetter(lookup, eventClass, "getTasksAfterSend"),
                    findListenerInvoker(lookup, eventClass)
            );
        }
    };

    private static final ClassValue<Optional<MethodHandle>> SHADED_USER_UUID_GETTER = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> userClass) {
            return Optional.ofNullable(findNoArgGetter(MethodHandles.lookup(), userClass, "getUUID"));
        }
    };

    private static MethodHandle findNoArgGetter(MethodHandles.Lookup lookup, Class<?> clazz, String name) {
        try {
            Method m = clazz.getMethod(name);
            m.setAccessible(true);
            return lookup.unreflect(m).asType(MethodType.methodType(Object.class, Object.class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static MethodHandle findBooleanGetter(MethodHandles.Lookup lookup, Class<?> clazz, String name) {
        try {
            Method m = clazz.getMethod(name);
            m.setAccessible(true);
            return lookup.unreflect(m).asType(MethodType.methodType(boolean.class, Object.class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static MethodHandle findBooleanSetter(MethodHandles.Lookup lookup, Class<?> clazz, String name) {
        try {
            Method m = clazz.getMethod(name, boolean.class);
            m.setAccessible(true);
            return lookup.unreflect(m).asType(MethodType.methodType(void.class, Object.class, boolean.class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static MethodHandle findOneArgSetter(MethodHandles.Lookup lookup, Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                try {
                    m.setAccessible(true);
                    return lookup.unreflect(m).asType(MethodType.methodType(void.class, Object.class, Object.class));
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    private static MethodHandle findListGetter(MethodHandles.Lookup lookup, Class<?> clazz, String name) {
        try {
            Method m = clazz.getMethod(name);
            if (List.class.isAssignableFrom(m.getReturnType())) {
                m.setAccessible(true);
                return lookup.unreflect(m).asType(MethodType.methodType(List.class, Object.class));
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static MethodHandle findListenerInvoker(MethodHandles.Lookup lookup, Class<?> eventClass) {
        for (Method callMethod : eventClass.getMethods()) {
            if (callMethod.getName().equals("call") && callMethod.getParameterCount() == 1) {
                Class<?> baseEventClass = callMethod.getDeclaringClass();
                String methodName = baseEventClass.getSimpleName().equals("PacketSendEvent")
                        ? "onPacketSend"
                        : "onPacketReceive";
                Class<?> listenerClass = callMethod.getParameterTypes()[0];
                try {
                    Method m = listenerClass.getDeclaredMethod(methodName, baseEventClass);
                    m.setAccessible(true);
                    return lookup.unreflect(m).asType(MethodType.methodType(void.class, Object.class, Object.class));
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

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
        return internalClassName.endsWith(EVENT_MANAGER_SUFFIX)
                || internalClassName.endsWith(PACKET_RECEIVE_SUFFIX)
                || internalClassName.endsWith(PACKET_SEND_SUFFIX)
                || isProtocolManagerClass(internalClassName);
    }

    private static boolean isProtocolManagerClass(String internalClassName) {
        return internalClassName.endsWith(PROTOCOL_MANAGER_IMPL_SUFFIX);
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

        if (internalClassName.endsWith(EVENT_MANAGER_SUFFIX)) {
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
        if (listener == null || isInternalPacketEventsListener(listener.getClass())) {
            return;
        }
        Plugin owner = resolveListenerOwner(listener);
        if (owner == null) {
            owner = PluginCallerResolver.resolveCallerPlugin(pluginRegistry, selfClassLoader);
        }
        if (owner != null && !PluginCallerResolver.isSharedPacketFramework(owner)) {
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
                    playerRegistry.registerPlayer(hookedPlayer.getBukkitPlayer());
                }
            }
        }
        if (hookedPlayer == null) {
            hookedPlayer = PlayerChannelResolver.resolveHookedPlayer(channel, playerRegistry);
            if (hookedPlayer == null) {
                return false;
            }
        }
        Plugin caller = PluginCallerResolver.resolveDirectPacketEventsSendCaller(pluginRegistry, selfClassLoader);
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
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
        if (IGNORED_EVENT_CACHE.get(event.getClass())) {
            return false;
        }
        if (isInternalPacketEventsListener(listener.getClass())) {
            return false;
        }

        Plugin plugin = resolveListenerOwner(listener);
        if (plugin == null) {
            plugin = listenerToPlugin.get(listener);
        }
        if (plugin == null || !pluginRegistry.isHooked(plugin)) {
            return false;
        }

        HookedPlayer hookedPlayer = resolveHookedPlayer(event);
        if (hookedPlayer == null) {
            return false;
        }

        if (!hookedPlayer.isPluginEnabled(plugin)) {
            if (hookedPlayer.isInJoinGracePeriod()) {
                invokeReadOnlyListener(listener, event, outbound);
            }
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
     * Executes a disabled plugin's PacketEvents listener in read-only mode during the join grace period
     * so it can observe join initialization packets without cancelling, re-encoding, or queuing post-send tasks.
     */
    @SuppressWarnings("rawtypes")
    private static void invokeReadOnlyListener(Object listener, Object event, boolean outbound) {
        if (event instanceof ProtocolPacketEvent ppe && listener instanceof PacketListenerCommon) {
            MethodHandle invoker = SHADED_EVENT_ACCESSORS.get(event.getClass()).invokeListener();
            if (invoker == null) {
                return;
            }
            boolean wasCancelled = ppe.isCancelled();
            boolean wasNeedsReEncode = ppe.needsReEncode();
            PacketWrapper<?> prevWrapper = ppe.getLastUsedWrapper();
            int prevPostTasksSize = ppe.hasPostTasks() ? ppe.getPostTasks().size() : 0;
            PacketSendEvent pse = outbound && event instanceof PacketSendEvent s ? s : null;
            int prevAfterSendSize = pse != null && pse.hasTasksAfterSend() ? pse.getTasksAfterSend().size() : 0;
            try {
                invoker.invokeExact(listener, event);
            } catch (Throwable ignored) {
            } finally {
                if (ppe.isCancelled() != wasCancelled) {
                    ppe.setCancelled(wasCancelled);
                }
                ppe.setLastUsedWrapper(prevWrapper);
                ppe.markForReEncode(wasNeedsReEncode);
                if (ppe.hasPostTasks()) {
                    trimList(ppe.getPostTasks(), prevPostTasksSize);
                }
                if (pse != null && pse.hasTasksAfterSend()) {
                    trimList(pse.getTasksAfterSend(), prevAfterSendSize);
                }
            }
            return;
        }

        ShadedEventAccessors acc = SHADED_EVENT_ACCESSORS.get(event.getClass());
        if (acc.invokeListener() == null) {
            return;
        }
        try {
            boolean wasCancelled = acc.isCancelled() != null && (boolean) acc.isCancelled().invokeExact(event);
            boolean wasNeedsReEncode = acc.needsReEncode() != null && (boolean) acc.needsReEncode().invokeExact(event);
            Object prevWrapper = acc.getLastUsedWrapper() != null ? acc.getLastUsedWrapper().invokeExact(event) : null;
            int prevPostTasksSize = acc.hasPostTasks() != null
                    && acc.getPostTasks() != null
                    && (boolean) acc.hasPostTasks().invokeExact(event)
                    ? ((List) acc.getPostTasks().invokeExact(event)).size()
                    : 0;
            int prevAfterSendSize = outbound
                    && acc.hasTasksAfterSend() != null
                    && acc.getTasksAfterSend() != null
                    && (boolean) acc.hasTasksAfterSend().invokeExact(event)
                    ? ((List) acc.getTasksAfterSend().invokeExact(event)).size()
                    : 0;
            try {
                acc.invokeListener().invokeExact(listener, event);
            } catch (Throwable ignored) {
            } finally {
                if (acc.isCancelled() != null && acc.setCancelled() != null) {
                    boolean nowCancelled = (boolean) acc.isCancelled().invokeExact(event);
                    if (nowCancelled != wasCancelled) {
                        acc.setCancelled().invokeExact(event, wasCancelled);
                    }
                }
                if (acc.setLastUsedWrapper() != null) {
                    acc.setLastUsedWrapper().invokeExact(event, prevWrapper);
                }
                if (acc.markForReEncode() != null) {
                    acc.markForReEncode().invokeExact(event, wasNeedsReEncode);
                }
                if (acc.hasPostTasks() != null
                        && acc.getPostTasks() != null
                        && (boolean) acc.hasPostTasks().invokeExact(event)) {
                    trimList((List) acc.getPostTasks().invokeExact(event), prevPostTasksSize);
                }
                if (outbound
                        && acc.hasTasksAfterSend() != null
                        && acc.getTasksAfterSend() != null
                        && (boolean) acc.hasTasksAfterSend().invokeExact(event)) {
                    trimList((List) acc.getTasksAfterSend().invokeExact(event), prevAfterSendSize);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void trimList(List<?> list, int targetSize) {
        if (list == null) {
            return;
        }
        while (list.size() > targetSize) {
            list.remove(list.size() - 1);
        }
    }

    /**
     * Resolves the owning {@link Plugin} of a PacketEvents listener, unwrapping {@code PacketListener$1.this$0}
     * when a plugin implements {@code PacketListener} and registers via {@code PacketListener#asAbstract}.
     */
    private Plugin resolveListenerOwner(Object listener) {
        Plugin owner = pluginRegistry.getPluginByClassLoader(listener.getClass().getClassLoader());
        if (owner != null && !PluginCallerResolver.isSharedPacketFramework(owner)) {
            return owner;
        }
        Optional<MethodHandle> unwrapOpt = UNWRAP_LISTENER_HANDLE.get(listener.getClass());
        if (unwrapOpt.isPresent()) {
            try {
                Object inner = unwrapOpt.get().invokeExact(listener);
                if (inner != null) {
                    Plugin innerOwner = pluginRegistry.getPluginByClassLoader(inner.getClass().getClassLoader());
                    if (innerOwner != null && !PluginCallerResolver.isSharedPacketFramework(innerOwner)) {
                        return innerOwner;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Never block PacketEvents' own internal state-tracking listeners (in both standalone and shaded instances).
     */
    private static boolean isInternalPacketEventsListener(Class<?> listenerClass) {
        return INTERNAL_LISTENER_CACHE.get(listenerClass);
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
                HookedPlayer hp = PlayerChannelResolver.resolveHookedPlayer(ch, playerRegistry);
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

        // Shaded/relocated PacketEvents event instance
        ShadedEventAccessors accessors = SHADED_EVENT_ACCESSORS.get(event.getClass());
        try {
            if (accessors.getPlayer() != null) {
                Object playerObj = accessors.getPlayer().invokeExact(event);
                if (playerObj instanceof Player p) {
                    HookedPlayer hp = playerRegistry.getPlayer(p);
                    if (hp != null) {
                        return hp;
                    }
                }
            }
            if (accessors.getChannel() != null) {
                Object channelObj = accessors.getChannel().invokeExact(event);
                if (channelObj instanceof Channel ch) {
                    HookedPlayer hp = PlayerChannelResolver.resolveHookedPlayer(ch, playerRegistry);
                    if (hp != null) {
                        return hp;
                    }
                }
            }
            if (accessors.getUser() != null) {
                Object userObj = accessors.getUser().invokeExact(event);
                if (userObj != null) {
                    Optional<MethodHandle> uuidGetter = SHADED_USER_UUID_GETTER.get(userObj.getClass());
                    if (uuidGetter.isPresent()) {
                        Object uuidObj = uuidGetter.get().invokeExact(userObj);
                        if (uuidObj instanceof UUID uuid) {
                            return playerRegistry.getPlayer(uuid);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
