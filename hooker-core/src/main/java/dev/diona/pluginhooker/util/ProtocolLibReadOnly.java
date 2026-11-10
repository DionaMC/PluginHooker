package dev.diona.pluginhooker.util;

import com.comphenix.protocol.events.NetworkMarker;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.events.PacketPostListener;
import com.comphenix.protocol.events.ScheduledPacket;

import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Isolated read-only execution helper for ProtocolLib {@link PacketListener}s during the player join grace period.
 */
public final class ProtocolLibReadOnly {

    private ProtocolLibReadOnly() {
    }

    /**
     * Executes a disabled plugin's listener in read-only mode during the join grace period so it can
     * observe join initialization packets without cancelling, mutating, replacing, or scheduling packets.
     */
    public static void invokeReadOnlyListener(PacketEvent event, PacketListener listener, boolean outbound) {
        boolean wasCancelled = event.isCancelled();
        boolean wasReadOnly = event.isReadOnly();
        PacketContainer prevPacket = event.getPacket();
        NetworkMarker prevMarker = NetworkMarker.getNetworkMarker(event);
        Deque<ScheduledPacket> prevScheduled = prevMarker != null
                ? NetworkMarker.readScheduledPackets(prevMarker)
                : null;
        int prevScheduledSize = prevScheduled != null ? prevScheduled.size() : 0;
        Set<PacketPostListener> prevPostSnapshot = NetworkMarker.hasPostListeners(prevMarker)
                ? new HashSet<>(prevMarker.getPostListeners())
                : null;

        if (prevPacket != null && !wasReadOnly) {
            PacketContainer isolatedCopy = createIsolatedPacketCopy(prevPacket);
            if (isolatedCopy != null) {
                event.setPacket(isolatedCopy);
            }
        }

        try {
            if (outbound) {
                listener.onPacketSending(event);
            } else {
                listener.onPacketReceiving(event);
            }
        } catch (Throwable ignored) {
        } finally {
            event.setReadOnly(false);
            if (event.isCancelled() != wasCancelled) {
                event.setCancelled(wasCancelled);
            }
            if (prevPacket != null && event.getPacket() != prevPacket) {
                event.setPacket(prevPacket);
            }
            event.setReadOnly(wasReadOnly);
            revertNetworkMarker(event, prevMarker, prevScheduled, prevScheduledSize, prevPostSnapshot);
        }
    }

    private static PacketContainer createIsolatedPacketCopy(PacketContainer original) {
        try {
            Object handle = original.getHandle();
            if (handle == null) {
                return null;
            }
            if (handle.getClass().isRecord()) {
                return new PacketContainer(original.getType(), handle);
            }
            return original.shallowClone();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void revertNetworkMarker(
            PacketEvent event,
            NetworkMarker prevMarker,
            Deque<ScheduledPacket> prevScheduled,
            int prevScheduledSize,
            Set<PacketPostListener> prevPostSnapshot
    ) {
        NetworkMarker currentMarker = NetworkMarker.getNetworkMarker(event);
        if (prevMarker == null) {
            if (currentMarker != null) {
                event.setNetworkMarker(null);
            }
            return;
        }
        if (currentMarker != prevMarker) {
            event.setNetworkMarker(prevMarker);
        }
        Deque<ScheduledPacket> currentScheduled = NetworkMarker.readScheduledPackets(prevMarker);
        if (currentScheduled != null) {
            if (prevScheduled == null) {
                currentScheduled.clear();
            } else {
                while (currentScheduled.size() > prevScheduledSize) {
                    currentScheduled.removeLast();
                }
            }
        }
        if (NetworkMarker.hasPostListeners(prevMarker)) {
            if (prevPostSnapshot == null) {
                prevMarker.getPostListeners().clear();
            } else if (prevMarker.getPostListeners().size() != prevPostSnapshot.size()) {
                prevMarker.getPostListeners().retainAll(prevPostSnapshot);
            }
        }
    }
}
