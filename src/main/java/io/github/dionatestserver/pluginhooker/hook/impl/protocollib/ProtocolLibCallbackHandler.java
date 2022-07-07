package io.github.dionatestserver.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.concurrency.AbstractConcurrentListenerMultimap;
import com.comphenix.protocol.concurrency.SortedCopyOnWriteArray;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.injector.PrioritizedListener;
import com.comphenix.protocol.injector.SortedPacketListenerList;
import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.events.DionaProtocolLibPacketEvent;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ProtocolLibCallbackHandler {

    private MethodHandle listenersGetter;
    private MethodHandle listenersSetter;

    public SortedPacketListenerList handleProtocolLibPacket(SortedPacketListenerList listenerList, PacketEvent event, boolean outbound) {
        DionaPlayer dionaPlayer = DionaPluginHooker.getPlayerManager().getDionaPlayer(event.getPlayer());
        if (dionaPlayer == null) return listenerList;

        SortedPacketListenerList cachedListeners = dionaPlayer.getCachedListeners();
        if (cachedListeners != null) return cachedListeners;


        SortedPacketListenerList newListeners = this.deepCopyListenerList(listenerList);

        for (PrioritizedListener<PacketListener> value : newListeners.values()) {
            PacketListener listener = value.getListener();
            Plugin plugin = listener.getPlugin();
            if (!DionaPluginHooker.getPluginManager().getPluginsToHook().contains(plugin)) {
                continue;
            }

            if (dionaPlayer.getEnabledPlugins().contains(plugin)) {

                DionaProtocolLibPacketEvent packetEvent = new DionaProtocolLibPacketEvent(listener, event, outbound);
                Bukkit.getPluginManager().callEvent(packetEvent);

                if (packetEvent.isCancelled()) {
                    newListeners.removeListener(listener, outbound ? listener.getSendingWhitelist() : listener.getReceivingWhitelist());
                }
            } else {
                newListeners.removeListener(listener, outbound ? listener.getSendingWhitelist() : listener.getReceivingWhitelist());
            }
        }

        dionaPlayer.setCachedListeners(newListeners);
        return newListeners;
    }

    private SortedPacketListenerList deepCopyListenerList(SortedPacketListenerList sortedPacketListenerList) {
        SortedPacketListenerList result = new SortedPacketListenerList();
        try {

            if (this.listenersGetter == null) {
                Field mapListeners = SortedPacketListenerList.class.getSuperclass().getDeclaredField("mapListeners");
                mapListeners.setAccessible(true);

                MethodHandles.Lookup lookup = MethodHandles.lookup();
                this.listenersGetter = lookup.unreflectGetter(mapListeners);
                this.listenersSetter = lookup.unreflectSetter(mapListeners);
            }

//            ConcurrentHashMap<Object, Object> listeners = (ConcurrentHashMap<Object, Object>) mapListeners.get(sortedPacketListenerList);
            ConcurrentMap<Object, Object> listeners =
                    (ConcurrentMap<Object, Object>) listenersGetter.invokeExact(((AbstractConcurrentListenerMultimap) sortedPacketListenerList));
            ConcurrentMap<Object, Object> resultMap = listeners.keySet().stream().collect(
                    ConcurrentHashMap::new,
                    (map, packetType) -> map.put(packetType, new SortedCopyOnWriteArray((Collection) listeners.get(packetType))),
                    ConcurrentHashMap::putAll
            );

//            mapListeners.set(result, resultMap);
            listenersSetter.invokeExact((AbstractConcurrentListenerMultimap) result, resultMap);
            return result;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

}
