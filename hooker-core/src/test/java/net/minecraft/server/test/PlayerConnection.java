package net.minecraft.server.test;

import io.netty.channel.Channel;
import org.bukkit.entity.Player;

/**
 * Simulated NMS PlayerConnection instrumented live by NmsConnectionPatch in tests.
 */
public class PlayerConnection {

    public static final class SimulatedNetworkManager {
        public Channel channel;

        public SimulatedNetworkManager(Channel channel) {
            this.channel = channel;
        }
    }

    public static final class SimulatedEntityPlayer {
        public PlayerConnection connection;

        public SimulatedEntityPlayer(PlayerConnection connection) {
            this.connection = connection;
        }
    }

    private final Player player;
    public SimulatedNetworkManager networkManager;
    public int sentPackets;
    public boolean disconnected;

    public PlayerConnection(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    public void sendPacket(Object packet) {
        this.sentPackets++;
    }

    public void disconnect(String reason) {
        this.disconnected = true;
    }

    public void onDisconnect(Object reason) {
        this.disconnected = true;
    }
}
