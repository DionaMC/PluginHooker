package io.github.dionatestserver.anticheatmanager.player;

import io.github.dionatestserver.anticheatmanager.anticheat.Anticheat;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

@Getter
public class DionaPlayer {

    private final Player player;

    @Setter
    private Set<Anticheat> enabledAnticheats;

    public DionaPlayer(Player player) {
        this.player = player;
        this.enabledAnticheats = new HashSet<>();
    }
}
