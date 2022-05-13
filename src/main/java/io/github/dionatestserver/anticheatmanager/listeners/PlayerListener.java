package io.github.dionatestserver.anticheatmanager.listeners;

import io.github.dionatestserver.anticheatmanager.Diona;
import io.github.dionatestserver.anticheatmanager.anticheat.DionaPlayer;
import io.github.dionatestserver.anticheatmanager.anticheat.PlayerManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent e) {
        PlayerManager playerManager = Diona.getInstance().getPlayerManager();
        playerManager.addPlayer(e.getPlayer());
//        DionaPlayer dionaPlayer = playerManager.getDionaPlayer(e.getPlayer());
//        Diona.getInstance().getAnticheatManager().switchAnticheat(dionaPlayer, Diona.getInstance().getAnticheatManager().getLoadedAnticheat());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent e) {
        PlayerManager playerManager = Diona.getInstance().getPlayerManager();
        playerManager.removePlayer(e.getPlayer());
    }
}
