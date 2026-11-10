package dev.diona.pluginhooker.util;

import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lock-free {@link ClassValue} and {@link MethodHandle} reflection helper for standalone and shaded PacketEvents.
 */
public final class PacketEventsReflector {

    public record ShadedEventAccessors(
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

    private static final ClassValue<Optional<MethodHandle>> SHADED_USER_UUID_GETTER = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> userClass) {
            return Optional.ofNullable(findNoArgGetter(MethodHandles.lookup(), userClass, "getUUID"));
        }
    };

    private PacketEventsReflector() {
    }

    public static ShadedEventAccessors getAccessors(Class<?> eventClass) {
        return SHADED_EVENT_ACCESSORS.get(eventClass);
    }

    /**
     * Unwraps {@code PacketListener$1.this$0} when a plugin registers via {@code PacketListener#asAbstract}.
     */
    public static Object unwrapListener(Object listener) {
        Optional<MethodHandle> unwrapOpt = UNWRAP_LISTENER_HANDLE.get(listener.getClass());
        if (unwrapOpt.isEmpty()) {
            return null;
        }
        try {
            return unwrapOpt.get().invokeExact(listener);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static UUID extractShadedUserUuid(Object userObj) {
        if (userObj == null) {
            return null;
        }
        Optional<MethodHandle> uuidGetter = SHADED_USER_UUID_GETTER.get(userObj.getClass());
        if (uuidGetter.isEmpty()) {
            return null;
        }
        try {
            Object uuidObj = uuidGetter.get().invokeExact(userObj);
            return uuidObj instanceof UUID uuid ? uuid : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Executes a disabled plugin's PacketEvents listener in read-only mode during the join grace period
     * so it can observe join initialization packets without cancelling, re-encoding, or queuing post-send tasks.
     */
    @SuppressWarnings("rawtypes")
    public static void invokeReadOnlyListener(Object listener, Object event, boolean outbound) {
        if (event instanceof ProtocolPacketEvent ppe && listener instanceof PacketListenerCommon) {
            MethodHandle invoker = SHADED_EVENT_ACCESSORS.get(event.getClass()).invokeListener();
            if (invoker == null) {
                return;
            }
            boolean wasCancelled = ppe.isCancelled();
            boolean wasNeedsReEncode = ppe.needsReEncode();
            PacketWrapper<?> prevWrapper = ppe.getLastUsedWrapper();
            Object prevBufObj = ppe.getByteBuf();
            ByteBuf prevBuf = prevBufObj instanceof ByteBuf b ? b : null;
            int prevReaderIndex = prevBuf != null ? prevBuf.readerIndex() : 0;
            int prevWriterIndex = prevBuf != null ? prevBuf.writerIndex() : 0;
            ByteBuf readOnlyView = prevBuf != null ? Unpooled.unmodifiableBuffer(prevBuf.duplicate()) : null;

            int prevPostTasksSize = ppe.hasPostTasks() ? ppe.getPostTasks().size() : 0;
            PacketSendEvent pse = outbound && event instanceof PacketSendEvent s ? s : null;
            int prevAfterSendSize = pse != null && pse.hasTasksAfterSend() ? pse.getTasksAfterSend().size() : 0;

            if (readOnlyView != null) {
                ppe.setByteBuf(readOnlyView);
            }
            if (prevWrapper != null) {
                ppe.setLastUsedWrapper(null);
            }
            try {
                invoker.invokeExact(listener, event);
            } catch (Throwable ignored) {
            } finally {
                Object currentBufObj = ppe.getByteBuf();
                if (currentBufObj != prevBufObj) {
                    if (currentBufObj != readOnlyView) {
                        ReferenceCountUtil.safeRelease(currentBufObj);
                    }
                    ppe.setByteBuf(prevBufObj);
                }
                if (prevBuf != null && prevBuf.refCnt() > 0) {
                    prevBuf.setIndex(prevReaderIndex, prevWriterIndex);
                }
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
