package dev.diona.pluginhooker.player;

import lombok.Getter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Getter
public class PlayerManager {

    @Getter
    private final Set<DionaPlayer> players = Collections.synchronizedSet(new HashSet<>());

    public void addPlayer(Player player) {
        players.add(new DionaPlayer(player));
    }

    public void removePlayer(Player player) {
        players.removeIf(dionaPlayer -> dionaPlayer.getPlayer() == player);
    }

    public DionaPlayer getDionaPlayer(Player player) {
        if (player == null) return null;
        return players.stream().filter(dionaPlayer -> dionaPlayer.getPlayer().equals(player)).findFirst().orElse(null);
    }

    public void removeAllPlayerCachedListener() {
        players.forEach(DionaPlayer::removeCachedListener);
    }
}
