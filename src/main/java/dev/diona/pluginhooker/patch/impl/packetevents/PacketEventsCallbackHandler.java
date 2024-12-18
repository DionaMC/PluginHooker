package dev.diona.pluginhooker.patch.impl.packetevents;

import com.github.retrooper.packetevents.event.*;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.PacketEventsPacketEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class PacketEventsCallbackHandler {

    private static PacketEventsCallbackHandler instance;

    @ConfigPath("hook.packetevents.call-event")
    public boolean callEvent;

    public PacketEventsCallbackHandler() {
        PluginHooker.getConfigManager().loadConfig(this);
    }

    /**
     * Returns true if the event is cancelled
     *
     * @param event    PacketEvent
     * @param listener PacketListenerCommon
     * @return boolean return true the prevent the listener
     */
    public boolean handlePacketEvent(PacketListenerCommon listener, PacketEvent event) {
        if (!(event instanceof ProtocolPacketEvent)) {
            throw new RuntimeException("Undefined behavior.");
        }
        ProtocolPacketEvent ppe = (ProtocolPacketEvent) event;

        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(ppe.getPlayer());
        if (dionaPlayer == null) return false;

        Plugin plugin = EventManagerCallbackHandler.getInstance().getPlugin(listener);
        if (plugin == null || !PluginHooker.getPluginManager().getPluginsToHook().contains(plugin)) return false;
        if (!dionaPlayer.getEnabledPlugins().contains(plugin)) return true;

        if (callEvent) {
            PacketEventsPacketEvent packetEvent = new PacketEventsPacketEvent(listener, event);
            Bukkit.getPluginManager().callEvent(packetEvent);
            return packetEvent.isCancelled();
        }
        return false;
    }


    public static PacketEventsCallbackHandler getInstance() {
        return instance != null ? instance : (instance = new PacketEventsCallbackHandler());
    }
}
