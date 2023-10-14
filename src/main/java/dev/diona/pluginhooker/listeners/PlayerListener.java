package dev.diona.pluginhooker.listeners;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.player.DionaPlayer;
import dev.diona.pluginhooker.utils.HookerUtils;
import dev.diona.pluginhooker.utils.NMSUtils;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.function.Consumer;

public class PlayerListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent e) {
        PluginHooker.getPlayerManager().addPlayer(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void postPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
        if (dionaPlayer == null) return;
        Channel channel = NMSUtils.getChannelByPlayer(player);
        Bukkit.getScheduler().runTaskLaterAsynchronously(PluginHooker.getInstance(), () -> {
            // is Channel still open?
            if (!channel.isOpen()) {
                // clear the attr
                channel.attr(HookerUtils.HANDLER_REPLACEMENT_FUNCTIONS).remove();
                return;
            }
            if (dionaPlayer.isQuited() || !dionaPlayer.getPlayer().isOnline()) {
                return;
            }
            List<Consumer<Player>> list = channel.attr(HookerUtils.HANDLER_REPLACEMENT_FUNCTIONS).getAndRemove();
            // what??
            if (list != null) {
                for (Consumer<Player> consumer : list) {
                    if (channel.isOpen()) {
                        consumer.accept(player);
                    }
                }
            }
        }, 10L);
        dionaPlayer.setInitialized(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent e) {
        PluginHooker.getPlayerManager().removePlayer(e.getPlayer());
    }
}
