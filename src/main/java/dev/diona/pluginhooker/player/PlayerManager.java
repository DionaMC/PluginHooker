package dev.diona.pluginhooker.player;

import com.comphenix.protocol.concurrency.AbstractConcurrentListenerMultimap;
import lombok.Getter;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class PlayerManager {

    private final Map<UUID, DionaPlayer> players = new ConcurrentHashMap<>();
    // protocollib listener cache
    private final Map<Integer, DionaPlayer> inboundListenerRefToPlayer = new ConcurrentHashMap<>();
    private final Map<Integer, DionaPlayer> outboundListenerRefToPlayer = new ConcurrentHashMap<>();

    public void addPlayer(Player player) {
        DionaPlayer dionaPlayer = new DionaPlayer(player);
        this.players.put(player.getUniqueId(), dionaPlayer);
    }

    public void removePlayer(Player player) {
        DionaPlayer dionaPlayer = this.getDionaPlayer(player);
        if (dionaPlayer != null) {
            dionaPlayer.setInitialized(false);
            this.players.remove(player.getUniqueId());
            this.inboundListenerRefToPlayer.values().removeIf(dionaPlayer::equals);
            this.outboundListenerRefToPlayer.values().removeIf(dionaPlayer::equals);
        }
    }

    public DionaPlayer getDionaPlayer(Player player) {
        if (player == null) return null;
        return this.players.getOrDefault(player.getUniqueId(), null);
    }

    public void checkAndRemoveCachedListener(AbstractConcurrentListenerMultimap list) {
        // try to get inboundListenerList first
        DionaPlayer dionaPlayer = inboundListenerRefToPlayer.get(list.hashCode());
        if (dionaPlayer != null) {
            dionaPlayer.setReceivedCachedListeners(null);
            return;
        }
        dionaPlayer = outboundListenerRefToPlayer.get(list.hashCode());
        if (dionaPlayer != null) {
            dionaPlayer.setSendingCachedListeners(null);
        }
    }
}
