package dev.diona.pluginhooker;

import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.support.SandboxTestFixtures;
import dev.diona.pluginhooker.support.SandboxTestFixtures.ChildFirstActorLoader;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyAndNmsSandboxTest {

    private HookConfig config;
    private PluginRegistryImpl pluginRegistry;
    private PlayerRegistryImpl playerRegistry;
    private PatchManager patchManager;
    private ClassLoader parentLoader;

    @BeforeEach
    void setUp() {
        config = new HookConfig();
        pluginRegistry = new PluginRegistryImpl(config);
        playerRegistry = new PlayerRegistryImpl(pluginRegistry, config);
        pluginRegistry.setPlayerRegistry(playerRegistry);
        parentLoader = getClass().getClassLoader();
        patchManager = new PatchManager(Logger.getLogger("NettyAndNmsSandboxTest"), config, pluginRegistry, playerRegistry, parentLoader);
        patchManager.initialize();
    }

    @AfterEach
    void tearDown() {
        patchManager.shutdown();
    }

    @Test
    void nettyBypassEmptyBufferTwoArgWritesAndClosePropagation() throws Exception {
        try (ChildFirstActorLoader pluginLoader = new ChildFirstActorLoader(parentLoader)) {
            Plugin mockPlugin = SandboxTestFixtures.createMockPlugin(pluginLoader, "DisabledNettyAnticheat");
            pluginRegistry.registerPlugin(mockPlugin, HookMode.WHITELIST);

            HookedPlayer mockHookedPlayer = SandboxTestFixtures.createDisabledHookedPlayerProxy(parentLoader, "TestPlayer");
            Class<?> actorClass = Class.forName("testplugin.MockNettyPluginActor", true, pluginLoader);
            ChannelDuplexHandler disabledHandler = (ChannelDuplexHandler) actorClass.getDeclaredConstructor().newInstance();

            AtomicReference<Object> headReceivedWrite = new AtomicReference<>();
            ChannelDuplexHandler captureDownstream = new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    headReceivedWrite.set(msg);
                    super.write(ctx, msg, promise);
                }
            };

            EmbeddedChannel ch = new EmbeddedChannel(captureDownstream);
            ch.attr(PlayerChannelResolver.HOOKED_PLAYER_KEY).set(mockHookedPlayer);
            ch.pipeline().addLast("disabled_ac", disabledHandler);

            try {
                // 1. Bypassing disabled handler must preserve Unpooled.EMPTY_BUFFER flush markers
                ChannelFuture emptyFuture = ch.writeAndFlush(Unpooled.EMPTY_BUFFER);
                ch.runPendingTasks();
                assertSame(Unpooled.EMPTY_BUFFER, headReceivedWrite.get());
                assertTrue(emptyFuture.isSuccess());
                headReceivedWrite.set(null);
                ch.readOutbound();

                // 2. 2-arg write(buf, voidPromise) & writeAndFlush(buf, promise) from disabled plugin are blocked
                ByteBuf raw1 = Unpooled.buffer(4).writeInt(42);
                actorClass.getMethod("writeTwoArgVoidPromise", Channel.class, ByteBuf.class).invoke(null, ch, raw1);
                ch.runPendingTasks();
                assertNull(headReceivedWrite.get());
                assertEquals(0, raw1.refCnt());

                ByteBuf raw2 = Unpooled.buffer(4).writeInt(99);
                ChannelPromise customPromise = ch.newPromise();
                Object retFuture = actorClass.getMethod("writeAndFlushTwoArgPromise", Channel.class, ByteBuf.class, ChannelPromise.class)
                        .invoke(null, ch, raw2, customPromise);
                ch.runPendingTasks();
                assertSame(customPromise, retFuture);
                assertTrue(customPromise.isSuccess());
                assertNull(headReceivedWrite.get());
                assertEquals(0, raw2.refCnt());

                // 3. Direct channel.close() from disabled plugin is blocked
                actorClass.getMethod("directClose", Channel.class).invoke(null, ch);
                ch.runPendingTasks();
                assertTrue(ch.isOpen());

                // 4. Server-initiated channel.close() propagating through disabled handler helper succeeds
                ch.close();
                ch.runPendingTasks();
                assertFalse(ch.isOpen());
            } finally {
                pluginRegistry.handlePluginDisable(mockPlugin);
            }
        }
    }

    @Test
    void nmsConnectionInitSendPacketAndDisconnectGuards() throws Exception {
        try (ChildFirstActorLoader pluginLoader = new ChildFirstActorLoader(parentLoader)) {
            Plugin mockPlugin = SandboxTestFixtures.createMockPlugin(pluginLoader, "DisabledNmsAnticheat");
            pluginRegistry.registerPlugin(mockPlugin, HookMode.WHITELIST);
            Class<?> actorClass = Class.forName("testplugin.MockNettyPluginActor", true, pluginLoader);

            UUID mockUuid = UUID.randomUUID();
            Player mockBukkitPlayer = SandboxTestFixtures.createStubPlayer(mockUuid, "NmsTestPlayer");
            net.minecraft.server.test.PlayerConnection nmsConn = new net.minecraft.server.test.PlayerConnection(mockBukkitPlayer);

            try {
                assertNotNull(playerRegistry.getPlayer(mockUuid), "PlayerConnection.<init> must auto-register player");

                nmsConn.sendPacket("server_pkt");
                assertEquals(1, nmsConn.sentPackets);

                actorClass.getMethod("directNmsSend", net.minecraft.server.test.PlayerConnection.class, Object.class)
                        .invoke(null, nmsConn, "plugin_pkt");
                actorClass.getMethod("reflectiveNmsSend", net.minecraft.server.test.PlayerConnection.class, Object.class)
                        .invoke(null, nmsConn, "reflective_pkt");
                assertEquals(1, nmsConn.sentPackets, "Direct and reflective NMS sendPacket from disabled plugin must be blocked");

                actorClass.getMethod("directNmsDisconnect", net.minecraft.server.test.PlayerConnection.class, String.class)
                        .invoke(null, nmsConn, "false kick");
                assertFalse(nmsConn.disconnected);

                nmsConn.disconnect("server shutdown");
                assertTrue(nmsConn.disconnected);
            } finally {
                playerRegistry.unregisterPlayer(mockBukkitPlayer);
                pluginRegistry.handlePluginDisable(mockPlugin);
            }
        }
    }

    @Test
    void compositeChannelExtractorResolvesAndGuardsNullHops() throws Exception {
        java.lang.constant.ClassDesc desc = java.lang.constant.ClassDesc.of("dev.diona.pluginhooker.StubCraftPlayerWithHandle");
        java.lang.constant.ClassDesc epDesc = java.lang.constant.ClassDesc.of(
                net.minecraft.server.test.PlayerConnection.SimulatedEntityPlayer.class.getName()
        );
        byte[] bytes = java.lang.classfile.ClassFile.of().build(desc, clb -> {
            clb.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC | java.lang.classfile.ClassFile.ACC_FINAL);
            clb.withInterfaceSymbols(java.lang.constant.ClassDesc.of("org.bukkit.entity.Player"));
            clb.withField("handle", epDesc, java.lang.classfile.ClassFile.ACC_PUBLIC);
            clb.withMethodBody(
                    java.lang.constant.ConstantDescs.INIT_NAME,
                    java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void, epDesc),
                    java.lang.classfile.ClassFile.ACC_PUBLIC,
                    cb -> {
                        cb.aload(0);
                        cb.invokespecial(java.lang.constant.ConstantDescs.CD_Object, java.lang.constant.ConstantDescs.INIT_NAME, java.lang.constant.ConstantDescs.MTD_void);
                        cb.aload(0);
                        cb.aload(1);
                        cb.putfield(desc, "handle", epDesc);
                        cb.return_();
                    }
            );
            clb.withMethodBody("getHandle", java.lang.constant.MethodTypeDesc.of(epDesc), java.lang.classfile.ClassFile.ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.getfield(desc, "handle", epDesc);
                cb.areturn();
            });
        });
        Class<?> craftPlayerStubClass = java.lang.invoke.MethodHandles.lookup().defineClass(bytes);

        EmbeddedChannel ch = new EmbeddedChannel(new ChannelDuplexHandler());
        net.minecraft.server.test.PlayerConnection conn = new net.minecraft.server.test.PlayerConnection(null);
        conn.networkManager = new net.minecraft.server.test.PlayerConnection.SimulatedNetworkManager(ch);
        var ep = new net.minecraft.server.test.PlayerConnection.SimulatedEntityPlayer(conn);
        Player connectedPlayer = (Player) craftPlayerStubClass
                .getConstructor(net.minecraft.server.test.PlayerConnection.SimulatedEntityPlayer.class)
                .newInstance(ep);

        assertSame(ch, PlayerChannelResolver.getChannel(connectedPlayer));

        // Null intermediate hop (connection == null) must return null safely without NPE
        ep.connection = null;
        assertNull(PlayerChannelResolver.getChannel(connectedPlayer));
        ch.finish();
    }
}
