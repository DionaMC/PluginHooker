package io.github.dionatestserver.pluginhooker.player;

import lombok.Getter;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Getter
public class PlayerManager {

    @Getter
    private final Set<DionaPlayer> players = new HashSet<>();

    public void addPlayer(Player player) {
        players.add(new DionaPlayer(player));
    }

    public void removePlayer(Player player) {
        players.removeIf(dionaPlayer -> dionaPlayer.getPlayer() == player);
    }

    public DionaPlayer getDionaPlayer(Player player) {
        if (player == null) return null;
        for (DionaPlayer dionaPlayer : players) {
            if (dionaPlayer.getPlayer().equals(player)) {
                return dionaPlayer;
            }
        }
        return null;
    }

    public void removeAllPlayerCachedListener() {
        players.forEach(DionaPlayer::removeCachedListener);
    }
}
