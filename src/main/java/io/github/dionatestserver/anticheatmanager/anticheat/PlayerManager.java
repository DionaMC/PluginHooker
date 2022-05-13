package io.github.dionatestserver.anticheatmanager.anticheat;

import lombok.Getter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Getter
public class PlayerManager {

    private List<DionaPlayer> players = new ArrayList<>();

    public void addPlayer(Player player) {
        players.add(new DionaPlayer(player));
    }

    public void removePlayer(Player player) {
        players.removeIf(dionaPlayer -> dionaPlayer.getPlayer() == player);
    }

    public DionaPlayer getDionaPlayer(Player player) {
        if (player == null) return null;
        return players.stream()
                .filter(dionaPlayer -> dionaPlayer.getPlayer() == player)
                .findFirst()
                .orElse(null);
    }
}
