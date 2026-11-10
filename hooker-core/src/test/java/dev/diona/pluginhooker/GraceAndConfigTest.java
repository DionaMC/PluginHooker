package dev.diona.pluginhooker;

import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.NetworkMarker;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.events.PacketPostListener;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.impl.ProtocolLibPatch;
import dev.diona.pluginhooker.util.PacketEventsReflector;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import testplugin.packetevents.event.PacketSendEvent;

import java.nio.ReadOnlyBufferException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraceAndConfigTest {

    @Test
    void packetEventsReadOnlyGraceIsolatesBufferAndWrapper() {
        ByteBuf origBuf = Unpooled.buffer(8).writeInt(123456);
        int initReader = origBuf.readerIndex();
        int initWriter = origBuf.writerIndex();
        Object enabledPluginWrapper = new Object();

        PacketSendEvent peEvent = new PacketSendEvent(origBuf);
        peEvent.setLastUsedWrapper(enabledPluginWrapper);
        peEvent.markForReEncode(true);
        Runnable existingTask = () -> {};
        peEvent.getPostTasks().add(existingTask);

        ByteBuf replacementLeakCheck = Unpooled.buffer(4).writeInt(999);
        PacketSendEvent.MockShadedListener mutatingPeListener = ev -> {
            ByteBuf view = (ByteBuf) ev.getByteBuf();
            assertEquals(123456, view.readInt());
            assertNull(ev.getLastUsedWrapper());
            ev.setLastUsedWrapper(new Object());
            ev.setCancelled(true);
            ev.getPostTasks().add(() -> {});
            ev.getTasksAfterSend().add(() -> {});
            assertThrows(ReadOnlyBufferException.class, () -> view.setInt(0, -1));
            ev.setByteBuf(replacementLeakCheck);
        };

        PacketEventsReflector.invokeReadOnlyListener(mutatingPeListener, peEvent, true);

        assertSame(origBuf, peEvent.getByteBuf());
        assertEquals(initReader, origBuf.readerIndex());
        assertEquals(initWriter, origBuf.writerIndex());
        assertEquals(123456, origBuf.getInt(0));
        assertEquals(0, replacementLeakCheck.refCnt());
        assertFalse(peEvent.isCancelled());
        assertTrue(peEvent.needsReEncode());
        assertSame(enabledPluginWrapper, peEvent.getLastUsedWrapper());
        assertEquals(1, peEvent.getPostTasks().size());
        assertSame(existingTask, peEvent.getPostTasks().get(0));
        assertFalse(peEvent.hasTasksAfterSend());
        origBuf.release();
    }

    @Test
    void protocolLibReadOnlyGraceRestoresMarkerAndPartialPostListeners() {
        NetworkMarker marker = new NetworkMarker(ConnectionSide.SERVER_SIDE, null);
        PacketPostListener keepListener = new PacketPostListener() {
            @Override
            public Plugin getPlugin() {
                return null;
            }

            @Override
            public void onPostEvent(PacketEvent event) {
            }
        };
        marker.addPostListener(keepListener);
        PacketEvent plibEvent = new PacketEvent(new Object());
        plibEvent.setNetworkMarker(marker);

        PacketListener mutatingPlibListener = new PacketListener() {
            @Override
            public void onPacketSending(PacketEvent event) {
                event.setCancelled(true);
                event.getNetworkMarker().addPostListener(new PacketPostListener() {
                    @Override
                    public Plugin getPlugin() {
                        return null;
                    }

                    @Override
                    public void onPostEvent(PacketEvent ev) {
                    }
                });
                event.setNetworkMarker(null);
            }

            @Override
            public void onPacketReceiving(PacketEvent event) {
            }

            @Override
            public ListeningWhitelist getSendingWhitelist() {
                return null;
            }

            @Override
            public ListeningWhitelist getReceivingWhitelist() {
                return null;
            }

            @Override
            public Plugin getPlugin() {
                return null;
            }
        };

        ProtocolLibPatch.invokeReadOnlyListener(plibEvent, mutatingPlibListener, true);

        assertFalse(plibEvent.isCancelled());
        assertSame(marker, NetworkMarker.getNetworkMarker(plibEvent));
        assertEquals(Set.of(keepListener), marker.getPostListeners());
    }

    @Test
    void parsesMapAndListPreConfiguredSandboxPlugins() throws Exception {
        HookConfig cfg = new HookConfig();
        YamlConfiguration mapYaml = new YamlConfiguration();
        mapYaml.loadFromString("""
                sandbox:
                  default-mode: WHITELIST
                  plugins:
                    GrimAC: WHITELIST
                    Vulcan: BLACKLIST
                """);
        cfg.loadFromYaml(mapYaml);
        assertEquals(HookMode.WHITELIST, cfg.getConfiguredPluginMode("grimac"));
        assertEquals(HookMode.BLACKLIST, cfg.getConfiguredPluginMode("VULCAN"));
        assertNull(cfg.getConfiguredPluginMode("Unknown"));

        YamlConfiguration listYaml = new YamlConfiguration();
        listYaml.loadFromString("""
                sandbox:
                  default-mode: WHITELIST
                  plugins:
                    - Matrix
                    - Karhu:BLACKLIST
                """);
        cfg.loadFromYaml(listYaml);
        assertEquals(HookMode.WHITELIST, cfg.getConfiguredPluginMode("Matrix"));
        assertEquals(HookMode.BLACKLIST, cfg.getConfiguredPluginMode("karhu"));
    }
}
