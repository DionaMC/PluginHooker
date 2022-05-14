package io.github.dionatestserver.pluginhooker.hook;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.concurrency.SortedCopyOnWriteArray;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.injector.PrioritizedListener;
import com.comphenix.protocol.injector.SortedPacketListenerList;
import io.github.dionatestserver.pluginhooker.Diona;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.*;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class CallbackHandler {

    private final Map<Class<? extends Event>, Function<Event, Player>> eventMap = new LinkedHashMap<>();

    public CallbackHandler() {
        this.initEventMap();
    }

    public boolean handleBukkitEvent(Plugin plugin, Event event) {
        // Don't handle those events
        if (event instanceof AsyncPlayerPreLoginEvent
                || event instanceof PlayerPreLoginEvent
                || event instanceof PlayerJoinEvent
                || event instanceof PlayerQuitEvent
                || event instanceof PlayerLoginEvent)
            return false;
        DionaPlayer dionaPlayer = Diona.getInstance().getPlayerManager().getDionaPlayer(this.getPlayerByEvent(event));
        if (dionaPlayer == null) return false;

        if (Diona.getInstance().getPluginManager().getLoadedDionaPlugin().stream().noneMatch(dionaPlugin -> dionaPlugin.getPlugin() == plugin))
            return false;

        return dionaPlayer.getEnabledDionaPlugins().stream().noneMatch(dionaPlugin -> dionaPlugin.getPlugin() == plugin);
    }

    public SortedPacketListenerList handleProtocolLibPacket(SortedPacketListenerList listenerList, PacketEvent event, boolean outbound) {
        DionaPlayer dionaPlayer = Diona.getInstance().getPlayerManager().getDionaPlayer(event.getPlayer());
        if (dionaPlayer == null) return listenerList;

        SortedPacketListenerList newListeners = this.deepCopyListenerList(listenerList);

        for (PrioritizedListener<PacketListener> value : newListeners.values()) {
            PacketListener listener = value.getListener();
            if (Diona.getInstance().getPluginManager().getLoadedDionaPlugin().stream()
                    .noneMatch(dionaPlugin -> dionaPlugin.getPlugin() == listener.getPlugin())) {
                continue;
            }

            if (dionaPlayer.getEnabledDionaPlugins().stream().noneMatch(dionaPlugin -> dionaPlugin.getPlugin() == listener.getPlugin())) {
                newListeners.removeListener(listener, outbound ? listener.getSendingWhitelist() : listener.getReceivingWhitelist());
            }
        }


        return newListeners;
    }

    private SortedPacketListenerList deepCopyListenerList(SortedPacketListenerList sortedPacketListenerList) {
        //TODO 需要优化
        SortedPacketListenerList result = new SortedPacketListenerList();
        try {
            Field mapListeners = sortedPacketListenerList.getClass().getSuperclass().getDeclaredField("mapListeners");
            mapListeners.setAccessible(true);
            ConcurrentHashMap<PacketType, SortedCopyOnWriteArray<PrioritizedListener<PacketListener>>> listeners
                    = (ConcurrentHashMap<PacketType, SortedCopyOnWriteArray<PrioritizedListener<PacketListener>>>) mapListeners.get(sortedPacketListenerList);

            ConcurrentHashMap<PacketType, SortedCopyOnWriteArray<PrioritizedListener<PacketListener>>> concurrentHashMap = new ConcurrentHashMap<>();

            for (PacketType packetType : listeners.keySet()) {
                SortedCopyOnWriteArray<PrioritizedListener<PacketListener>> listenerArray = listeners.get(packetType);
                SortedCopyOnWriteArray<PrioritizedListener<PacketListener>> newListenerArray = new SortedCopyOnWriteArray<>(listenerArray);
                concurrentHashMap.put(packetType, newListenerArray);
            }

            mapListeners.set(result, concurrentHashMap);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private Player getPlayerByEvent(Event event) {
        // return player from PlayerEvent
        if (event instanceof PlayerEvent)
            return ((PlayerEvent) event).getPlayer();

        if (event instanceof EntityEvent) {
            if (event instanceof EntityDamageByEntityEvent) {
                Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
                if (damager instanceof Player)
                    return (Player) damager;
            }
            Entity entity = ((EntityEvent) event).getEntity();
            if (entity instanceof Player)
                return (Player) entity;
            else if (event instanceof ProjectileLaunchEvent) {
                ProjectileSource shooter = ((ProjectileLaunchEvent) event).getEntity().getShooter();
                return shooter instanceof Player ? (Player) shooter : null;
            }
        }

        return this.eventMap.get(event.getClass()).apply(event);

        // Try to get the player field from the event
        /*
        try {
            Field playerField = event.getClass().getDeclaredField("player");
            if (!playerField.isAccessible()) playerField.setAccessible(true);
            Object player = playerField.get(event);
            if (player instanceof Player) {
                return (Player) player;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        **/

    }

    private void initEventMap() {
        this.eventMap.put(BlockBreakEvent.class, event -> ((BlockBreakEvent) event).getPlayer());
        this.eventMap.put(BlockDamageEvent.class, event -> ((BlockDamageEvent) event).getPlayer());
        this.eventMap.put(BlockIgniteEvent.class, event -> ((BlockIgniteEvent) event).getPlayer());
        this.eventMap.put(BlockMultiPlaceEvent.class, event -> ((BlockMultiPlaceEvent) event).getPlayer());
        this.eventMap.put(BlockPlaceEvent.class, event -> ((BlockPlaceEvent) event).getPlayer());
        this.eventMap.put(SignChangeEvent.class, event -> ((SignChangeEvent) event).getPlayer());
        this.eventMap.put(EnchantItemEvent.class, event -> ((EnchantItemEvent) event).getEnchanter());
        this.eventMap.put(PrepareItemEnchantEvent.class, event -> ((PrepareItemEnchantEvent) event).getEnchanter());
        this.eventMap.put(FurnaceExtractEvent.class, event -> ((FurnaceExtractEvent) event).getPlayer());
        this.eventMap.put(InventoryClickEvent.class, event -> {
            HumanEntity player = ((InventoryClickEvent) event).getWhoClicked();
            return player instanceof Player ? (Player) player : null;
        });
        this.eventMap.put(InventoryCloseEvent.class, event -> {
            HumanEntity player = ((InventoryCloseEvent) event).getPlayer();
            return player instanceof Player ? (Player) player : null;
        });
        this.eventMap.put(InventoryDragEvent.class, event -> {
            HumanEntity player = ((InventoryDragEvent) event).getWhoClicked();
            return player instanceof Player ? (Player) player : null;
        });
        this.eventMap.put(InventoryInteractEvent.class, event -> {
            HumanEntity player = ((InventoryInteractEvent) event).getWhoClicked();
            return player instanceof Player ? (Player) player : null;
        });
        this.eventMap.put(InventoryOpenEvent.class, event -> {
            HumanEntity player = ((InventoryOpenEvent) event).getPlayer();
            return player instanceof Player ? (Player) player : null;
        });
        this.eventMap.put(VehicleDamageEvent.class, event -> {
            Entity attacker = ((VehicleDamageEvent) event).getAttacker();
            return attacker instanceof Player ? (Player) attacker : null;
        });
        this.eventMap.put(VehicleDestroyEvent.class, event -> {
            Entity attacker = ((VehicleDestroyEvent) event).getAttacker();
            return attacker instanceof Player ? (Player) attacker : null;
        });
        this.eventMap.put(VehicleEnterEvent.class, event -> {
            Entity enteredEntity = ((VehicleEnterEvent) event).getEntered();
            return enteredEntity instanceof Player ? (Player) enteredEntity : null;
        });
        this.eventMap.put(VehicleEntityCollisionEvent.class, event -> {
            Entity entity = ((VehicleEntityCollisionEvent) event).getEntity();
            return entity instanceof Player ? (Player) entity : null;
        });
        this.eventMap.put(VehicleExitEvent.class, event -> {
            Entity exitedEntity = ((VehicleExitEvent) event).getExited();
            return exitedEntity instanceof Player ? (Player) exitedEntity : null;
        });
    }

}
