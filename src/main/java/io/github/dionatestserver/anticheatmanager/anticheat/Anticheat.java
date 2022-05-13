package io.github.dionatestserver.anticheatmanager.anticheat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.plugin.Plugin;

@Getter
@AllArgsConstructor
public abstract class Anticheat {

    private Plugin plugin;

    public abstract void onEnable(DionaPlayer dionaPlayer);

    public abstract void onDisable(DionaPlayer dionaPlayer);

}