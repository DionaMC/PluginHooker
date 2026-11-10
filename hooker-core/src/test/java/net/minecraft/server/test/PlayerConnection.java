package net.minecraft.server.test;

import org.bukkit.entity.Player;

/**
 * Simulated NMS PlayerConnection instrumented live by BukkitApiPatch in PatchVerifier.
 */
public class PlayerConnection {

    private final Player player;
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
}
