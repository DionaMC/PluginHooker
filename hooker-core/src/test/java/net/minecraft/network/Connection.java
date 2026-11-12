package net.minecraft.network;

import io.netty.channel.Channel;
import net.minecraft.network.protocol.Packet;
import org.bukkit.entity.Player;

/**
 * Simulated Mojang-mapped {@code net.minecraft.network.Connection} instrumented live by {@code NmsConnectionPatch}.
 */
public class Connection {

    /**
     * Deliberately does not end with {@code Packet} in its own class name to verify interface-hierarchy
     * {@code net.minecraft.network.protocol.Packet} detection (e.g. {@code PacketPlayOutTransaction}).
     */
    public static final class SimulatedPingPayload implements Packet {
        private final int id;

        public SimulatedPingPayload(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public static final class SimulatedServerPlayer {
        private final Player bukkitPlayer;

        public SimulatedServerPlayer(Player bukkitPlayer) {
            this.bukkitPlayer = bukkitPlayer;
        }

        public Player getBukkitEntity() {
            return bukkitPlayer;
        }
    }

    public Channel channel;
    private SimulatedServerPlayer serverPlayer;
    public int sentPackets;
    public boolean disconnected;

    public Connection(Channel channel, Player bukkitPlayer) {
        this.channel = channel;
        this.serverPlayer = bukkitPlayer != null ? new SimulatedServerPlayer(bukkitPlayer) : null;
    }

    public final SimulatedServerPlayer getPlayer() {
        return serverPlayer;
    }

    public void setServerPlayer(Player bukkitPlayer) {
        this.serverPlayer = bukkitPlayer != null ? new SimulatedServerPlayer(bukkitPlayer) : null;
    }

    public void send(Object packet) {
        send(packet, null, true);
    }

    public void send(Object packet, Object listener, boolean flush) {
        this.sentPackets++;
    }

    public void sendPacketAndScheduleNettyTask(Object packet, boolean flush, Runnable task) {
        this.sentPackets++;
        if (task != null) {
            task.run();
        }
    }

    public void disconnect(Object reason) {
        this.disconnected = true;
    }
}
