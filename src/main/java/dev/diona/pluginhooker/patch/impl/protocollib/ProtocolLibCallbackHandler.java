package dev.diona.pluginhooker.patch.impl.protocollib;

import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.ProtocolLibPacketEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ProtocolLibCallbackHandler {

    private static ProtocolLibCallbackHandler instance;

    @ConfigPath("hook.protocollib.call-event")
    public boolean callEvent;

    public ProtocolLibCallbackHandler() {
        PluginHooker.getConfigManager().loadConfig(this);
    }


    public boolean handleProtocolLibPacket(PacketEvent event, PacketListener listener, boolean outbound) {
        // don't process temporary player
        if (event.isPlayerTemporary()) return false;
        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(event.getPlayer());
        if (dionaPlayer == null) return false;

        Plugin plugin = listener.getPlugin();
        if (!PluginHooker.getPluginManager().getPluginsToHook().contains(plugin)) return false;
        if (!dionaPlayer.getEnabledPlugins().contains(plugin)) return true;

        if (callEvent) {
            ProtocolLibPacketEvent packetEvent = new ProtocolLibPacketEvent(listener, event, outbound);
            Bukkit.getPluginManager().callEvent(packetEvent);
            return packetEvent.isCancelled();
        }
        return false;
    }

    public static ProtocolLibCallbackHandler getInstance() {
        return instance != null ? instance :  (instance = new ProtocolLibCallbackHandler());
    }

}
