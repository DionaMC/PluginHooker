package dev.diona.pluginhooker.util;

import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.protocol.player.User;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Lock-free {@link ClassValue} and {@link MethodHandle} reflection helper for standalone and shaded PacketEvents.
 */
public final class PacketEventsReflector {

    private record ShadedEventAccessors(
            MethodHandle getPlayer,
            MethodHandle getChannel,
            MethodHandle getUser,
            MethodHandle getByteBuf,
            MethodHandle setByteBuf,
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

    private static final ClassValue<MethodHandle> UNWRAP_LISTENER_HANDLE = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> listenerClass) {
            try {
                Field f = listenerClass.getDeclaredField("this$0");
                f.setAccessible(true);
                return MethodHandles.lookup()
                        .unreflectGetter(f)
                        .asType(MethodType.methodType(Object.class, Object.class));
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    };

    private static final ClassValue<MethodHandle> EVENT_MANAGER_LISTENERS_GETTER = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> emClass) {
            try {
                Field f = emClass.getDeclaredField("listeners");
                if (Object[].class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return MethodHandles.lookup()
                            .unreflectGetter(f)
                            .asType(MethodType.methodType(Object[].class, Object.class));
                }
            } catch (ReflectiveOperationException ignored) {
            }
            return null;
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
                    findNoArgGetter(lookup, eventClass, "getByteBuf"),
                    findOneArgSetter(lookup, eventClass, "setByteBuf"),
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

    private static final ClassValue<MethodHandle> SHADED_USER_UUID_GETTER = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> userClass) {
            return findNoArgGetter(MethodHandles.lookup(), userClass, "getUUID");
        }
    };

    private PacketEventsReflector() {
    }

    /**
     * Unwraps {@code PacketListener$1.this$0} when a plugin registers via {@code PacketListener#asAbstract}.
     */
    public static Object unwrapListener(Object listener) {
        MethodHandle unwrap = UNWRAP_LISTENER_HANDLE.get(listener.getClass());
        if (unwrap == null) {
            return null;
        }
        try {
            return unwrap.invokeExact(listener);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Extracts the snapshot {@code listeners} array from a standalone or shaded PacketEvents {@code EventManager}.
     */
    public static Object[] extractListenersArray(Object eventManager) {
        MethodHandle getter = EVENT_MANAGER_LISTENERS_GETTER.get(eventManager.getClass());
        if (getter == null) {
            return null;
        }
        try {
            return (Object[]) getter.invokeExact(eventManager);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolves the {@link HookedPlayer} associated with a standalone or shaded PacketEvents packet event.
     */
    public static HookedPlayer resolveEventPlayer(Object event, PlayerRegistryImpl playerRegistry) {
        if (event instanceof ProtocolPacketEvent ppe) {
            Object channelObj = ppe.getChannel();
            HookedPlayer direct = resolveChannelOrBukkitPlayer(channelObj, ppe.getPlayer(), playerRegistry);
            if (direct != null || ppe.getPlayer() instanceof Player || isNonRealChannel(channelObj)) {
                return direct;
            }
            User user = ppe.getUser();
            return user != null ? playerRegistry.getPlayer(user.getUUID()) : null;
        }

        ShadedEventAccessors accessors = SHADED_EVENT_ACCESSORS.get(event.getClass());
        try {
            Object channelObj = accessors.getChannel() != null ? accessors.getChannel().invokeExact(event) : null;
            Object playerObj = accessors.getPlayer() != null ? accessors.getPlayer().invokeExact(event) : null;
            HookedPlayer direct = resolveChannelOrBukkitPlayer(channelObj, playerObj, playerRegistry);
            if (direct != null || playerObj instanceof Player || isNonRealChannel(channelObj)) {
                return direct;
            }
            if (accessors.getUser() != null) {
                return playerRegistry.getPlayer(extractShadedUserUuid(accessors.getUser().invokeExact(event)));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static HookedPlayer resolveChannelOrBukkitPlayer(
            Object channelObj,
            Object playerObj,
            PlayerRegistryImpl playerRegistry
    ) {
        if (channelObj instanceof Channel ch) {
            HookedPlayer hp = PlayerChannelResolver.getHookedPlayer(ch);
            if (hp != null) {
                return hp;
            }
        }
        return playerObj instanceof Player p ? playerRegistry.getPlayer(p) : null;
    }

    private static boolean isNonRealChannel(Object channelObj) {
        return channelObj instanceof Channel ch && !PlayerChannelResolver.isRealNetworkChannel(ch);
    }

    /**
     * Extracts the raw Bukkit {@link Player} (if any) from a standalone or shaded PacketEvents packet event.
     */
    public static Player extractEventBukkitPlayer(Object event) {
        if (event instanceof ProtocolPacketEvent ppe) {
            return ppe.getPlayer() instanceof Player p ? p : null;
        }
        ShadedEventAccessors accessors = SHADED_EVENT_ACCESSORS.get(event.getClass());
        if (accessors.getPlayer() == null) {
            return null;
        }
        try {
            Object playerObj = accessors.getPlayer().invokeExact(event);
            return playerObj instanceof Player p ? p : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static UUID extractShadedUserUuid(Object userObj) {
        if (userObj == null) {
            return null;
        }
        MethodHandle uuidGetter = SHADED_USER_UUID_GETTER.get(userObj.getClass());
        if (uuidGetter == null) {
            return null;
        }
        try {
            Object uuidObj = uuidGetter.invokeExact(userObj);
            return uuidObj instanceof UUID uuid ? uuid : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Executes a disabled plugin's PacketEvents listener (standalone or shaded) in read-only mode during
     * the join grace period so it can observe join initialization packets without cancelling, mutating
     * the buffer, polluting wrappers, re-encoding, or queuing post-send tasks.
     */
    @SuppressWarnings("rawtypes")
    public static void invokeReadOnlyListener(Object listener, Object event, boolean outbound) {
        ShadedEventAccessors acc = SHADED_EVENT_ACCESSORS.get(event.getClass());
        if (acc.invokeListener() == null) {
            return;
        }
        try {
            boolean wasCancelled = acc.isCancelled() != null && (boolean) acc.isCancelled().invokeExact(event);
            boolean wasNeedsReEncode = acc.needsReEncode() != null && (boolean) acc.needsReEncode().invokeExact(event);
            Object prevWrapper = acc.getLastUsedWrapper() != null ? acc.getLastUsedWrapper().invokeExact(event) : null;
            Object prevBufObj = acc.getByteBuf() != null ? acc.getByteBuf().invokeExact(event) : null;
            ByteBuf prevBuf = prevBufObj instanceof ByteBuf b ? b : null;
            int prevReaderIndex = prevBuf != null ? prevBuf.readerIndex() : 0;
            int prevWriterIndex = prevBuf != null ? prevBuf.writerIndex() : 0;
            ByteBuf readOnlyView = prevBuf != null && acc.setByteBuf() != null
                    ? Unpooled.unmodifiableBuffer(prevBuf.duplicate())
                    : null;

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

            if (readOnlyView != null) {
                acc.setByteBuf().invokeExact(event, (Object) readOnlyView);
            }
            if (prevWrapper != null && acc.setLastUsedWrapper() != null) {
                acc.setLastUsedWrapper().invokeExact(event, (Object) null);
            }
            try {
                acc.invokeListener().invokeExact(listener, event);
            } catch (Throwable ignored) {
            } finally {
                if (acc.getByteBuf() != null && acc.setByteBuf() != null) {
                    Object currentBufObj = acc.getByteBuf().invokeExact(event);
                    if (currentBufObj != prevBufObj) {
                        if (currentBufObj != readOnlyView) {
                            ReferenceCountUtil.safeRelease(currentBufObj);
                        }
                        acc.setByteBuf().invokeExact(event, prevBufObj);
                    }
                }
                if (prevBuf != null && prevBuf.refCnt() > 0) {
                    prevBuf.setIndex(prevReaderIndex, prevWriterIndex);
                }
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
}
