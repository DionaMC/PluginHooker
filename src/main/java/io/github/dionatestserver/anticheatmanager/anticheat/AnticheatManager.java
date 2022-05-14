package io.github.dionatestserver.anticheatmanager.anticheat;

import lombok.Getter;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
public class AnticheatManager {

    private final Set<Anticheat> loadedAnticheat = new LinkedHashSet<>();

    public void init() {

    }

    public void addAnticheat(Anticheat anticheat) {
        loadedAnticheat.add(anticheat);
    }

    public void removeAnticheat(Anticheat anticheat) {
        loadedAnticheat.remove(anticheat);
    }

    public void switchAnticheat(DionaPlayer dionaPlayer, Set<Anticheat> anticheats) {
        dionaPlayer.getEnabledAnticheats().forEach(anticheat -> anticheat.onDisable(dionaPlayer));
        anticheats.forEach(anticheat -> anticheat.onEnable(dionaPlayer));

        dionaPlayer.setEnabledAnticheats(anticheats);
    }
}
