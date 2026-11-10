package dev.diona.pluginhooker;

import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.impl.BukkitApiPatch;
import dev.diona.pluginhooker.patch.impl.BukkitEventPatch;
import dev.diona.pluginhooker.patch.impl.NettyPipelinePatch;
import dev.diona.pluginhooker.patch.impl.PacketEventsPatch;
import dev.diona.pluginhooker.patch.impl.ProtocolLibPatch;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

import java.io.File;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that all ClassPatch transformations produce valid JVM bytecode via JDK 25's ClassFile verifier
 * across both Spigot 1.8.8 (Netty 4.0) and Paper 26.3 (Netty 4.2) classes.
 * Automatically downloads and unpacks Paper 26.3 via PaperMC's Fill v3 API + {@code -Dpaperclip.patchonly=true}
 * into the Gradle cache when not already cached.
 */
public final class PatchVerifier {

    private static final String PAPER_VERSION = "26.3";
    private static final String PAPER_FILL_API =
            "https://fill.papermc.io/v3/projects/paper/versions/" + PAPER_VERSION + "/builds/latest";
    private static final Pattern DOWNLOAD_URL_PATTERN =
            Pattern.compile("\"url\"\\s*:\\s*\"(https://fill-data\\.papermc\\.io/[^\"]+\\.jar)\"");

    private static final List<String> SPIGOT_1_8_TARGETS = List.of(
            "org/bukkit/plugin/RegisteredListener",
            "org/bukkit/craftbukkit/v1_8_R3/CraftServer",
            "org/bukkit/craftbukkit/v1_8_R3/CraftWorld",
            "org/bukkit/craftbukkit/v1_8_R3/entity/CraftPlayer",
            "org/bukkit/craftbukkit/v1_8_R3/entity/CraftPlayer$1",
            "org/bukkit/craftbukkit/v1_8_R3/entity/CraftEntity",
            "net/minecraft/server/v1_8_R3/PlayerConnection",
            "io/netty/channel/DefaultChannelPipeline",
            "io/netty/channel/AbstractChannelHandlerContext",
            "com/comphenix/protocol/injector/collection/InboundPacketListenerSet",
            "com/comphenix/protocol/injector/collection/OutboundPacketListenerSet",
            "com/comphenix/protocol/async/AsyncListenerHandler",
            "com/comphenix/protocol/injector/PacketFilterManager",
            "com/github/retrooper/packetevents/event/EventManager",
            "com/github/retrooper/packetevents/event/PacketReceiveEvent",
            "com/github/retrooper/packetevents/event/PacketSendEvent",
            "io/github/retrooper/packetevents/manager/protocol/ProtocolManagerImpl"
    );

    private static final List<String> PAPER_26_3_TARGETS = List.of(
            "org/bukkit/plugin/RegisteredListener",
            "org/bukkit/craftbukkit/CraftServer",
            "org/bukkit/craftbukkit/CraftWorld",
            "org/bukkit/craftbukkit/CraftRegionAccessor",
            "org/bukkit/craftbukkit/entity/CraftPlayer",
            "org/bukkit/craftbukkit/entity/CraftPlayer$2",
            "org/bukkit/craftbukkit/entity/CraftEntity",
            "net/minecraft/server/network/ServerCommonPacketListenerImpl",
            "net/minecraft/server/network/ServerGamePacketListenerImpl",
            "io/netty/channel/DefaultChannelPipeline",
            "io/netty/channel/AbstractChannelHandlerContext"
    );

    public static void main(String[] args) throws Exception {
        Logger logger = Logger.getLogger("PatchVerifier");
        HookConfig config = new HookConfig();
        PluginRegistryImpl pluginRegistry = new PluginRegistryImpl(config);
        PlayerRegistryImpl playerRegistry = new PlayerRegistryImpl(pluginRegistry, config);
        ClassLoader cl = PatchVerifier.class.getClassLoader();

        // 1. Test live agent & bootstrap bridge injection
        PatchManager patchManager = new PatchManager(logger, config, pluginRegistry, playerRegistry, cl);
        patchManager.initialize();

        Class<?> bootstrapBridge = Class.forName("dev.diona.pluginhooker.bridge.HookBridge", true, null);
        if (bootstrapBridge.getClassLoader() != null) {
            throw new AssertionError("Expected HookBridge to be loaded by Bootstrap ClassLoader (null)");
        }
        logger.info("Verified HookBridge in Bootstrap ClassLoader!");

        List<ClassPatch> patches = List.of(
                new BukkitEventPatch(config, pluginRegistry, playerRegistry, cl),
                new BukkitApiPatch(logger, config, pluginRegistry, playerRegistry, cl),
                new ProtocolLibPatch(logger, config, pluginRegistry, playerRegistry, cl),
                new PacketEventsPatch(logger, config, pluginRegistry, playerRegistry, cl),
                new NettyPipelinePatch(logger, config, pluginRegistry, cl)
        );

        // 2. Verify Spigot 1.8.8 + ProtocolLib 5.4.0 + PacketEvents 2.14.0
        verifyTargets(logger, "Spigot 1.8.8", cl, patches, SPIGOT_1_8_TARGETS);

        // 3. Verify Paper 26.3 (downloaded & unpacked via PaperMC Fill v3 API + paperclip.patchonly)
        Path paperCacheDir = Path.of(System.getProperty("paper.cache.dir", "build/paper-cache/" + PAPER_VERSION));
        Path patchedPaperJar = ensurePaperUnpacked(logger, paperCacheDir);
        Path librariesDir = paperCacheDir.resolve("libraries");

        List<URL> urls = new ArrayList<>();
        urls.add(patchedPaperJar.toUri().toURL());
        try (var stream = Files.walk(librariesDir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(p -> {
                        try {
                            urls.add(p.toUri().toURL());
                        } catch (Exception ignored) {
                        }
                    });
        }
        try (URLClassLoader paperLoader = new URLClassLoader(urls.toArray(URL[]::new), null)) {
            verifyTargets(logger, "Paper " + PAPER_VERSION, paperLoader, patches, PAPER_26_3_TARGETS);
        }

        // 4. Verify live multi-ClassLoader instrumentation & shutdown revert (e.g. multiple plugins shading PacketEvents)
        URL peJarUrl = Class.forName("com.github.retrooper.packetevents.event.EventManager", false, cl)
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
        try (URLClassLoader isolatedA = new URLClassLoader(new URL[]{peJarUrl}, null);
             URLClassLoader isolatedB = new URLClassLoader(new URL[]{peJarUrl}, null)) {
            Class<?> emA = Class.forName("com.github.retrooper.packetevents.event.EventManager", true, isolatedA);
            Class<?> emB = Class.forName("com.github.retrooper.packetevents.event.EventManager", true, isolatedB);
            if (emA == emB) {
                throw new AssertionError("Expected isolated ClassLoaders to produce distinct Class instances");
            }
            if (!patchManager.isClassTransformed(emA) || !patchManager.isClassTransformed(emB)) {
                throw new AssertionError("Expected identical shaded class across multiple ClassLoaders to both be transformed");
            }
            logger.info("Verified multi-ClassLoader live instrumentation!");

            // 4b. Verify live Netty pipeline, Adventure wrapper, and NMS send/disconnect behavior before shutdown
            verifyNettyPipelineBehavior(logger, pluginRegistry, playerRegistry, cl);

            patchManager.shutdown();

            if (patchManager.isClassTransformed(emA) || patchManager.isClassTransformed(emB)) {
                throw new AssertionError("Expected all ClassLoader copies of transformed classes to be reverted on shutdown");
            }
        }

        // 4c. Verify HookBridge fallback Netty dispatch works losslessly when NETTY_DISPATCHER is unbound (null)
        verifyHookBridgeUnboundFallback(logger, bootstrapBridge);

        // 5. Verify Spigot/Paper libraryLoader, early plugin field, and child ClassLoader resolution
        verifyPluginClassLoaderResolution(logger, pluginRegistry, cl);

        // 6. Verify join-grace read-only isolation for PacketEvents & ProtocolLib
        verifyJoinGraceReadOnlyIsolation(logger);

        logger.info("ALL TARGET CLASSES PASSED JDK 25 BYTECODE VERIFICATION!");
    }

    private static final class SimulatedPluginClassLoader extends URLClassLoader {
        @SuppressWarnings("unused")
        private final URLClassLoader libraryLoader;
        @SuppressWarnings("unused")
        private org.bukkit.plugin.Plugin plugin;

        SimulatedPluginClassLoader(ClassLoader parent, URLClassLoader libraryLoader) {
            super(new URL[0], parent);
            this.libraryLoader = libraryLoader;
        }
    }

    private static void verifyPluginClassLoaderResolution(
            Logger logger,
            PluginRegistryImpl pluginRegistry,
            ClassLoader parentLoader
    ) throws Exception {
        try (URLClassLoader libLoader = new URLClassLoader(new URL[0], parentLoader);
             SimulatedPluginClassLoader mainLoader = new SimulatedPluginClassLoader(parentLoader, libLoader);
             URLClassLoader childLoader = new URLClassLoader(new URL[0], libLoader)) {
            org.bukkit.plugin.Plugin mockPlugin = (org.bukkit.plugin.Plugin) Proxy.newProxyInstance(
                    mainLoader,
                    new Class<?>[]{org.bukkit.plugin.Plugin.class},
                    (proxy, method, mArgs) -> switch (method.getName()) {
                        case "getName" -> "MockAnticheat";
                        case "equals" -> proxy == mArgs[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "MockAnticheat";
                        default -> null;
                    }
            );
            mainLoader.plugin = mockPlugin;

            // Early resolution (before registerPlugin / PluginEnableEvent)
            if (pluginRegistry.getPluginByClassLoader(mainLoader) != mockPlugin) {
                throw new AssertionError("Failed to resolve plugin from main PluginClassLoader");
            }
            if (pluginRegistry.getPluginByClassLoader(libLoader) != mockPlugin) {
                throw new AssertionError("Failed to resolve plugin from Spigot/Paper libraryLoader");
            }
            if (pluginRegistry.getPluginByClassLoader(childLoader) != mockPlugin) {
                throw new AssertionError("Failed to resolve plugin from child ClassLoader");
            }
            if (pluginRegistry.getPluginByClassLoader(parentLoader) != null) {
                throw new AssertionError("Server/system ClassLoader must never resolve to a plugin");
            }

            mainLoader.plugin = null;
            pluginRegistry.handlePluginDisable(mockPlugin);
            if (pluginRegistry.getPluginByClassLoader(libLoader) != null) {
                throw new AssertionError("Expected disabled plugin libraryLoader mapping to be cleared");
            }
            logger.info("Verified PluginRegistryImpl libraryLoader & child ClassLoader resolution!");
        }
    }

    private static volatile Class<?> stubPlayerClass;

    private static org.bukkit.entity.Player createStubBukkitPlayer(java.util.UUID uuid, String name) throws Exception {
        Class<?> clazz = stubPlayerClass;
        if (clazz == null) {
            ClassDesc stubDesc = ClassDesc.of("dev.diona.pluginhooker.StubBukkitPlayer");
            ClassDesc uuidDesc = ClassDesc.of("java.util.UUID");
            byte[] bytes = ClassFile.of().build(stubDesc, clb -> {
                clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
                clb.withInterfaceSymbols(ClassDesc.of("org.bukkit.entity.Player"));
                clb.withField("uuid", uuidDesc, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                clb.withField("name", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                clb.withMethodBody(
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, uuidDesc, ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC,
                        cb -> {
                            cb.aload(0);
                            cb.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                            cb.aload(0);
                            cb.aload(1);
                            cb.putfield(stubDesc, "uuid", uuidDesc);
                            cb.aload(0);
                            cb.aload(2);
                            cb.putfield(stubDesc, "name", ConstantDescs.CD_String);
                            cb.return_();
                        }
                );
                clb.withMethodBody("getUniqueId", MethodTypeDesc.of(uuidDesc), ClassFile.ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.getfield(stubDesc, "uuid", uuidDesc);
                    cb.areturn();
                });
                clb.withMethodBody("getName", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.getfield(stubDesc, "name", ConstantDescs.CD_String);
                    cb.areturn();
                });
            });
            clazz = MethodHandles.lookup().defineClass(bytes);
            stubPlayerClass = clazz;
        }
        return (org.bukkit.entity.Player) clazz.getConstructor(java.util.UUID.class, String.class).newInstance(uuid, name);
    }

    private static final class ChildFirstActorLoader extends URLClassLoader {
        ChildFirstActorLoader(URL testClassesUrl, ClassLoader parent) {
            super(new URL[]{testClassesUrl}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("testplugin.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static void verifyNettyPipelineBehavior(
            Logger logger,
            PluginRegistryImpl pluginRegistry,
            PlayerRegistryImpl playerRegistry,
            ClassLoader parentLoader
    ) throws Exception {
        URL testClassesUrl = PatchVerifier.class.getProtectionDomain().getCodeSource().getLocation();
        try (ChildFirstActorLoader pluginLoader = new ChildFirstActorLoader(testClassesUrl, parentLoader)) {
            org.bukkit.plugin.Plugin mockPlugin = (org.bukkit.plugin.Plugin) Proxy.newProxyInstance(
                    pluginLoader,
                    new Class<?>[]{org.bukkit.plugin.Plugin.class},
                    (proxy, method, mArgs) -> switch (method.getName()) {
                        case "getName" -> "DisabledNettyAnticheat";
                        case "equals" -> proxy == mArgs[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> null;
                    }
            );
            pluginRegistry.registerPlugin(mockPlugin, HookMode.WHITELIST);

            HookedPlayer mockHookedPlayer = (HookedPlayer) Proxy.newProxyInstance(
                    parentLoader,
                    new Class<?>[]{HookedPlayer.class},
                    (proxy, method, mArgs) -> switch (method.getName()) {
                        case "getName" -> "TestPlayer";
                        case "isPluginEnabled" -> false;
                        case "hasAnyDisabledPlugin" -> true;
                        case "isInJoinGracePeriod" -> false;
                        default -> null;
                    }
            );

            Class<?> actorClass = Class.forName("testplugin.MockNettyPluginActor", true, pluginLoader);
            ChannelDuplexHandler disabledHandler = (ChannelDuplexHandler) actorClass.getDeclaredConstructor().newInstance();

            java.util.concurrent.atomic.AtomicReference<Object> headReceivedWrite = new java.util.concurrent.atomic.AtomicReference<>();
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
                // 1. Bypassing disabled handler must NOT swallow Unpooled.EMPTY_BUFFER flush markers
                ChannelFuture emptyFuture = ch.writeAndFlush(Unpooled.EMPTY_BUFFER);
                ch.runPendingTasks();
                if (headReceivedWrite.get() != Unpooled.EMPTY_BUFFER || !emptyFuture.isSuccess()) {
                    throw new AssertionError("Expected Unpooled.EMPTY_BUFFER to pass through bypassed handler, got: " + headReceivedWrite.get());
                }
                headReceivedWrite.set(null);
                ch.readOutbound();

                // 2. 2-arg write(buf, voidPromise) and writeAndFlush(buf, promise) from disabled plugin must be blocked
                ByteBuf raw1 = Unpooled.buffer(4).writeInt(42);
                actorClass.getMethod("writeTwoArgVoidPromise", Channel.class, ByteBuf.class).invoke(null, ch, raw1);
                ch.runPendingTasks();
                if (headReceivedWrite.get() != null || raw1.refCnt() != 0) {
                    throw new AssertionError("Expected 2-arg write(ByteBuf, voidPromise) from disabled plugin to be blocked and released");
                }

                ByteBuf raw2 = Unpooled.buffer(4).writeInt(99);
                ChannelPromise customPromise = ch.newPromise();
                Object retFuture = actorClass.getMethod("writeAndFlushTwoArgPromise", Channel.class, ByteBuf.class, ChannelPromise.class)
                        .invoke(null, ch, raw2, customPromise);
                ch.runPendingTasks();
                if (retFuture != customPromise || !customPromise.isSuccess() || headReceivedWrite.get() != null || raw2.refCnt() != 0) {
                    throw new AssertionError("Expected 2-arg writeAndFlush(ByteBuf, promise) to be blocked, released, and succeed promise");
                }

                // 3. Direct channel.close() from disabled plugin must be blocked
                actorClass.getMethod("directClose", Channel.class).invoke(null, ch);
                ch.runPendingTasks();
                if (!ch.isOpen()) {
                    throw new AssertionError("Expected direct channel.close() from disabled plugin to be blocked");
                }

                // 4. Server-initiated channel.close() propagating through disabled handler's helper method must succeed!
                ch.close();
                ch.runPendingTasks();
                if (ch.isOpen()) {
                    throw new AssertionError("Server-initiated channel.close() was falsely blocked by disabled handler helper!");
                }

                // 5. Hooked plugin calling through server-loaded net.kyori.adventure.* wrapper frames must still resolve!
                java.util.function.Supplier<org.bukkit.plugin.Plugin> callerProbe =
                        () -> PluginCallerResolver.resolveHookedCaller(pluginRegistry, parentLoader);
                Object resolvedViaAdventure = actorClass
                        .getMethod("callViaAdventureWrapper", java.util.function.Supplier.class)
                        .invoke(null, callerProbe);
                if (resolvedViaAdventure != mockPlugin) {
                    throw new AssertionError("Expected resolveHookedCaller to walk past net.kyori.adventure.* wrapper, got: " + resolvedViaAdventure);
                }

                // 6. Live NMS PlayerConnection sendPacket (direct + reflection) & disconnect fast-path caller verification
                java.util.UUID mockUuid = java.util.UUID.randomUUID();
                org.bukkit.entity.Player mockBukkitPlayer = createStubBukkitPlayer(mockUuid, "NmsTestPlayer");
                net.minecraft.server.test.PlayerConnection nmsConn = new net.minecraft.server.test.PlayerConnection(mockBukkitPlayer);
                if (playerRegistry.getPlayer(mockUuid) == null) {
                    throw new AssertionError("Expected PlayerConnection.<init> hook to auto-register player");
                }
                nmsConn.sendPacket("server_pkt");
                if (nmsConn.sentPackets != 1) {
                    throw new AssertionError("Expected server NMS sendPacket to pass");
                }
                actorClass.getMethod("directNmsSend", net.minecraft.server.test.PlayerConnection.class, Object.class)
                        .invoke(null, nmsConn, "plugin_pkt");
                actorClass.getMethod("reflectiveNmsSend", net.minecraft.server.test.PlayerConnection.class, Object.class)
                        .invoke(null, nmsConn, "reflective_pkt");
                if (nmsConn.sentPackets != 1) {
                    throw new AssertionError("Expected direct and reflective NMS sendPacket from disabled plugin to be blocked, sentPackets=" + nmsConn.sentPackets);
                }
                actorClass.getMethod("directNmsDisconnect", net.minecraft.server.test.PlayerConnection.class, String.class)
                        .invoke(null, nmsConn, "false kick");
                if (nmsConn.disconnected) {
                    throw new AssertionError("Expected NMS disconnect from disabled plugin to be blocked");
                }
                nmsConn.disconnect("server shutdown");
                if (!nmsConn.disconnected) {
                    throw new AssertionError("Expected server NMS disconnect to succeed");
                }
                playerRegistry.unregisterPlayer(mockBukkitPlayer);
            } finally {
                pluginRegistry.handlePluginDisable(mockPlugin);
            }
            logger.info("Verified live Netty pipeline bypass, 2-arg ByteBuf block, and close propagation!");
        }
    }

    private static void verifyJoinGraceReadOnlyIsolation(Logger logger) throws Exception {
        // A. PacketEvents read-only ByteBuf, readerIndex, wrapper, and task rollback
        ByteBuf origBuf = Unpooled.buffer(8).writeInt(123456);
        int initReader = origBuf.readerIndex();
        int initWriter = origBuf.writerIndex();
        Object enabledPluginWrapper = new Object();

        testplugin.packetevents.event.PacketSendEvent peEvent =
                new testplugin.packetevents.event.PacketSendEvent(origBuf);
        peEvent.setLastUsedWrapper(enabledPluginWrapper);
        peEvent.markForReEncode(true);
        Runnable existingTask = () -> {};
        peEvent.getPostTasks().add(existingTask);

        ByteBuf replacementLeakCheck = Unpooled.buffer(4).writeInt(999);
        testplugin.packetevents.event.PacketSendEvent.MockShadedListener mutatingPeListener = ev -> {
            ByteBuf view = (ByteBuf) ev.getByteBuf();
            if (view.readInt() != 123456) {
                throw new AssertionError("Read-only view could not read packet payload");
            }
            if (ev.getLastUsedWrapper() != null) {
                throw new AssertionError("Expected lastUsedWrapper to be hidden (null) from read-only listener");
            }
            ev.setLastUsedWrapper(new Object());
            ev.setCancelled(true);
            ev.getPostTasks().add(() -> {});
            ev.getTasksAfterSend().add(() -> {});
            try {
                view.setInt(0, -1);
                throw new AssertionError("Expected write to read-only ByteBuf view to fail");
            } catch (java.nio.ReadOnlyBufferException expected) {
            }
            ev.setByteBuf(replacementLeakCheck);
        };

        dev.diona.pluginhooker.util.PacketEventsReflector.invokeReadOnlyListener(mutatingPeListener, peEvent, true);

        if (peEvent.getByteBuf() != origBuf
                || origBuf.readerIndex() != initReader
                || origBuf.writerIndex() != initWriter
                || origBuf.getInt(0) != 123456) {
            throw new AssertionError("Original ByteBuf state or indices were corrupted by read-only PacketEvents listener");
        }
        if (replacementLeakCheck.refCnt() != 0) {
            throw new AssertionError("Replaced ByteBuf was not released during read-only rollback");
        }
        if (peEvent.isCancelled()
                || !peEvent.needsReEncode()
                || peEvent.getLastUsedWrapper() != enabledPluginWrapper
                || peEvent.getPostTasks().size() != 1
                || peEvent.getPostTasks().get(0) != existingTask
                || peEvent.hasTasksAfterSend()) {
            throw new AssertionError("PacketEvents event metadata was not properly restored after read-only listener");
        }
        origBuf.release();

        // B. ProtocolLib read-only NetworkMarker partial postListeners, scheduledPackets & cancellation rollback
        com.comphenix.protocol.events.NetworkMarker marker = new com.comphenix.protocol.events.NetworkMarker(
                com.comphenix.protocol.events.ConnectionSide.SERVER_SIDE,
                null
        );
        com.comphenix.protocol.events.PacketPostListener keepListener = new com.comphenix.protocol.events.PacketPostListener() {
            @Override
            public org.bukkit.plugin.Plugin getPlugin() {
                return null;
            }

            @Override
            public void onPostEvent(com.comphenix.protocol.events.PacketEvent event) {
            }
        };
        marker.addPostListener(keepListener);
        com.comphenix.protocol.events.PacketEvent plibEvent = new com.comphenix.protocol.events.PacketEvent(new Object());
        plibEvent.setNetworkMarker(marker);

        com.comphenix.protocol.events.PacketListener mutatingPlibListener = new com.comphenix.protocol.events.PacketListener() {
            @Override
            public void onPacketSending(com.comphenix.protocol.events.PacketEvent event) {
                event.setCancelled(true);
                event.getNetworkMarker().addPostListener(new com.comphenix.protocol.events.PacketPostListener() {
                    @Override
                    public org.bukkit.plugin.Plugin getPlugin() {
                        return null;
                    }

                    @Override
                    public void onPostEvent(com.comphenix.protocol.events.PacketEvent ev) {
                    }
                });
                event.setNetworkMarker(null);
            }

            @Override
            public void onPacketReceiving(com.comphenix.protocol.events.PacketEvent event) {
            }

            @Override
            public com.comphenix.protocol.events.ListeningWhitelist getSendingWhitelist() {
                return null;
            }

            @Override
            public com.comphenix.protocol.events.ListeningWhitelist getReceivingWhitelist() {
                return null;
            }

            @Override
            public org.bukkit.plugin.Plugin getPlugin() {
                return null;
            }
        };

        ProtocolLibPatch.invokeReadOnlyListener(plibEvent, mutatingPlibListener, true);

        if (plibEvent.isCancelled() || com.comphenix.protocol.events.NetworkMarker.getNetworkMarker(plibEvent) != marker) {
            throw new AssertionError("ProtocolLib cancellation or NetworkMarker reference was not restored");
        }
        if (marker.getPostListeners().size() != 1 || !marker.getPostListeners().contains(keepListener)) {
            throw new AssertionError("ProtocolLib partial postListeners rollback failed: " + marker.getPostListeners());
        }
        logger.info("Verified join-grace read-only isolation for PacketEvents & ProtocolLib!");
    }

    private static void verifyHookBridgeUnboundFallback(Logger logger, Class<?> bootstrapBridge) throws Exception {
        if (bootstrapBridge.getField("NETTY_DISPATCHER").get(null) != null) {
            throw new AssertionError("Expected NETTY_DISPATCHER to be null after shutdown");
        }
        java.util.concurrent.atomic.AtomicReference<Object> readMsg = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Object> writtenMsg = new java.util.concurrent.atomic.AtomicReference<>();
        ChannelDuplexHandler probe = new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                readMsg.set(msg);
            }

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                writtenMsg.set(msg);
                promise.trySuccess();
            }
        };
        EmbeddedChannel ch = new EmbeddedChannel(probe);
        ChannelHandlerContext ctx = ch.pipeline().context(probe);
        ChannelPromise promise = ch.newPromise();

        bootstrapBridge.getMethod("dispatchNettyRead", Object.class, Object.class, Object.class)
                .invoke(null, probe, ctx, "inbound_ok");
        bootstrapBridge.getMethod("dispatchNettyWrite", Object.class, Object.class, Object.class, Object.class)
                .invoke(null, probe, ctx, "outbound_ok", promise);

        if (!"inbound_ok".equals(readMsg.get()) || !"outbound_ok".equals(writtenMsg.get()) || !promise.isSuccess()) {
            throw new AssertionError("HookBridge unbound Netty fallback failed to forward read/write");
        }
        ch.finish();
        logger.info("Verified HookBridge unbound Netty fallback & cached bridge JAR!");
    }

    private static Path ensurePaperUnpacked(Logger logger, Path cacheDir) throws Exception {
        Path patchedJar = cacheDir.resolve("versions").resolve(PAPER_VERSION).resolve("paper-" + PAPER_VERSION + ".jar");
        if (Files.exists(patchedJar) && Files.exists(cacheDir.resolve("libraries"))) {
            return patchedJar;
        }

        Files.createDirectories(cacheDir);
        Path paperclipJar = cacheDir.resolve("paperclip-" + PAPER_VERSION + ".jar");

        if (!Files.exists(paperclipJar)) {
            logger.info("Querying PaperMC Fill v3 API for Paper " + PAPER_VERSION + "...");
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest metaReq = HttpRequest.newBuilder(URI.create(PAPER_FILL_API))
                    .header("User-Agent", "PluginHooker/2.0 (https://github.com/DionaMC/PluginHooker)")
                    .GET()
                    .build();
            String body = client.send(metaReq, HttpResponse.BodyHandlers.ofString()).body();
            Matcher matcher = DOWNLOAD_URL_PATTERN.matcher(body);
            if (!matcher.find()) {
                throw new IllegalStateException("Could not find download URL in PaperMC Fill API response");
            }
            String downloadUrl = matcher.group(1);
            logger.info("Downloading Paper " + PAPER_VERSION + " from " + downloadUrl + "...");
            HttpRequest dlReq = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .header("User-Agent", "PluginHooker/2.0 (https://github.com/DionaMC/PluginHooker)")
                    .GET()
                    .build();
            try (InputStream in = client.send(dlReq, HttpResponse.BodyHandlers.ofInputStream()).body()) {
                Files.copy(in, paperclipJar, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        logger.info("Running paperclip patchonly in " + cacheDir + "...");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                javaBin,
                "-Dpaperclip.patchonly=true",
                "-jar",
                paperclipJar.toAbsolutePath().toString()
        ).directory(cacheDir.toFile()).inheritIO().start();

        int exitCode = process.waitFor();
        if (exitCode != 0 || !Files.exists(patchedJar)) {
            throw new IllegalStateException("Paperclip patchonly failed with exit code " + exitCode);
        }
        return patchedJar;
    }

    private static void verifyTargets(
            Logger logger,
            String envName,
            ClassLoader loader,
            List<ClassPatch> patches,
            List<String> targetClasses
    ) throws Exception {
        ClassHierarchyResolver resolver = ClassHierarchyResolver.ofResourceParsing(loader)
                .orElse(ClassHierarchyResolver.ofClassLoading(loader))
                .orElse(ClassHierarchyResolver.defaultResolver())
                .orElse(desc -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object))
                .cached();
        ClassFile verifierCf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));

        for (String internalName : targetClasses) {
            byte[] originalBytes;
            try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
                if (in == null) {
                    throw new IllegalStateException("[" + envName + "] Missing class resource: " + internalName);
                }
                originalBytes = in.readAllBytes();
            }

            boolean matched = false;
            for (ClassPatch patch : patches) {
                if (patch.matchesClass(internalName)) {
                    matched = true;
                    byte[] transformed = patch.transform(loader, internalName, originalBytes);
                    List<VerifyError> errors = verifierCf.verify(transformed);
                    if (!errors.isEmpty()) {
                        throw new AssertionError("[" + envName + "] Bytecode verification failed for " + internalName + ": " + errors);
                    }
                    if (transformed.length == originalBytes.length) {
                        throw new AssertionError("[" + envName + "] Class " + internalName + " was not modified by patch!");
                    }
                    logger.info("[" + envName + "] Verified bytecode: " + internalName
                            + " (" + originalBytes.length + " -> " + transformed.length + " bytes)");
                }
            }
            if (!matched) {
                throw new AssertionError("[" + envName + "] No patch matched target class: " + internalName);
            }
        }
    }
}
