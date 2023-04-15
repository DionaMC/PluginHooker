package dev.diona.pluginhooker.listeners;

import dev.diona.pluginhooker.PluginHooker;
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
        Channel channel = NMSUtils.getChannelByPlayer(player);
        List<Consumer<Player>> list = channel.attr(HookerUtils.HANDLER_REPLACEMENT_FUNCTIONS).get();
        if (list == null) return;

        Bukkit.getScheduler().runTaskLaterAsynchronously(PluginHooker.getInstance(), () -> {
            list.forEach(consumer -> consumer.accept(player));
        }, 10L);
        PluginHooker.getPlayerManager().getDionaPlayer(player).setInitialized();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent e) {
        PluginHooker.getPlayerManager().removePlayer(e.getPlayer());
    }
}
