package io.github.dionatestserver.pluginhooker.examples;

import io.github.dionatestserver.pluginhooker.events.DionaBukkitListenerEvent;
import io.github.dionatestserver.pluginhooker.events.DionaProtocolLibPacketEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ExampleListener implements Listener {

    @EventHandler
    public void onBukkitEvent(DionaBukkitListenerEvent event) {
        // do something
    }

    @EventHandler
    public void onProtocolLibEvent(DionaProtocolLibPacketEvent event) {
        // do something
    }
}
