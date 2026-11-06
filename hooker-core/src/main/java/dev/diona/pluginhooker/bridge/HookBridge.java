package dev.diona.pluginhooker.bridge;

import java.lang.invoke.MethodHandle;

/**
 * Pure-JDK static bridge injected into the Bootstrap ClassLoader.
 * Accessible from every ClassLoader in the JVM (Server, Netty, ProtocolLib, PacketEvents, isolated Paper plugins).
 */
public final class HookBridge {

    /**
     * Signature: (int hookId, Object arg0, Object arg1, Object arg2) -> boolean
     * Returns true if the target execution should be blocked/cancelled.
     */
    public static volatile MethodHandle GUARD_DISPATCHER;

    /**
     * Signature: (Object returnValue, int hookId, Object receiver) -> Object
     * Filters or replaces the return value of a hooked API method.
     */
    public static volatile MethodHandle FILTER_DISPATCHER;

    /**
     * Signature: (int hookId, Object arg0, Object arg1) -> void
     * Notification callback for registration/lifecycle tracking.
     */
    public static volatile MethodHandle NOTIFY_DISPATCHER;

    /**
     * Signature: (int hookId, Object handler, Object ctx, Object msg, Object promise) -> void
     * Intercepts Netty channelRead / write invocations inside AbstractChannelHandlerContext.
     */
    public static volatile MethodHandle NETTY_DISPATCHER;

    private HookBridge() {
    }

    public static boolean checkGuard(int hookId, Object arg0, Object arg1, Object arg2) {
        MethodHandle mh = GUARD_DISPATCHER;
        if (mh == null) {
            return false;
        }
        try {
            return (boolean) mh.invokeExact(hookId, arg0, arg1, arg2);
        } catch (Throwable t) {
            return false;
        }
    }

    public static Object filterReturn(Object returnValue, int hookId, Object receiver) {
        MethodHandle mh = FILTER_DISPATCHER;
        if (mh == null) {
            return returnValue;
        }
        try {
            return mh.invokeExact(returnValue, hookId, receiver);
        } catch (Throwable t) {
            return returnValue;
        }
    }

    public static void onNotify(int hookId, Object arg0, Object arg1) {
        MethodHandle mh = NOTIFY_DISPATCHER;
        if (mh == null) {
            return;
        }
        try {
            mh.invokeExact(hookId, arg0, arg1);
        } catch (Throwable ignored) {
        }
    }

    public static void dispatchNettyRead(Object handler, Object ctx, Object msg) throws Throwable {
        MethodHandle mh = NETTY_DISPATCHER;
        if (mh != null) {
            mh.invokeExact(0, handler, ctx, msg, (Object) null);
        }
    }

    public static void dispatchNettyWrite(Object handler, Object ctx, Object msg, Object promise) throws Throwable {
        MethodHandle mh = NETTY_DISPATCHER;
        if (mh != null) {
            mh.invokeExact(1, handler, ctx, msg, promise);
        }
    }
}
