package dev.diona.pluginhooker.patch.impl.packetevents;

import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;

public class PacketEventsCallbackHandler {

    private static PacketEventsCallbackHandler instance;

    @ConfigPath("hook.packetevents.call-event")
    public boolean callEvent;

    public PacketEventsCallbackHandler() {
        PluginHooker.getConfigManager().loadConfig(this);
    }

    /**
     * Returns true if the event is cancelled
     * @param event PacketEvent
     * @param listener PacketListenerCommon
     * @return boolean return true the prevent the listener
     */
    public boolean handlePacketEvent(PacketEvent event, PacketListenerCommon listener) {
        if (event instanceof PacketSendEvent) {
            return false;
        }
        if (event instanceof PacketReceiveEvent) {
            return false;
        }
        return false;
    }


    public static PacketEventsCallbackHandler getInstance() {
        return instance != null ? instance : (instance = new PacketEventsCallbackHandler());
    }
}
