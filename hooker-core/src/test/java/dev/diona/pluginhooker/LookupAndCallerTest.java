package dev.diona.pluginhooker;

import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.bridge.HookBridge;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.support.SandboxTestFixtures;
import dev.diona.pluginhooker.support.SandboxTestFixtures.ChildFirstActorLoader;
import dev.diona.pluginhooker.util.EventPlayerResolver;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupAndCallerTest {

    private HookConfig config;
    private PluginRegistryImpl pluginRegistry;
    private PlayerRegistryImpl playerRegistry;
    private PatchManager patchManager;
    private ClassLoader parentLoader;

    private static final class SimulatedPluginClassLoader extends URLClassLoader {
        @SuppressWarnings("unused")
        private final URLClassLoader libraryLoader;
        @SuppressWarnings("unused")
        private Plugin plugin;

        SimulatedPluginClassLoader(ClassLoader parent, URLClassLoader libraryLoader) {
            super(new URL[0], parent);
            this.libraryLoader = libraryLoader;
        }
    }

    @BeforeEach
    void setUp() {
        config = new HookConfig();
        pluginRegistry = new PluginRegistryImpl(config);
        playerRegistry = new PlayerRegistryImpl(pluginRegistry, config);
        pluginRegistry.setPlayerRegistry(playerRegistry);
        parentLoader = getClass().getClassLoader();
        patchManager = new PatchManager(Logger.getLogger("LookupAndCallerTest"), config, pluginRegistry, playerRegistry, parentLoader);
        patchManager.initialize();
    }

    @AfterEach
    void tearDown() {
        patchManager.shutdown();
    }

    @Test
    void resolvesMainLoaderLibraryLoaderAndChildLoaders() throws Exception {
        try (URLClassLoader libLoader = new URLClassLoader(new URL[0], parentLoader);
             SimulatedPluginClassLoader mainLoader = new SimulatedPluginClassLoader(parentLoader, libLoader);
             URLClassLoader childLoader = new URLClassLoader(new URL[0], libLoader);
             URLClassLoader isolatedNonPluginLoader = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            // Early query while mainLoader.plugin is still null must NOT permanently poison mainLoader
            assertNull(pluginRegistry.getPluginByClassLoader(mainLoader));

            Plugin mockPlugin = SandboxTestFixtures.createMockPlugin(mainLoader, "MockAnticheat");
            mainLoader.plugin = mockPlugin;

            assertSame(mockPlugin, pluginRegistry.getPluginByClassLoader(mainLoader));
            assertSame(mockPlugin, pluginRegistry.getPluginByClassLoader(libLoader));
            assertSame(mockPlugin, pluginRegistry.getPluginByClassLoader(childLoader));
            assertNull(pluginRegistry.getPluginByClassLoader(parentLoader));

            // Non-plugin isolated ClassLoader negative-caches cleanly on repeated lookups
            assertNull(pluginRegistry.getPluginByClassLoader(isolatedNonPluginLoader));
            assertNull(pluginRegistry.getPluginByClassLoader(isolatedNonPluginLoader));

            mainLoader.plugin = null;
            pluginRegistry.handlePluginDisable(mockPlugin);
            assertNull(pluginRegistry.getPluginByClassLoader(libLoader));
        }
    }

    public static final class CustomPluginEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player customTarget;

        public CustomPluginEvent(Player customTarget) {
            super(true);
            this.customTarget = customTarget;
        }

        public Player getCustomTarget() {
            return customTarget;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void eventPlayerResolverClassValueDispatch() throws Exception {
        Player attacker = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "Attacker");
        Player victim = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "Victim");
        Entity dummyZombie = (Entity) java.lang.reflect.Proxy.newProxyInstance(
                parentLoader,
                new Class<?>[]{Entity.class},
                (p, m, a) -> null
        );

        // PvP: resolves damager first
        EntityDamageByEntityEvent pvp = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0
        );
        assertSame(attacker, EventPlayerResolver.resolvePlayer(pvp, true));

        // PvE (Zombie hitting Player): falls back to victim Player
        EntityDamageByEntityEvent pve = new EntityDamageByEntityEvent(
                dummyZombie, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0
        );
        assertSame(victim, EventPlayerResolver.resolvePlayer(pve, true));

        // Custom reflective event respects useReflectionFallback flag
        CustomPluginEvent custom = new CustomPluginEvent(victim);
        assertSame(victim, EventPlayerResolver.resolvePlayer(custom, true));
        assertNull(EventPlayerResolver.resolvePlayer(custom, false));
    }

    @Test
    void adventureWrapperFilteringCommandDisambiguationAndCloseInventory() throws Exception {
        try (ChildFirstActorLoader pluginLoader = new ChildFirstActorLoader(parentLoader)) {
            Plugin mockPlugin = SandboxTestFixtures.createMockPlugin(pluginLoader, "HookedPvpAnticheat");
            pluginRegistry.registerPlugin(mockPlugin, HookMode.WHITELIST);
            Class<?> actorClass = Class.forName("testplugin.MockNettyPluginActor", true, pluginLoader);

            UUID disabledUuid = UUID.randomUUID();
            Player disabledPlayer = SandboxTestFixtures.createStubPlayer(disabledUuid, "DisabledPlayer");
            Player enabledPlayer = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "EnabledPlayer");
            Player subcmdNamedPlayer = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "kick");

            playerRegistry.registerPlayer(disabledPlayer);
            playerRegistry.registerPlayer(subcmdNamedPlayer);
            HookedPlayer enabledHp = playerRegistry.registerPlayer(enabledPlayer);
            enabledHp.enablePlugin(mockPlugin);

            try {
                // 1. Adventure default-method wrapper stack penetration
                Supplier<Plugin> callerProbe = () -> PluginCallerResolver.resolveHookedCaller(pluginRegistry, parentLoader);
                Object resolved = actorClass.getMethod("callViaAdventureWrapper", Supplier.class).invoke(null, callerProbe);
                assertSame(mockPlugin, resolved);

                // 2. Single-pass lazy entity collection filter + mutability + zero-copy fast path
                config.bukkitApi().setHideFromEntityLists(true);
                List<Object> rawEntities = new ArrayList<>(List.of("ZombieStub", enabledPlayer, disabledPlayer));
                Supplier<Object> filterCall = () -> HookBridge.filterReturn(rawEntities, HookIds.FILTER_ENTITY_COLLECTION, null);
                @SuppressWarnings("unchecked")
                List<Object> filtered = (List<Object>) actorClass.getMethod("callViaAdventureWrapper", Supplier.class)
                        .invoke(null, filterCall);
                assertEquals(2, filtered.size());
                assertFalse(filtered.contains(disabledPlayer));
                filtered.removeIf("ZombieStub"::equals);
                assertEquals(List.of(enabledPlayer), filtered);

                List<Object> noHidden = new ArrayList<>(List.of("ZombieStub", enabledPlayer));
                Supplier<Object> noCopyCall = () -> HookBridge.filterReturn(noHidden, HookIds.FILTER_ENTITY_COLLECTION, null);
                Object sameRef = actorClass.getMethod("callViaAdventureWrapper", Supplier.class).invoke(null, noCopyCall);
                assertSame(noHidden, sameRef);

                // 3. Command target disambiguation & UUID matching
                Supplier<Boolean> cmdTargetingEnabled = () -> HookBridge.checkGuard(
                        HookIds.GUARD_DISPATCH_COMMAND, enabledPlayer, "ac kick EnabledPlayer Hacking", null
                );
                assertFalse((Boolean) actorClass.getMethod("callViaAdventureWrapper", Supplier.class).invoke(null, cmdTargetingEnabled));

                Supplier<Boolean> cmdTargetingKickUser = () -> HookBridge.checkGuard(
                        HookIds.GUARD_DISPATCH_COMMAND, enabledPlayer, "kick kick Hacking", null
                );
                assertTrue((Boolean) actorClass.getMethod("callViaAdventureWrapper", Supplier.class).invoke(null, cmdTargetingKickUser));

                Supplier<Boolean> cmdTargetingUuid = () -> HookBridge.checkGuard(
                        HookIds.GUARD_DISPATCH_COMMAND, enabledPlayer, "litebans:ban -s " + disabledUuid + " Cheating", null
                );
                assertTrue((Boolean) actorClass.getMethod("callViaAdventureWrapper", Supplier.class).invoke(null, cmdTargetingUuid));

                // 4. closeInventory guard
                Supplier<Boolean> closeInvDisabled = () -> HookBridge.checkGuard(
                        HookIds.GUARD_PLAYER_CLOSE_INVENTORY, disabledPlayer, null, null
                );
                assertTrue((Boolean) actorClass.getMethod("callViaAdventureWrapper", Supplier.class).invoke(null, closeInvDisabled));
            } finally {
                playerRegistry.unregisterPlayer(subcmdNamedPlayer);
                playerRegistry.unregisterPlayer(enabledPlayer);
                playerRegistry.unregisterPlayer(disabledPlayer);
                pluginRegistry.handlePluginDisable(mockPlugin);
            }
        }
    }
}
