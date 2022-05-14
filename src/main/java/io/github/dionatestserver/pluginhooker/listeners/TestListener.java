package io.github.dionatestserver.pluginhooker.listeners;

import io.github.dionatestserver.pluginhooker.events.DionaBukkitListenerEvent;
import io.github.dionatestserver.pluginhooker.events.DionaProtocolLibPacketEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class TestListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onDionaBukkitListener(DionaBukkitListenerEvent event) {

    }

    @EventHandler(ignoreCancelled = true)
    public void onDionaProtocolLibPacket(DionaProtocolLibPacketEvent event) {

    }
}
