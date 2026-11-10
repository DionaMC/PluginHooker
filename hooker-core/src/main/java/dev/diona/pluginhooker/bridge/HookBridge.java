package dev.diona.pluginhooker.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.StackWalker;

/**
 * Pure-JDK static bridge injected into the Bootstrap ClassLoader.
 * Accessible from every ClassLoader in the JVM (Server, Netty, ProtocolLib, PacketEvents, isolated Paper plugins).
 */
public final class HookBridge {

    /**
     * Intrinsic-backed caller-class resolver invoked directly from instrumented ultra-hot methods
     * (such as NMS {@code sendPacket}/{@code send} and {@code disconnect}) without stream/frame allocation.
     */
    public static final StackWalker CALLER_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /**
     * Fast-path volatile flag indicating whether at least one plugin is currently registered in the sandbox.
     */
    public static volatile boolean HAS_HOOKED_PLUGINS;

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

    private static volatile MethodHandle FALLBACK_NETTY_READ;
    private static volatile MethodHandle FALLBACK_NETTY_WRITE;

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
        } else {
            invokeFallbackNettyRead(handler, ctx, msg);
        }
    }

    public static void dispatchNettyWrite(Object handler, Object ctx, Object msg, Object promise) throws Throwable {
        MethodHandle mh = NETTY_DISPATCHER;
        if (mh != null) {
            mh.invokeExact(1, handler, ctx, msg, promise);
        } else {
            invokeFallbackNettyWrite(handler, ctx, msg, promise);
        }
    }

    private static void invokeFallbackNettyRead(Object handler, Object ctx, Object msg) throws Throwable {
        MethodHandle fallback = FALLBACK_NETTY_READ;
        if (fallback == null) {
            ClassLoader nettyLoader = ctx.getClass().getClassLoader();
            Class<?> inboundClass = Class.forName("io.netty.channel.ChannelInboundHandler", false, nettyLoader);
            Class<?> ctxClass = Class.forName("io.netty.channel.ChannelHandlerContext", false, nettyLoader);
            fallback = MethodHandles.publicLookup()
                    .findVirtual(inboundClass, "channelRead", MethodType.methodType(void.class, ctxClass, Object.class))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, Object.class));
            FALLBACK_NETTY_READ = fallback;
        }
        fallback.invokeExact(handler, ctx, msg);
    }

    private static void invokeFallbackNettyWrite(Object handler, Object ctx, Object msg, Object promise) throws Throwable {
        MethodHandle fallback = FALLBACK_NETTY_WRITE;
        if (fallback == null) {
            ClassLoader nettyLoader = ctx.getClass().getClassLoader();
            Class<?> outboundClass = Class.forName("io.netty.channel.ChannelOutboundHandler", false, nettyLoader);
            Class<?> ctxClass = Class.forName("io.netty.channel.ChannelHandlerContext", false, nettyLoader);
            Class<?> promiseClass = Class.forName("io.netty.channel.ChannelPromise", false, nettyLoader);
            fallback = MethodHandles.publicLookup()
                    .findVirtual(outboundClass, "write", MethodType.methodType(void.class, ctxClass, Object.class, promiseClass))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, Object.class, Object.class));
            FALLBACK_NETTY_WRITE = fallback;
        }
        fallback.invokeExact(handler, ctx, msg, promise);
    }
}
