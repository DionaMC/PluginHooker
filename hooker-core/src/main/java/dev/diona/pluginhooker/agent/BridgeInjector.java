package dev.diona.pluginhooker.agent;

import dev.diona.pluginhooker.bridge.HookBridge;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Injects {@link HookBridge} into the Bootstrap ClassLoader so that any patched class
 * across any ClassLoader can invoke bridge methods directly without reflection or visibility errors.
 */
public final class BridgeInjector {

    private static final String BRIDGE_CLASS_NAME = "dev.diona.pluginhooker.bridge.HookBridge";
    private static final String BRIDGE_RESOURCE_PATH = "/dev/diona/pluginhooker/bridge/HookBridge.class";

    private static boolean injected = false;

    private BridgeInjector() {
    }

    public static synchronized void injectAndBind(
            Instrumentation instrumentation,
            MethodHandle guardDispatcher,
            MethodHandle filterDispatcher,
            MethodHandle notifyDispatcher,
            MethodHandle nettyDispatcher
    ) throws Exception {
        if (!injected) {
            Path tempJar = Files.createTempFile("pluginhooker-bridge-", ".jar");
            tempJar.toFile().deleteOnExit();

            try (InputStream in = BridgeInjector.class.getResourceAsStream(BRIDGE_RESOURCE_PATH)) {
                if (in == null) {
                    throw new IllegalStateException("Could not locate " + BRIDGE_RESOURCE_PATH);
                }
                byte[] classBytes = in.readAllBytes();
                try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar))) {
                    JarEntry entry = new JarEntry("dev/diona/pluginhooker/bridge/HookBridge.class");
                    jos.putNextEntry(entry);
                    jos.write(classBytes);
                    jos.closeEntry();
                }
            }

            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJar.toFile()));
            injected = true;
        }

        // Bind dispatchers on the Bootstrap ClassLoader copy
        Class<?> bootstrapBridge = Class.forName(BRIDGE_CLASS_NAME, true, null);
        bindHandles(bootstrapBridge, guardDispatcher, filterDispatcher, notifyDispatcher, nettyDispatcher);

        // Also bind on local class if loaded separately by a child-first PluginClassLoader
        if (bootstrapBridge != HookBridge.class) {
            bindHandles(HookBridge.class, guardDispatcher, filterDispatcher, notifyDispatcher, nettyDispatcher);
        }
    }

    public static synchronized void unbind() {
        try {
            Class<?> bootstrapBridge = Class.forName(BRIDGE_CLASS_NAME, false, null);
            bindHandles(bootstrapBridge, null, null, null, null);
            if (bootstrapBridge != HookBridge.class) {
                bindHandles(HookBridge.class, null, null, null, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void bindHandles(
            Class<?> bridgeClass,
            MethodHandle guardDispatcher,
            MethodHandle filterDispatcher,
            MethodHandle notifyDispatcher,
            MethodHandle nettyDispatcher
    ) throws ReflectiveOperationException {
        bridgeClass.getField("GUARD_DISPATCHER").set(null, guardDispatcher);
        bridgeClass.getField("FILTER_DISPATCHER").set(null, filterDispatcher);
        bridgeClass.getField("NOTIFY_DISPATCHER").set(null, notifyDispatcher);
        bridgeClass.getField("NETTY_DISPATCHER").set(null, nettyDispatcher);
    }
}
