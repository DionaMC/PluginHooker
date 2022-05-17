package io.github.dionatestserver.pluginhooker.listeners;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        DionaPluginHooker.getPlayerManager().addPlayer(e.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        DionaPluginHooker.getPlayerManager().removePlayer(e.getPlayer());
    }
}
