package io.github.dionatestserver.pluginhooker.player;

import io.github.dionatestserver.pluginhooker.plugin.DionaPlugin;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

@Getter
public class DionaPlayer {

    private final Player player;

    @Setter
    private Set<DionaPlugin> enabledDionaPlugins;

    public DionaPlayer(Player player) {
        this.player = player;
        this.enabledDionaPlugins = new HashSet<>();
    }
}
