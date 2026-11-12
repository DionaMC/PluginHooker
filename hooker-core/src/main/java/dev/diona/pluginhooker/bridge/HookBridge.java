package dev.diona.pluginhooker.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

/**
 * Pure-JDK static bridge injected into the Bootstrap ClassLoader.
 * <p>
 * Backed by {@link MutableCallSite}s and {@code static final} dynamic invokers so HotSpot C2 can inline
 * all bridge dispatches across ClassLoader boundaries without volatile read barriers or null checks.
 */
public final class HookBridge {

    /**
     * Fast-path volatile flag indicating whether at least one plugin is currently registered in the sandbox.
     */
    public static volatile boolean HAS_HOOKED_PLUGINS;

    /**
     * C2-intrinsified single-hop caller resolver ({@code JVM_GetCallerClass}) invoked directly from
     * instrumented NMS entry points to avoid {@link StackWalker#walk} on non-reflective calls.
     */
    public static final StackWalker CALLER_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final MethodType GUARD_TYPE =
            MethodType.methodType(boolean.class, int.class, Object.class, Object.class, Object.class);
    private static final MethodType FILTER_TYPE =
            MethodType.methodType(Object.class, Object.class, int.class, Object.class);
    private static final MethodType NOTIFY_TYPE =
            MethodType.methodType(void.class, int.class, Object.class, Object.class);
    private static final MethodType NETTY_TYPE =
            MethodType.methodType(void.class, int.class, Object.class, Object.class, Object.class, Object.class);

    private static final MethodHandle DEFAULT_GUARD;
    private static final MethodHandle DEFAULT_FILTER;
    private static final MethodHandle DEFAULT_NOTIFY;
    private static final MethodHandle DEFAULT_NETTY;

    private static final MutableCallSite GUARD_SITE;
    private static final MutableCallSite FILTER_SITE;
    private static final MutableCallSite NOTIFY_SITE;
    private static final MutableCallSite NETTY_SITE;

    private static final MethodHandle GUARD_INVOKER;
    private static final MethodHandle FILTER_INVOKER;
    private static final MethodHandle NOTIFY_INVOKER;
    private static final MethodHandle NETTY_INVOKER;

    private static volatile MethodHandle FALLBACK_NETTY_READ;
    private static volatile MethodHandle FALLBACK_NETTY_WRITE;

    static {
        try {
            DEFAULT_GUARD = MethodHandles.dropArguments(
                    MethodHandles.constant(boolean.class, false),
                    0,
                    int.class, Object.class, Object.class, Object.class
            );
            DEFAULT_FILTER = MethodHandles.dropArguments(
                    MethodHandles.identity(Object.class),
                    1,
                    int.class, Object.class
            );
            DEFAULT_NOTIFY = MethodHandles.empty(NOTIFY_TYPE);
            DEFAULT_NETTY = MethodHandles.lookup().findStatic(
                    HookBridge.class,
                    "fallbackNettyDispatch",
                    NETTY_TYPE
            );

            GUARD_SITE = new MutableCallSite(DEFAULT_GUARD);
            FILTER_SITE = new MutableCallSite(DEFAULT_FILTER);
            NOTIFY_SITE = new MutableCallSite(DEFAULT_NOTIFY);
            NETTY_SITE = new MutableCallSite(DEFAULT_NETTY);

            GUARD_INVOKER = GUARD_SITE.dynamicInvoker();
            FILTER_INVOKER = FILTER_SITE.dynamicInvoker();
            NOTIFY_INVOKER = NOTIFY_SITE.dynamicInvoker();
            NETTY_INVOKER = NETTY_SITE.dynamicInvoker();
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private HookBridge() {
    }

    public static synchronized void bindDispatchers(
            MethodHandle guardDispatcher,
            MethodHandle filterDispatcher,
            MethodHandle notifyDispatcher,
            MethodHandle nettyDispatcher
    ) {
        GUARD_SITE.setTarget(guardDispatcher != null ? guardDispatcher.asType(GUARD_TYPE) : DEFAULT_GUARD);
        FILTER_SITE.setTarget(filterDispatcher != null ? filterDispatcher.asType(FILTER_TYPE) : DEFAULT_FILTER);
        NOTIFY_SITE.setTarget(notifyDispatcher != null ? notifyDispatcher.asType(NOTIFY_TYPE) : DEFAULT_NOTIFY);
        NETTY_SITE.setTarget(nettyDispatcher != null ? nettyDispatcher.asType(NETTY_TYPE) : DEFAULT_NETTY);
        MutableCallSite.syncAll(new MutableCallSite[]{GUARD_SITE, FILTER_SITE, NOTIFY_SITE, NETTY_SITE});
    }

    public static synchronized void unbindDispatchers() {
        HAS_HOOKED_PLUGINS = false;
        bindDispatchers(null, null, null, null);
    }

    public static boolean isBound() {
        return NETTY_SITE.getTarget() != DEFAULT_NETTY;
    }

    public static boolean checkGuard(int hookId, Object arg0, Object arg1, Object arg2) {
        try {
            return (boolean) GUARD_INVOKER.invokeExact(hookId, arg0, arg1, arg2);
        } catch (Throwable t) {
            return false;
        }
    }

    public static Object filterReturn(Object returnValue, int hookId, Object receiver) {
        try {
            return FILTER_INVOKER.invokeExact(returnValue, hookId, receiver);
        } catch (Throwable t) {
            return returnValue;
        }
    }

    public static void onNotify(int hookId, Object arg0, Object arg1) {
        try {
            NOTIFY_INVOKER.invokeExact(hookId, arg0, arg1);
        } catch (Throwable ignored) {
        }
    }

    public static void dispatchNettyRead(Object handler, Object ctx, Object msg) throws Throwable {
        NETTY_INVOKER.invokeExact(0, handler, ctx, msg, (Object) null);
    }

    public static void dispatchNettyWrite(Object handler, Object ctx, Object msg, Object promise) throws Throwable {
        NETTY_INVOKER.invokeExact(1, handler, ctx, msg, promise);
    }

    @SuppressWarnings("unused")
    private static void fallbackNettyDispatch(
            int isOutbound,
            Object handler,
            Object ctx,
            Object msg,
            Object promise
    ) throws Throwable {
        if (isOutbound != 0) {
            invokeFallbackNettyWrite(handler, ctx, msg, promise);
        } else {
            invokeFallbackNettyRead(handler, ctx, msg);
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
