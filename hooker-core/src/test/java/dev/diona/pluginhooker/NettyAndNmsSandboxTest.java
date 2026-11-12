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
import io.netty.util.ReferenceCountUtil;
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

    @Test
    void rawNmsConnectionSharedPeChannelOperatorAndDirectPacketWriteGuards() throws Exception {
        try (ChildFirstActorLoader peLoader = new ChildFirstActorLoader(parentLoader, "testpe.");
             ChildFirstActorLoader disabledLoader = new ChildFirstActorLoader(peLoader, "testplugin.");
             ChildFirstActorLoader enabledLoader = new ChildFirstActorLoader(peLoader, "testplugin.")) {
            Plugin sharedPe = SandboxTestFixtures.createMockPlugin(peLoader, "packetevents");
            peLoader.setPlugin(sharedPe);
            Plugin disabledPlugin = SandboxTestFixtures.createMockPlugin(disabledLoader, "DisabledDiona");
            disabledLoader.setPlugin(disabledPlugin);
            Plugin enabledPlugin = SandboxTestFixtures.createMockPlugin(enabledLoader, "EnabledVulcan");
            enabledLoader.setPlugin(enabledPlugin);

            pluginRegistry.registerPlugin(disabledPlugin, HookMode.WHITELIST);
            pluginRegistry.registerPlugin(enabledPlugin, HookMode.WHITELIST);

            Class<?> peOperatorClass = Class.forName("testpe.ChannelOperatorModernImpl", true, peLoader);
            Class<?> disabledActor = Class.forName("testplugin.MockNettyPluginActor", true, disabledLoader);
            Class<?> enabledActor = Class.forName("testplugin.MockNettyPluginActor", true, enabledLoader);

            UUID playerUuid = UUID.randomUUID();
            Player bukkitPlayer = SandboxTestFixtures.createStubPlayer(playerUuid, "Loyisa");
            HookedPlayer hookedPlayer = playerRegistry.registerPlayer(bukkitPlayer);
            hookedPlayer.enablePlugin(enabledPlugin);

            AtomicReference<Object> headReceivedWrite = new AtomicReference<>();
            ChannelDuplexHandler captureDownstream = new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    headReceivedWrite.set(msg);
                    super.write(ctx, msg, promise);
                }
            };
            io.netty.handler.codec.MessageToMessageEncoder<String> multiOutputEncoder =
                    new io.netty.handler.codec.MessageToMessageEncoder<>(String.class) {
                        @Override
                        protected void encode(ChannelHandlerContext ctx, String msg, java.util.List<Object> out) {
                            out.add(Unpooled.copiedBuffer(msg.getBytes()));
                            out.add(Unpooled.copiedBuffer(msg.getBytes()));
                        }
                    };

            EmbeddedChannel ch = new EmbeddedChannel(captureDownstream);
            ch.pipeline().addLast("pe-encoder-packetevents", new ChannelDuplexHandler());
            ch.pipeline().addLast("multi_encoder", multiOutputEncoder);
            ch.attr(PlayerChannelResolver.HOOKED_PLAYER_KEY).set(hookedPlayer);

            // 1. Handshake/login Connection (null channel attr & null ServerPlayer) never NPEs
            net.minecraft.network.Connection loginConn = new net.minecraft.network.Connection(null, null);
            disabledActor.getMethod("directRawConnectionSend", net.minecraft.network.Connection.class, Object.class)
                    .invoke(null, loginConn, "login_pkt");
            assertEquals(1, loginConn.sentPackets);

            // 2. PLAY Connection resolved via channel fast-path, NettyChannelProxy unwrap, AND entity fallback
            Channel channelProxy = createSimulatedNettyChannelProxy(ch);
            net.minecraft.network.Connection channelBoundConn = new net.minecraft.network.Connection(ch, null);
            net.minecraft.network.Connection proxyBoundConn = new net.minecraft.network.Connection(channelProxy, null);
            net.minecraft.network.Connection entityBoundConn = new net.minecraft.network.Connection(null, bukkitPlayer);

            try {
                // Disabled plugin blocked on 1-arg send, reflective send, 3-arg send, proxy-channel send, and disconnect
                disabledActor.getMethod("directRawConnectionSend", net.minecraft.network.Connection.class, Object.class)
                        .invoke(null, channelBoundConn, "diona_ping");
                disabledActor.getMethod("reflectiveRawConnectionSend", net.minecraft.network.Connection.class, Object.class)
                        .invoke(null, channelBoundConn, "diona_reflective_ping");
                disabledActor.getMethod("directRawConnectionSend", net.minecraft.network.Connection.class, Object.class)
                        .invoke(null, proxyBoundConn, "diona_proxy_ping");
                disabledActor.getMethod("directRawConnectionSendThreeArg", net.minecraft.network.Connection.class, Object.class)
                        .invoke(null, entityBoundConn, "diona_ping_noflush");
                assertEquals(0, channelBoundConn.sentPackets);
                assertEquals(0, proxyBoundConn.sentPackets);
                assertEquals(0, entityBoundConn.sentPackets);

                disabledActor.getMethod("directRawConnectionDisconnect", net.minecraft.network.Connection.class, Object.class)
                        .invoke(null, channelBoundConn, "false disconnect");
                assertFalse(channelBoundConn.disconnected);

                // Enabled plugin & server itself are allowed on raw Connection
                enabledActor.getMethod("directRawConnectionSend", net.minecraft.network.Connection.class, Object.class)
                        .invoke(null, channelBoundConn, "vulcan_ping");
                channelBoundConn.send("server_bundle");
                assertEquals(2, channelBoundConn.sentPackets);

                // 3. Direct Netty channel.writeAndFlush(NMS Packet) blocked for disabled plugin, allowed for server/enabled,
                // and one-shot internalNmsWrite token does NOT leak into re-entrant writes inside downstream handlers
                var pingPacket = new net.minecraft.network.Connection.SimulatedPingPayload(-31662);
                var leakedNestedPacket = new net.minecraft.network.Connection.SimulatedPingPayload(-31661);
                disabledActor.getMethod("directWriteNmsPacket", Channel.class, Object.class)
                        .invoke(null, ch, pingPacket);
                ch.runPendingTasks();
                assertNull(headReceivedWrite.get());

                ch.pipeline().addAfter("pe-encoder-packetevents", "reentrant_probe", new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        if (msg == pingPacket) {
                            disabledActor.getMethod("directWriteNmsPacket", Channel.class, Object.class)
                                    .invoke(null, ch, leakedNestedPacket);
                        }
                        super.write(ctx, msg, promise);
                    }
                });
                channelBoundConn.send(pingPacket);
                ch.runPendingTasks();
                assertSame(pingPacket, ch.readOutbound());
                assertNull(ch.readOutbound(), "Re-entrant disabled plugin write during server doSendPacket must still be blocked");
                ch.pipeline().remove("reentrant_probe");
                headReceivedWrite.set(null);

                enabledActor.getMethod("directWriteNmsPacket", Channel.class, Object.class)
                        .invoke(null, ch, pingPacket);
                ch.runPendingTasks();
                assertSame(pingPacket, headReceivedWrite.get());
                headReceivedWrite.set(null);
                ch.readOutbound();

                // 4. Standalone packetevents ChannelOperatorModernImpl writeAndFlush / writeAndFlushInContext / close
                ByteBuf blockedBuf1 = Unpooled.buffer(4).writeInt(-31662);
                disabledActor.getMethod("writeViaSharedPeOperator", Class.class, Channel.class, Object.class)
                        .invoke(null, peOperatorClass, ch, blockedBuf1);
                ch.runPendingTasks();
                assertNull(headReceivedWrite.get());
                assertEquals(0, blockedBuf1.refCnt());

                ByteBuf blockedBuf2 = Unpooled.buffer(4).writeInt(-31661);
                disabledActor.getMethod(
                        "writeInContextViaSharedPeOperator", Class.class, Channel.class, String.class, Object.class
                ).invoke(null, peOperatorClass, ch, "pe-encoder-packetevents", blockedBuf2);
                ch.runPendingTasks();
                assertNull(headReceivedWrite.get());
                assertEquals(0, blockedBuf2.refCnt());

                disabledActor.getMethod("closeViaSharedPeOperator", Class.class, Channel.class)
                        .invoke(null, peOperatorClass, ch);
                ch.runPendingTasks();
                assertTrue(ch.isOpen(), "ChannelOperatorModernImpl.close from disabled plugin must be blocked");

                ByteBuf allowedBuf = Unpooled.buffer(4).writeInt(-31662);
                enabledActor.getMethod("writeViaSharedPeOperator", Class.class, Channel.class, Object.class)
                        .invoke(null, peOperatorClass, ch, allowedBuf);
                ch.runPendingTasks();
                assertSame(allowedBuf, headReceivedWrite.get());
                headReceivedWrite.set(null);
                ReferenceCountUtil.safeRelease(ch.readOutbound());

                // 5. MessageToMessageEncoder writePromiseCombiner (non-void promise, >1 output) never false-blocks
                // even when a disabled plugin frame sits deeper on the call stack
                ChannelPromise bundlePromise = ch.newPromise();
                Runnable serverBundleWrite = () -> ch.writeAndFlush("bundle", bundlePromise);
                disabledActor.getMethod("triggerServerActionOnStack", Runnable.class)
                        .invoke(null, serverBundleWrite);
                ch.runPendingTasks();
                assertTrue(bundlePromise.isSuccess());
                assertNotNull(headReceivedWrite.get());
                while (true) {
                    Object out = ch.readOutbound();
                    if (out == null) {
                        break;
                    }
                    ReferenceCountUtil.safeRelease(out);
                }
            } finally {
                ch.finish();
                playerRegistry.unregisterPlayer(bukkitPlayer);
                pluginRegistry.handlePluginDisable(disabledPlugin);
                pluginRegistry.handlePluginDisable(enabledPlugin);
                pluginRegistry.handlePluginDisable(sharedPe);
            }
        }
    }

    private static Channel createSimulatedNettyChannelProxy(Channel delegate) throws Exception {
        java.lang.constant.ClassDesc proxyDesc =
                java.lang.constant.ClassDesc.of("dev.diona.pluginhooker.NettyChannelProxy");
        java.lang.constant.ClassDesc channelDesc =
                java.lang.constant.ClassDesc.of("io.netty.channel.Channel");
        byte[] bytes = java.lang.classfile.ClassFile.of().build(proxyDesc, clb -> {
            clb.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC | java.lang.classfile.ClassFile.ACC_FINAL);
            clb.withInterfaceSymbols(channelDesc);
            clb.withField("delegate", channelDesc, java.lang.classfile.ClassFile.ACC_PRIVATE | java.lang.classfile.ClassFile.ACC_FINAL);
            clb.withMethodBody(
                    java.lang.constant.ConstantDescs.INIT_NAME,
                    java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void, channelDesc),
                    java.lang.classfile.ClassFile.ACC_PUBLIC,
                    cb -> {
                        cb.aload(0);
                        cb.invokespecial(java.lang.constant.ConstantDescs.CD_Object, java.lang.constant.ConstantDescs.INIT_NAME, java.lang.constant.ConstantDescs.MTD_void);
                        cb.aload(0);
                        cb.aload(1);
                        cb.putfield(proxyDesc, "delegate", channelDesc);
                        cb.return_();
                    }
            );
        });
        Class<?> proxyClass = java.lang.invoke.MethodHandles.lookup().defineClass(bytes);
        return (Channel) proxyClass.getConstructor(Channel.class).newInstance(delegate);
    }
}
