package dev.diona.pluginhooker;

import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.support.SandboxTestFixtures;
import dev.diona.pluginhooker.support.SandboxTestFixtures.ChildFirstActorLoader;
import dev.diona.pluginhooker.support.SandboxTestFixtures.InMemoryNoResourceLoader;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.TimedRegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredListenerOverrideTest {

    private PluginRegistryImpl pluginRegistry;
    private PlayerRegistryImpl playerRegistry;
    private PatchManager patchManager;

    @BeforeEach
    void setUp() {
        HookConfig config = new HookConfig();
        pluginRegistry = new PluginRegistryImpl(config);
        playerRegistry = new PlayerRegistryImpl(pluginRegistry, config);
        pluginRegistry.setPlayerRegistry(playerRegistry);
        patchManager = new PatchManager(Logger.getLogger("RegisteredListenerOverrideTest"), config, pluginRegistry,
                playerRegistry, getClass().getClassLoader());
        patchManager.initialize();
    }

    @AfterEach
    void tearDown() {
        patchManager.shutdown();
    }

    @Test
    void sandboxesListenersThatOverrideCallEvent() throws Exception {
        try (ChildFirstActorLoader pluginLoader = new ChildFirstActorLoader(getClass().getClassLoader())) {
            Plugin anticheat = SandboxTestFixtures.createMockPlugin(pluginLoader, "OverridingAnticheat");
            pluginRegistry.registerPlugin(anticheat, HookMode.WHITELIST);
            Player player = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "Watched");
            HookedPlayer hooked = playerRegistry.registerPlayer(player);

            try {
                Class<?> overridingClass = Class.forName("testplugin.OverridingRegisteredListener", true, pluginLoader);
                Class<?> delegatingClass = Class.forName("testplugin.DelegatingRegisteredListener", true, pluginLoader);
                // The delegating one and Bukkit's TimedRegisteredListener are sandboxed by the hooked base method alone
                assertTrue(patchManager.isClassTransformed(overridingClass));
                assertFalse(patchManager.isClassTransformed(delegatingClass));
                assertFalse(patchManager.isClassTransformed(TimedRegisteredListener.class));

                AtomicInteger overridingCalls = new AtomicInteger();
                AtomicInteger delegatingCalls = new AtomicInteger();
                AtomicInteger timedCalls = new AtomicInteger();
                EventExecutor delegatingExec = (listener, event) -> delegatingCalls.incrementAndGet();
                EventExecutor timedExec = (listener, event) -> timedCalls.incrementAndGet();
                RegisteredListener overriding = (RegisteredListener) overridingClass
                        .getConstructor(Plugin.class, AtomicInteger.class)
                        .newInstance(anticheat, overridingCalls);
                RegisteredListener delegating = (RegisteredListener) delegatingClass
                        .getConstructor(Plugin.class, EventExecutor.class)
                        .newInstance(anticheat, delegatingExec);
                TimedRegisteredListener timed = new TimedRegisteredListener(
                        new Listener() {
                        }, timedExec, EventPriority.NORMAL, anticheat, false
                );
                Event event = (Event) Class.forName("testplugin.TestPlayerEvent", true, pluginLoader)
                        .getConstructor(Player.class)
                        .newInstance(player);

                overriding.callEvent(event);
                delegating.callEvent(event);
                timed.callEvent(event);
                assertEquals(0, overridingCalls.get());
                assertEquals(0, delegatingCalls.get());
                assertEquals(0, timedCalls.get());

                hooked.enablePlugin(anticheat);
                overriding.callEvent(event);
                delegating.callEvent(event);
                timed.callEvent(event);
                assertEquals(1, overridingCalls.get());
                assertEquals(1, delegatingCalls.get());
                assertEquals(1, timedCalls.get());
            } finally {
                playerRegistry.unregisterPlayer(player);
                pluginRegistry.handlePluginDisable(anticheat);
            }
        }
    }

    @Test
    void sandboxesMultiLevelAndHybridSuperSubclasses() throws Exception {
        try (ChildFirstActorLoader pluginLoader = new ChildFirstActorLoader(getClass().getClassLoader())) {
            Plugin anticheat = SandboxTestFixtures.createMockPlugin(pluginLoader, "MultiLevelAnticheat");
            pluginRegistry.registerPlugin(anticheat, HookMode.WHITELIST);
            Player player = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "WatchedMulti");
            HookedPlayer hooked = playerRegistry.registerPlayer(player);

            try {
                Class<?> baseClass = Class.forName("testplugin.AbstractBaseRegisteredListener", true, pluginLoader);
                Class<?> deepClass = Class.forName("testplugin.DeepOverridingRegisteredListener", true, pluginLoader);
                Class<?> timedSubClass = Class.forName("testplugin.TimedOverridingRegisteredListener", true, pluginLoader);
                Class<?> hybridClass = Class.forName("testplugin.HybridSuperRegisteredListener", true, pluginLoader);

                assertFalse(patchManager.isClassTransformed(baseClass));
                assertTrue(patchManager.isClassTransformed(deepClass));
                assertTrue(patchManager.isClassTransformed(timedSubClass));
                assertTrue(patchManager.isClassTransformed(hybridClass));

                AtomicInteger deepCalls = new AtomicInteger();
                AtomicInteger timedSubCalls = new AtomicInteger();
                AtomicInteger fastSideEffects = new AtomicInteger();
                AtomicInteger postSuperSideEffects = new AtomicInteger();
                AtomicInteger baseExecCalls = new AtomicInteger();
                EventExecutor baseExec = (listener, ev) -> baseExecCalls.incrementAndGet();

                RegisteredListener deep = (RegisteredListener) deepClass
                        .getConstructor(Plugin.class, AtomicInteger.class)
                        .newInstance(anticheat, deepCalls);
                RegisteredListener timedSub = (RegisteredListener) timedSubClass
                        .getConstructor(Plugin.class, AtomicInteger.class)
                        .newInstance(anticheat, timedSubCalls);
                RegisteredListener hybridFast = (RegisteredListener) hybridClass
                        .getConstructor(Plugin.class, EventExecutor.class, boolean.class, AtomicInteger.class)
                        .newInstance(anticheat, baseExec, true, fastSideEffects);
                RegisteredListener hybridPostSuper = (RegisteredListener) hybridClass
                        .getConstructor(Plugin.class, EventExecutor.class, boolean.class, AtomicInteger.class)
                        .newInstance(anticheat, baseExec, false, postSuperSideEffects);

                Event event = (Event) Class.forName("testplugin.TestPlayerEvent", true, pluginLoader)
                        .getConstructor(Player.class)
                        .newInstance(player);

                deep.callEvent(event);
                timedSub.callEvent(event);
                hybridFast.callEvent(event);
                hybridPostSuper.callEvent(event);
                assertEquals(0, deepCalls.get());
                assertEquals(0, timedSubCalls.get());
                assertEquals(0, fastSideEffects.get());
                assertEquals(0, postSuperSideEffects.get());
                assertEquals(0, baseExecCalls.get());

                hooked.enablePlugin(anticheat);
                deep.callEvent(event);
                timedSub.callEvent(event);
                hybridFast.callEvent(event);
                hybridPostSuper.callEvent(event);
                assertEquals(1, deepCalls.get());
                assertEquals(1, timedSubCalls.get());
                assertEquals(1, fastSideEffects.get());
                assertEquals(1, postSuperSideEffects.get());
                assertEquals(1, baseExecCalls.get());
            } finally {
                playerRegistry.unregisterPlayer(player);
                pluginRegistry.handlePluginDisable(anticheat);
            }
        }
    }

    @Test
    void supportsInMemoryResourceLessClassLoadersWithoutCircularityOrLeak() throws Exception {
        Player player = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "WatchedEncrypted");
        HookedPlayer hooked = playerRegistry.registerPlayer(player);
        WeakReference<ClassLoader> loaderRef;
        try {
            loaderRef = exerciseInMemoryEncryptedLoader(player, hooked);
        } finally {
            playerRegistry.unregisterPlayer(player);
        }

        for (int i = 0; i < 15 && loaderRef.get() != null; i++) {
            System.gc();
            Thread.sleep(20L);
        }
        assertNull(loaderRef.get(), "Transformed plugin ClassLoader should be garbage-collected after unload");
    }

    private WeakReference<ClassLoader> exerciseInMemoryEncryptedLoader(Player player, HookedPlayer hooked) throws Exception {
        InMemoryNoResourceLoader encryptedLoader = new InMemoryNoResourceLoader(getClass().getClassLoader());
        WeakReference<ClassLoader> loaderRef = new WeakReference<>(encryptedLoader);

        Plugin anticheat = SandboxTestFixtures.createMockPlugin(encryptedLoader, "EncryptedAnticheat");
        pluginRegistry.registerPlugin(anticheat, HookMode.WHITELIST);
        try {
            byte[] listenerBytes = SandboxTestFixtures.buildSelfMergingListenerBytes("inmemory.EncryptedListener");
            Class<?> encryptedListenerClass = encryptedLoader.defineInMemoryClass(
                    "inmemory.EncryptedListener",
                    listenerBytes
            );
            assertTrue(patchManager.isClassTransformed(encryptedListenerClass));

            byte[] eventBytes;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("testplugin/TestPlayerEvent.class")) {
                eventBytes = in.readAllBytes();
            }
            Class<?> eventClass = encryptedLoader.defineInMemoryClass("testplugin.TestPlayerEvent", eventBytes);
            Event event = (Event) eventClass.getConstructor(Player.class).newInstance(player);

            AtomicInteger calls = new AtomicInteger();
            RegisteredListener listener = (RegisteredListener) encryptedListenerClass
                    .getConstructor(Plugin.class, RegisteredListener.class, AtomicInteger.class)
                    .newInstance(anticheat, null, calls);

            listener.callEvent(event);
            assertEquals(0, calls.get());

            hooked.enablePlugin(anticheat);
            listener.callEvent(event);
            assertEquals(1, calls.get());
        } finally {
            pluginRegistry.handlePluginDisable(anticheat);
        }
        return loaderRef;
    }
}
