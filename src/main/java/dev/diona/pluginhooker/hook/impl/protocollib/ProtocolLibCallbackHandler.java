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

    private static Field MAP_LISTENERS_FIELD;

    static {
        try {
            MAP_LISTENERS_FIELD = SortedPacketListenerList.class.getSuperclass().getDeclaredField("mapListeners");
            MAP_LISTENERS_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    public ProtocolLibCallbackHandler() {
        PluginHooker.getConfigManager().loadConfig(this);
    }

    public SortedPacketListenerList handleProtocolLibPacket(SortedPacketListenerList listenerList, PacketEvent event, boolean outbound) {
        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(event.getPlayer());
        if (dionaPlayer == null) return listenerList;

        SortedPacketListenerList cachedListeners = outbound ? dionaPlayer.getSendingCachedListeners() : dionaPlayer.getReceivedCachedListeners();
        if (cachedListeners != null) return cachedListeners;

        // is inbound cache setup
        if (!outbound && !dionaPlayer.isReceivedListenersSetup()) {
            dionaPlayer.setReceivedListenersSetup(true);
            PluginHooker.getPlayerManager().getInboundListenerRefToPlayer().put(listenerList.hashCode(), dionaPlayer);
        }

        // is outbound cache setup
        if (outbound && !dionaPlayer.isSendingListenersSetup()) {
            dionaPlayer.setSendingListenersSetup(true);
            PluginHooker.getPlayerManager().getOutboundListenerRefToPlayer().put(listenerList.hashCode(), dionaPlayer);
        }


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
            ConcurrentHashMap<Object, Object> listeners = (ConcurrentHashMap<Object, Object>) MAP_LISTENERS_FIELD.get(sortedPacketListenerList);
            ConcurrentHashMap<Object, Object> resultMap = new ConcurrentHashMap<>();
            for (Object packetType : listeners.keySet()) {
                resultMap.put(packetType, new SortedCopyOnWriteArray((Collection) listeners.get(packetType)));
            }

            MAP_LISTENERS_FIELD.set(result, resultMap);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
