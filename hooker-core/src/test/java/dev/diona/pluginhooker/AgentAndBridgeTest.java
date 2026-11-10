package dev.diona.pluginhooker;

import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAndBridgeTest {

    @Test
    void multiClassLoaderShadedInstrumentationAndUnboundFallback() throws Exception {
        Logger logger = Logger.getLogger("AgentAndBridgeTest");
        HookConfig config = new HookConfig();
        PluginRegistryImpl pluginRegistry = new PluginRegistryImpl(config);
        PlayerRegistryImpl playerRegistry = new PlayerRegistryImpl(pluginRegistry, config);
        ClassLoader cl = getClass().getClassLoader();

        PatchManager patchManager = new PatchManager(logger, config, pluginRegistry, playerRegistry, cl);
        patchManager.initialize();

        Class<?> bootstrapBridge = Class.forName("dev.diona.pluginhooker.bridge.HookBridge", true, null);
        assertNull(bootstrapBridge.getClassLoader(), "HookBridge must reside in Bootstrap ClassLoader");

        URL peJarUrl = Class.forName("com.github.retrooper.packetevents.event.EventManager", false, cl)
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();

        try (URLClassLoader isolatedA = new URLClassLoader(new URL[]{peJarUrl}, null);
             URLClassLoader isolatedB = new URLClassLoader(new URL[]{peJarUrl}, null)) {
            Class<?> emA = Class.forName("com.github.retrooper.packetevents.event.EventManager", true, isolatedA);
            Class<?> emB = Class.forName("com.github.retrooper.packetevents.event.EventManager", true, isolatedB);
            assertNotSame(emA, emB);
            assertTrue(patchManager.isClassTransformed(emA));
            assertTrue(patchManager.isClassTransformed(emB));

            patchManager.shutdown();

            assertFalse(patchManager.isClassTransformed(emA));
            assertFalse(patchManager.isClassTransformed(emB));
        }

        // Verify HookBridge unbound fallback forwards Netty read/write losslessly when NETTY_DISPATCHER == null
        assertNull(bootstrapBridge.getField("NETTY_DISPATCHER").get(null));
        AtomicReference<Object> readMsg = new AtomicReference<>();
        AtomicReference<Object> writtenMsg = new AtomicReference<>();
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

        assertEquals("inbound_ok", readMsg.get());
        assertEquals("outbound_ok", writtenMsg.get());
        assertTrue(promise.isSuccess());
        ch.finish();
    }
}
