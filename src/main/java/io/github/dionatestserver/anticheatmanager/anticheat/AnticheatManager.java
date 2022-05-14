package io.github.dionatestserver.anticheatmanager.anticheat;

import io.github.dionatestserver.anticheatmanager.Diona;
import io.github.dionatestserver.anticheatmanager.player.DionaPlayer;
import lombok.Getter;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
public class AnticheatManager {

    private final Set<Anticheat> loadedAnticheat = new LinkedHashSet<>();

    public void addAnticheat(Anticheat anticheat) {
        loadedAnticheat.add(anticheat);
    }

    public void removeAnticheat(Anticheat anticheat) {
        loadedAnticheat.remove(anticheat);
    }

    public void switchAnticheats(Player player, Set<Anticheat> anticheats) {
        DionaPlayer dionaPlayer = Diona.getInstance().getPlayerManager().getDionaPlayer(player);
        dionaPlayer.getEnabledAnticheats().forEach(anticheat -> anticheat.onDisable(dionaPlayer));
        anticheats.forEach(anticheat -> anticheat.onEnable(dionaPlayer));
        dionaPlayer.setEnabledAnticheats(anticheats);
    }
}
