package dev.diona.pluginhooker.agent;

import dev.diona.pluginhooker.bridge.HookBridge;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
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
            byte[] classBytes;
            try (InputStream in = BridgeInjector.class.getResourceAsStream(BRIDGE_RESOURCE_PATH)) {
                if (in == null) {
                    throw new IllegalStateException("Could not locate " + BRIDGE_RESOURCE_PATH);
                }
                classBytes = in.readAllBytes();
            }

            byte[] digest = MessageDigest.getInstance("SHA-256").digest(classBytes);
            String hashSuffix = HexFormat.of().formatHex(digest, 0, 8);
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
            Path bridgeJar = tempDir.resolve("pluginhooker-bridge-" + hashSuffix + ".jar");

            if (!Files.isRegularFile(bridgeJar) || Files.size(bridgeJar) == 0L) {
                Path stagingJar = Files.createTempFile(tempDir, "pluginhooker-bridge-tmp-", ".jar");
                try {
                    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(stagingJar))) {
                        JarEntry entry = new JarEntry("dev/diona/pluginhooker/bridge/HookBridge.class");
                        jos.putNextEntry(entry);
                        jos.write(classBytes);
                        jos.closeEntry();
                    }
                    try {
                        Files.move(stagingJar, bridgeJar, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception moveEx) {
                        if (!Files.isRegularFile(bridgeJar)) {
                            bridgeJar = stagingJar;
                        }
                    }
                } finally {
                    if (!stagingJar.equals(bridgeJar)) {
                        Files.deleteIfExists(stagingJar);
                    }
                }
            }

            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(bridgeJar.toFile()));
            injected = true;
        }

        // Bind dispatchers on the Bootstrap ClassLoader copy
        Class<?> bootstrapBridge = Class.forName(BRIDGE_CLASS_NAME, true, null);
        bootstrapBridge.getMethod(
                "bindDispatchers",
                MethodHandle.class,
                MethodHandle.class,
                MethodHandle.class,
                MethodHandle.class
        ).invoke(null, guardDispatcher, filterDispatcher, notifyDispatcher, nettyDispatcher);
        bootstrapBridge.getField("HAS_HOOKED_PLUGINS").setBoolean(null, HookBridge.HAS_HOOKED_PLUGINS);

        // Also bind on local class if loaded separately by a child-first PluginClassLoader
        if (bootstrapBridge != HookBridge.class) {
            HookBridge.bindDispatchers(guardDispatcher, filterDispatcher, notifyDispatcher, nettyDispatcher);
        }
    }

    public static synchronized void setHasHookedPlugins(boolean hasHookedPlugins) {
        HookBridge.HAS_HOOKED_PLUGINS = hasHookedPlugins;
        if (injected) {
            try {
                Class<?> bootstrapBridge = Class.forName(BRIDGE_CLASS_NAME, false, null);
                if (bootstrapBridge != HookBridge.class) {
                    bootstrapBridge.getField("HAS_HOOKED_PLUGINS").setBoolean(null, hasHookedPlugins);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized void unbind() {
        HookBridge.unbindDispatchers();
        try {
            Class<?> bootstrapBridge = Class.forName(BRIDGE_CLASS_NAME, false, null);
            if (bootstrapBridge != HookBridge.class) {
                bootstrapBridge.getMethod("unbindDispatchers").invoke(null);
            }
        } catch (Throwable ignored) {
        }
    }
}
