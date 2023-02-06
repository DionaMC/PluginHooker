package dev.diona.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.concurrency.SortedCopyOnWriteArray;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.injector.PrioritizedListener;
import com.comphenix.protocol.injector.SortedPacketListenerList;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.ProtocolLibPacketEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class ProtocolLibCallbackHandler {

    @ConfigPath("hook.protocollib.call-event")
    public boolean callEvent;

    private Field mapListeners;

    public ProtocolLibCallbackHandler() {
        PluginHooker.getConfigManager().loadConfig(this);
    }

    public SortedPacketListenerList handleProtocolLibPacket(SortedPacketListenerList listenerList, PacketEvent event, boolean outbound) {
        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(event.getPlayer());
        if (dionaPlayer == null) return listenerList;

        SortedPacketListenerList cachedListeners = outbound ? dionaPlayer.getSendingCachedListeners() : dionaPlayer.getReceivedCachedListeners();
        if (cachedListeners != null) return cachedListeners;


        SortedPacketListenerList newListeners = this.deepCopyListenerList(listenerList);

        for (PrioritizedListener<PacketListener> value : newListeners.values()) {
            PacketListener listener = value.getListener();
            Plugin plugin = listener.getPlugin();
            if (!PluginHooker.getPluginManager().getPluginsToHook().contains(plugin)) {
                continue;
            }

            if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
                if (callEvent) {
                    ProtocolLibPacketEvent packetEvent = new ProtocolLibPacketEvent(listener, event, outbound);
                    Bukkit.getPluginManager().callEvent(packetEvent);
                    if (packetEvent.isCancelled()) {
                        newListeners.removeListener(listener, outbound ? listener.getSendingWhitelist() : listener.getReceivingWhitelist());
                    }
                }
            } else {
                newListeners.removeListener(listener, outbound ? listener.getSendingWhitelist() : listener.getReceivingWhitelist());
            }
        }

        if (outbound) {
            dionaPlayer.setSendingCachedListeners(newListeners);
        } else {
            dionaPlayer.setReceivedCachedListeners(newListeners);
        }
        return newListeners;
    }

    private SortedPacketListenerList deepCopyListenerList(SortedPacketListenerList sortedPacketListenerList) {
        SortedPacketListenerList result = new SortedPacketListenerList();
        try {

            if (this.mapListeners == null) {
                this.mapListeners = SortedPacketListenerList.class.getSuperclass().getDeclaredField("mapListeners");
                this.mapListeners.setAccessible(true);
            }

            ConcurrentHashMap<Object, Object> listeners = (ConcurrentHashMap<Object, Object>) mapListeners.get(sortedPacketListenerList);
            ConcurrentHashMap<Object, Object> resultMap = listeners.keySet().stream().collect(
                    ConcurrentHashMap::new,
                    (map, packetType) -> map.put(packetType, new SortedCopyOnWriteArray((Collection) listeners.get(packetType))),
                    ConcurrentHashMap::putAll
            );

            mapListeners.set(result, resultMap);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
