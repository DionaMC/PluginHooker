package dev.diona.pluginhooker;

import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.api.PluginHookerAPI;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.bridge.HookBridge;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.support.SandboxTestFixtures;
import dev.diona.pluginhooker.support.SandboxTestFixtures.ChildFirstActorLoader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies sandbox isolation for NPC / bot {@link Player} instances backed by dummy/embedded channels
 * (e.g. Diona {@code BotPlayer} or Citizens NPCs) where {@code hookedPlayer == null}.
 */
class NpcPlayerSandboxTest {

    private HookConfig config;
    private PluginRegistryImpl pluginRegistry;
    private PlayerRegistryImpl playerRegistry;
    private PatchManager patchManager;
    private ClassLoader parentLoader;
    private URLClassLoader simulatedSelfLoader;

    public static final class GlobalServerTestEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    @BeforeEach
    void setUp() {
        config = new HookConfig();
        pluginRegistry = new PluginRegistryImpl(config);
        playerRegistry = new PlayerRegistryImpl(pluginRegistry, config);
        pluginRegistry.setPlayerRegistry(playerRegistry);
        parentLoader = getClass().getClassLoader();
        simulatedSelfLoader = new URLClassLoader(new URL[0], parentLoader);

        PluginHookerAPI.setProvider(new PluginHookerAPI.Provider() {
            @Override
            public PlayerRegistry getPlayerRegistry() {
                return playerRegistry;
            }

            @Override
            public PluginRegistry getPluginRegistry() {
                return pluginRegistry;
            }
        });

        patchManager = new PatchManager(
                Logger.getLogger("NpcPlayerSandboxTest"),
                config,
                pluginRegistry,
                playerRegistry,
                simulatedSelfLoader
        );
        patchManager.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        patchManager.shutdown();
        simulatedSelfLoader.close();
    }

    private static Player createDummyChannelNpcPlayer(UUID uuid, String name, EmbeddedChannel dummyChannel) throws Exception {
        var conn = new net.minecraft.server.test.PlayerConnection(null);
        conn.networkManager = new net.minecraft.server.test.PlayerConnection.SimulatedNetworkManager(dummyChannel);
        var ep = new net.minecraft.server.test.PlayerConnection.SimulatedEntityPlayer(conn);
        return SandboxTestFixtures.createStubPlayerWithEntity(uuid, name, ep);
    }

    @Test
    @SuppressWarnings("deprecation")
    void blocksWhitelistEventsOnBotsWhilePreservingGlobalAndRealAttackerEvents() throws Exception {
        EmbeddedChannel dummyChannel = new EmbeddedChannel(new ChannelDuplexHandler());
        Player botPlayer = createDummyChannelNpcPlayer(UUID.randomUUID(), "DionaBot", dummyChannel);
        Player enabledRealPlayer = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "EnabledHuman");
        Player disabledRealPlayer = SandboxTestFixtures.createStubPlayer(UUID.randomUUID(), "DisabledHuman");

        try (ChildFirstActorLoader wlLoader = new ChildFirstActorLoader(parentLoader);
             ChildFirstActorLoader blLoader = new ChildFirstActorLoader(parentLoader)) {
            Plugin wlPlugin = SandboxTestFixtures.createMockPlugin(wlLoader, "WhitelistAC");
            Plugin blPlugin = SandboxTestFixtures.createMockPlugin(blLoader, "BlacklistAC");
            pluginRegistry.registerPlugin(wlPlugin, HookMode.WHITELIST);
            pluginRegistry.registerPlugin(blPlugin, HookMode.BLACKLIST);

            // Bot player with dummy channel must NOT register as HookedPlayer
            assertNull(playerRegistry.registerPlayer(botPlayer));
            assertNull(playerRegistry.getPlayer(botPlayer));
            assertFalse(PluginHookerAPI.isAllowed(wlPlugin, botPlayer));
            assertTrue(PluginHookerAPI.isAllowed(blPlugin, botPlayer));

            HookedPlayer enabledHp = playerRegistry.registerPlayer(enabledRealPlayer);
            playerRegistry.registerPlayer(disabledRealPlayer);
            enabledHp.enablePlugin(wlPlugin);

            AtomicInteger wlCalls = new AtomicInteger();
            AtomicInteger blCalls = new AtomicInteger();
            RegisteredListener wlListener = new RegisteredListener(
                    new Listener() {}, (l, e) -> wlCalls.incrementAndGet(), EventPriority.NORMAL, wlPlugin, false
            );
            RegisteredListener blListener = new RegisteredListener(
                    new Listener() {}, (l, e) -> blCalls.incrementAndGet(), EventPriority.NORMAL, blPlugin, false
            );

            try {
                // 1. Global non-player event -> allowed for WHITELIST
                Event globalEvent = new GlobalServerTestEvent();
                wlListener.callEvent(globalEvent);
                assertEquals(1, wlCalls.get());

                // 2. Lifecycle event -> allowed even before HookedPlayer registration
                wlListener.callEvent(new PlayerJoinEvent(botPlayer, "join"));
                assertEquals(2, wlCalls.get());

                // 3. Bot player event -> blocked for WHITELIST, allowed for BLACKLIST
                Event botEvent = (Event) Class.forName("testplugin.TestPlayerEvent", true, wlLoader)
                        .getConstructor(Player.class)
                        .newInstance(botPlayer);
                wlListener.callEvent(botEvent);
                blListener.callEvent(botEvent);
                assertEquals(2, wlCalls.get(), "WHITELIST plugin must NOT receive bot PlayerEvent");
                assertEquals(1, blCalls.get(), "BLACKLIST plugin must still receive bot PlayerEvent");

                // 4. Bot attacking human or zombie attacking bot -> blocked for WHITELIST
                Entity zombie = (Entity) Proxy.newProxyInstance(parentLoader, new Class<?>[]{Entity.class}, (p, m, a) -> null);
                wlListener.callEvent(new EntityDamageByEntityEvent(
                        botPlayer, enabledRealPlayer, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0
                ));
                wlListener.callEvent(new EntityDamageByEntityEvent(
                        zombie, botPlayer, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0
                ));
                assertEquals(2, wlCalls.get(), "WHITELIST plugin must NOT receive damage events attributed to bot");

                // 5. Enabled human attacking bot -> MUST BE ALLOWED so Reach/Hitbox checks work!
                wlListener.callEvent(new EntityDamageByEntityEvent(
                        enabledRealPlayer, botPlayer, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0
                ));
                assertEquals(3, wlCalls.get(), "Enabled human attacking bot MUST pass through to WHITELIST plugin");

                // 6. Disabled human attacking bot -> blocked
                wlListener.callEvent(new EntityDamageByEntityEvent(
                        disabledRealPlayer, botPlayer, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4.0
                ));
                assertEquals(3, wlCalls.get());

                // 7. World entity list filter -> MUST KEEP botPlayer visible while hiding disabledRealPlayer!
                config.bukkitApi().setHideFromEntityLists(true);
                Class<?> actorClass = Class.forName("testplugin.MockNettyPluginActor", true, wlLoader);
                List<Object> worldEntities = new ArrayList<>(List.of(botPlayer, enabledRealPlayer, disabledRealPlayer));
                Supplier<Object> filterCall = () -> HookBridge.filterReturn(worldEntities, HookIds.FILTER_ENTITY_COLLECTION, null);
                @SuppressWarnings("unchecked")
                List<Object> visibleEntities = (List<Object>) actorClass
                        .getMethod("callViaAdventureWrapper", Supplier.class)
                        .invoke(null, filterCall);
                assertEquals(List.of(botPlayer, enabledRealPlayer), visibleEntities);
            } finally {
                playerRegistry.unregisterPlayer(enabledRealPlayer);
                playerRegistry.unregisterPlayer(disabledRealPlayer);
                pluginRegistry.handlePluginDisable(wlPlugin);
                pluginRegistry.handlePluginDisable(blPlugin);
                dummyChannel.finish();
            }
        }
    }

    @Test
    void blocksWhitelistSideEffectsAndPacketsOnBotsEvenWithZeroOnlineHumans() throws Exception {
        EmbeddedChannel dummyChannel = new EmbeddedChannel(new ChannelDuplexHandler());
        Player botPlayer = createDummyChannelNpcPlayer(UUID.randomUUID(), "SoloBot", dummyChannel);

        try (ChildFirstActorLoader wlLoader = new ChildFirstActorLoader(parentLoader);
             ChildFirstActorLoader blLoader = new ChildFirstActorLoader(parentLoader)) {
            Plugin wlPlugin = SandboxTestFixtures.createMockPlugin(wlLoader, "WhitelistAC");
            Plugin blPlugin = SandboxTestFixtures.createMockPlugin(blLoader, "BlacklistAC");
            pluginRegistry.registerPlugin(wlPlugin, HookMode.WHITELIST);
            pluginRegistry.registerPlugin(blPlugin, HookMode.BLACKLIST);

            Class<?> wlActor = Class.forName("testplugin.MockNettyPluginActor", true, wlLoader);
            Class<?> blActor = Class.forName("testplugin.MockNettyPluginActor", true, blLoader);

            // Ensure ZERO real players are registered in PlayerRegistry!
            assertTrue(playerRegistry.getPlayers().isEmpty());

            net.minecraft.server.test.PlayerConnection botNmsConn = new net.minecraft.server.test.PlayerConnection(botPlayer);
            assertNull(playerRegistry.getPlayer(botPlayer), "PlayerConnection.<init> must NOT auto-register dummy-channel bot");

            try {
                // 1. Bukkit side-effect APIs (kick, teleport, velocity, sendMessage, closeInventory)
                int[] sideEffectHooks = {
                        HookIds.GUARD_PLAYER_KICK,
                        HookIds.GUARD_PLAYER_TELEPORT,
                        HookIds.GUARD_ENTITY_VELOCITY,
                        HookIds.GUARD_PLAYER_SEND_MESSAGE,
                        HookIds.GUARD_PLAYER_CLOSE_INVENTORY
                };
                for (int hookId : sideEffectHooks) {
                    Supplier<Boolean> guardCall = () -> HookBridge.checkGuard(hookId, botPlayer, "arg", null);
                    assertTrue(
                            (Boolean) wlActor.getMethod("callViaAdventureWrapper", Supplier.class).invoke(null, guardCall),
                            "WHITELIST plugin should be blocked on hookId=" + hookId + " for bot with 0 humans online"
                    );
                    assertFalse(
                            (Boolean) blActor.getMethod("callViaAdventureWrapper", Supplier.class).invoke(null, guardCall),
                            "BLACKLIST plugin should be allowed on hookId=" + hookId + " for bot"
                    );
                }

                // 2. NMS sendPacket & disconnect on bot connection
                wlActor.getMethod("directNmsSend", net.minecraft.server.test.PlayerConnection.class, Object.class)
                        .invoke(null, botNmsConn, "ac_ping");
                wlActor.getMethod("directNmsDisconnect", net.minecraft.server.test.PlayerConnection.class, String.class)
                        .invoke(null, botNmsConn, "false kick");
                assertEquals(0, botNmsConn.sentPackets);
                assertFalse(botNmsConn.disconnected);

                botNmsConn.sendPacket("server_pkt");
                assertEquals(1, botNmsConn.sentPackets);

                // 3. ProtocolLib sendServerPacket & PacketListener on bot
                var wlInvokeGuard = wlActor.getMethod("invokeGuard", int.class, Object.class, Object.class, Object.class);
                var blInvokeGuard = blActor.getMethod("invokeGuard", int.class, Object.class, Object.class, Object.class);
                assertTrue((Boolean) wlInvokeGuard.invoke(null, HookIds.GUARD_PROTOCOLLIB_SEND_PACKET, botPlayer, "pkt", null));
                assertFalse((Boolean) blInvokeGuard.invoke(null, HookIds.GUARD_PROTOCOLLIB_SEND_PACKET, botPlayer, "pkt", null));

                PacketEvent botPlibEvent = createPlayerPacketEvent(botPlayer);
                PacketListener wlPlibListener = createStubPlibListener(wlPlugin);
                PacketListener blPlibListener = createStubPlibListener(blPlugin);
                assertTrue(HookBridge.checkGuard(HookIds.GUARD_PROTOCOLLIB_INBOUND, botPlibEvent, wlPlibListener, null));
                assertFalse(HookBridge.checkGuard(HookIds.GUARD_PROTOCOLLIB_INBOUND, botPlibEvent, blPlibListener, null));

                // ProtocolLib temporary player proxy MUST NOT be blocked or throw on getUniqueId()
                Player tempPlayer = createTemporaryPlayerStub();
                assertNull(playerRegistry.getPlayer(tempPlayer));
                assertTrue(PluginHookerAPI.isAllowed(wlPlugin, tempPlayer));
                Event tempEvent = (Event) Class.forName("testplugin.TestPlayerEvent", true, wlLoader)
                        .getConstructor(Player.class)
                        .newInstance(tempPlayer);
                AtomicInteger tempCalls = new AtomicInteger();
                new RegisteredListener(
                        new Listener() {}, (l, e) -> tempCalls.incrementAndGet(), EventPriority.NORMAL, wlPlugin, false
                ).callEvent(tempEvent);
                assertEquals(1, tempCalls.get(), "ProtocolLib temporary player proxy must pass through BukkitEventPatch");

                PacketEvent tempPlibEvent = createPlayerPacketEvent(tempPlayer);
                assertFalse(HookBridge.checkGuard(HookIds.GUARD_PROTOCOLLIB_INBOUND, tempPlibEvent, wlPlibListener, null));

                // 4. PacketEvents active sendPacket on dummy channel & PacketSendEvent on bot
                ByteBuf wlBuf = Unpooled.buffer(4).writeInt(77);
                assertTrue((Boolean) wlInvokeGuard.invoke(null, HookIds.GUARD_PACKETEVENTS_SEND_PACKET, null, dummyChannel, wlBuf));
                assertEquals(0, wlBuf.refCnt(), "Blocked PacketEvents ByteBuf to dummy channel must be released");

                ByteBuf blBuf = Unpooled.buffer(4).writeInt(88);
                assertFalse((Boolean) blInvokeGuard.invoke(null, HookIds.GUARD_PACKETEVENTS_SEND_PACKET, null, dummyChannel, blBuf));
                assertEquals(1, blBuf.refCnt());
                blBuf.release();

                Class<?> peEventClass = Class.forName("testplugin.packetevents.event.PacketSendEvent", true, wlLoader);
                Class<?> peListenerIface = Class.forName(
                        "testplugin.packetevents.event.PacketSendEvent$MockShadedListener", true, wlLoader
                );
                AtomicInteger peListenerCalls = new AtomicInteger();
                Object wlPeListener = Proxy.newProxyInstance(
                        wlLoader,
                        new Class<?>[]{peListenerIface},
                        (p, m, a) -> {
                            peListenerCalls.incrementAndGet();
                            return null;
                        }
                );
                Object botPeEvent = peEventClass.getConstructor(Object.class, Object.class).newInstance(null, botPlayer);
                peEventClass.getMethod("call", peListenerIface).invoke(botPeEvent, wlPeListener);
                assertEquals(0, peListenerCalls.get(), "WHITELIST PacketEvents listener must NOT fire on bot player");
            } finally {
                pluginRegistry.handlePluginDisable(wlPlugin);
                pluginRegistry.handlePluginDisable(blPlugin);
                dummyChannel.finish();
            }
        }
    }

    private static PacketEvent createPlayerPacketEvent(Player player) throws Exception {
        PacketEvent event = new PacketEvent(new Object());
        Field refField = PacketEvent.class.getDeclaredField("playerReference");
        refField.setAccessible(true);
        refField.set(event, new WeakReference<>(player));
        return event;
    }

    private static Player createTemporaryPlayerStub() throws Exception {
        ClassDesc tempDesc = ClassDesc.of("com.comphenix.protocol.injector.GeneratedTemporaryPlayerStub");
        byte[] bytes = ClassFile.of().build(tempDesc, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            clb.withInterfaceSymbols(ClassDesc.of("org.bukkit.entity.Player"));
            clb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                cb.return_();
            });
        });
        Class<?> clazz = new SandboxTestFixtures.InMemoryNoResourceLoader(NpcPlayerSandboxTest.class.getClassLoader())
                .defineInMemoryClass("com.comphenix.protocol.injector.GeneratedTemporaryPlayerStub", bytes);
        return (Player) clazz.getConstructor().newInstance();
    }

    private static PacketListener createStubPlibListener(Plugin owner) {
        return new PacketListener() {
            @Override
            public void onPacketSending(PacketEvent event) {
            }

            @Override
            public void onPacketReceiving(PacketEvent event) {
            }

            @Override
            public ListeningWhitelist getSendingWhitelist() {
                return ListeningWhitelist.EMPTY_WHITELIST;
            }

            @Override
            public ListeningWhitelist getReceivingWhitelist() {
                return ListeningWhitelist.EMPTY_WHITELIST;
            }

            @Override
            public Plugin getPlugin() {
                return owner;
            }
        };
    }
}
