package io.github.dionatestserver.anticheatmanager.listeners;

import io.github.dionatestserver.anticheatmanager.Diona;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Diona.getInstance().getPlayerManager().addPlayer(e.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Diona.getInstance().getPlayerManager().removePlayer(e.getPlayer());
    }
}
