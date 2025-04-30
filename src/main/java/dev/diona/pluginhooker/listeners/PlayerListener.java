package dev.diona.pluginhooker.listeners;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.player.DionaPlayer;
import dev.diona.pluginhooker.utils.NettyUtils;
import dev.diona.pluginhooker.utils.BukkitUtils;
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
    public void prePlayerJoin(PlayerJoinEvent e) {
        PluginHooker.getPlayerManager().addPlayer(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void postPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
        if (dionaPlayer == null) return;
        Channel channel = BukkitUtils.getChannelByPlayer(player);
        Bukkit.getScheduler().runTaskLaterAsynchronously(PluginHooker.getInstance(), () -> {
            // PacketEvents
            dionaPlayer.setPacketEventsHooked(true);
            // netty
            List<Consumer<Player>> list = channel.attr(NettyUtils.WRAPPER_FUNCTIONS).getAndRemove();
            if (list == null) return;
            if (!channel.isOpen() || !dionaPlayer.getPlayer().isOnline()) return;
            list.forEach(consumer -> consumer.accept(player));
        }, 10L);
        dionaPlayer.setInitialized(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent e) {
        PluginHooker.getPlayerManager().removePlayer(e.getPlayer());
    }
}
