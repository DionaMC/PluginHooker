package dev.diona.pluginhooker.player;

import lombok.Getter;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class PlayerManager {

    private final Map<UUID, DionaPlayer> players = new ConcurrentHashMap<>();

    public void addPlayer(Player player) {
        DionaPlayer dionaPlayer = new DionaPlayer(player);
        this.players.put(player.getUniqueId(), dionaPlayer);
    }

    public void removePlayer(Player player) {
        DionaPlayer dionaPlayer = this.getDionaPlayer(player);
        if (dionaPlayer != null) {
            dionaPlayer.setInitialized(false);
            this.players.remove(player.getUniqueId());
        }
    }

    public DionaPlayer getDionaPlayer(Player player) {
        if (player == null) return null;
        return this.players.getOrDefault(player.getUniqueId(), null);
    }
}
