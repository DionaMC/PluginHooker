package dev.diona.pluginhooker;

import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.support.SandboxTestFixtures;
import dev.diona.pluginhooker.support.SandboxTestFixtures.ChildFirstActorLoader;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                // The delegating one is sandboxed by the hooked base method alone, not twice
                assertTrue(patchManager.isClassTransformed(overridingClass));
                assertFalse(patchManager.isClassTransformed(delegatingClass));

                AtomicInteger overridingCalls = new AtomicInteger();
                AtomicInteger delegatingCalls = new AtomicInteger();
                EventExecutor executor = (listener, event) -> delegatingCalls.incrementAndGet();
                RegisteredListener overriding = (RegisteredListener) overridingClass
                        .getConstructor(Plugin.class, AtomicInteger.class)
                        .newInstance(anticheat, overridingCalls);
                RegisteredListener delegating = (RegisteredListener) delegatingClass
                        .getConstructor(Plugin.class, EventExecutor.class)
                        .newInstance(anticheat, executor);
                Event event = (Event) Class.forName("testplugin.TestPlayerEvent", true, pluginLoader)
                        .getConstructor(Player.class)
                        .newInstance(player);

                overriding.callEvent(event);
                delegating.callEvent(event);
                assertEquals(0, overridingCalls.get());
                assertEquals(0, delegatingCalls.get());

                hooked.enablePlugin(anticheat);
                overriding.callEvent(event);
                delegating.callEvent(event);
                assertEquals(1, overridingCalls.get());
                assertEquals(1, delegatingCalls.get());
            } finally {
                playerRegistry.unregisterPlayer(player);
                pluginRegistry.handlePluginDisable(anticheat);
            }
        }
    }
}
