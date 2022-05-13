package io.github.dionatestserver.anticheatmanager.anticheat;

import lombok.Getter;
import org.bukkit.Bukkit;

import java.util.HashSet;
import java.util.Set;

@Getter
public class AnticheatManager {

    private Set<Anticheat> loadedAnticheat = new HashSet<>();

    public void init(){

    }


    public void addAnticheat(Anticheat anticheat){
        loadedAnticheat.add(anticheat);
    }

    public void switchAnticheat(DionaPlayer dionaPlayer,Set<Anticheat> anticheats){
        dionaPlayer.getEnabledAnticheats().forEach(anticheat -> anticheat.onDisable(dionaPlayer));
        anticheats.forEach(anticheat -> anticheat.onEnable(dionaPlayer));

        dionaPlayer.setEnabledAnticheats(anticheats);
    }
}
