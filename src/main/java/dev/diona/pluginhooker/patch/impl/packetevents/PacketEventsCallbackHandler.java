package dev.diona.pluginhooker.patch.impl.packetevents;

import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.event.simple.*;
import com.google.common.collect.Sets;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.PacketEventsPacketEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;

public class PacketEventsCallbackHandler {

    private static PacketEventsCallbackHandler instance;

    private final Set<Class<? extends ProtocolPacketEvent>> ignoredPacketEvents = Sets.newHashSet(
            // Receive
            PacketHandshakeReceiveEvent.class,
            PacketStatusReceiveEvent.class,
            PacketLoginReceiveEvent.class,
            PacketConfigReceiveEvent.class,
            // Send
            PacketHandshakeSendEvent.class,
            PacketStatusSendEvent.class,
            PacketLoginSendEvent.class,
            PacketConfigSendEvent.class
    );

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
        // exempt
        if (this.ignoredPacketEvents.contains(event.getClass())) return false;

        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(ppe.getPlayer());
        if (dionaPlayer == null || !dionaPlayer.isPacketEventsHooked()) return false;

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
