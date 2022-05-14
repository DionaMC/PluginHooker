package io.github.dionatestserver.anticheatmanager.hook;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.concurrency.SortedCopyOnWriteArray;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.injector.PrioritizedListener;
import com.comphenix.protocol.injector.SortedPacketListenerList;
import io.github.dionatestserver.anticheatmanager.Diona;
import io.github.dionatestserver.anticheatmanager.player.DionaPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.*;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

public class CallbackHandler {

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

        if (Diona.getInstance().getAnticheatManager().getLoadedAnticheat().stream().noneMatch(anticheat -> anticheat.getPlugin() == plugin))
            return false;

        return dionaPlayer.getEnabledAnticheats().stream().noneMatch(anticheat -> anticheat.getPlugin() == plugin);
    }

    public SortedPacketListenerList handleProtocolLibPacket(SortedPacketListenerList listenerList, PacketEvent event, boolean outbound) {
        DionaPlayer dionaPlayer = Diona.getInstance().getPlayerManager().getDionaPlayer(event.getPlayer());
        if (dionaPlayer == null) return listenerList;

        SortedPacketListenerList newListeners = this.deepCopyListenerList(listenerList);

        for (PrioritizedListener<PacketListener> value : newListeners.values()) {
            PacketListener listener = value.getListener();
            if (Diona.getInstance().getAnticheatManager().getLoadedAnticheat().stream()
                    .noneMatch(anticheat -> anticheat.getPlugin() == listener.getPlugin())) {
                continue;
            }

            if (dionaPlayer.getEnabledAnticheats().stream().noneMatch(anticheat -> anticheat.getPlugin() == listener.getPlugin())) {
                newListeners.removeListener(listener, outbound ? listener.getSendingWhitelist() : listener.getReceivingWhitelist());
//                System.out.println(listener.getPlugin() + " " + listener.getClass().getSimpleName() + " removed " + types.size());
            }
        }

//        System.out.println("listeners size " + Iterators.size(listenerList.values().iterator()) + "  newlisteners size " +  Iterators.size(newListeners.values().iterator()));

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

        if (event instanceof BlockBreakEvent) {
            return ((BlockBreakEvent) event).getPlayer();
        }

        if (event instanceof BlockDamageEvent) {
            return ((BlockDamageEvent) event).getPlayer();
        }

        if (event instanceof BlockIgniteEvent) {
            return ((BlockIgniteEvent) event).getPlayer();
        }

        if (event instanceof BlockMultiPlaceEvent) {
            return ((BlockMultiPlaceEvent) event).getPlayer();
        }

        if (event instanceof BlockPlaceEvent) {
            return ((BlockPlaceEvent) event).getPlayer();
        }

        if (event instanceof SignChangeEvent) {
            return ((SignChangeEvent) event).getPlayer();
        }

        if (event instanceof EnchantItemEvent) {
            return ((EnchantItemEvent) event).getEnchanter();
        }
        if (event instanceof PrepareItemEnchantEvent) {
            return ((PrepareItemEnchantEvent) event).getEnchanter();
        }

        if (event instanceof EntityDamageByEntityEvent) {
            Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
            return damager instanceof Player ? (Player) damager : null;
        }

        if (event instanceof ProjectileLaunchEvent) {
            ProjectileSource shooter = ((ProjectileLaunchEvent) event).getEntity().getShooter();
            return shooter instanceof Player ? (Player) shooter : null;
        }

        if (event instanceof EntityEvent) {
            Entity entity = ((EntityEvent) event).getEntity();
            return entity instanceof Player ? (Player) entity : null;
        }

        if (event instanceof FurnaceExtractEvent) {
            return ((FurnaceExtractEvent) event).getPlayer();
        }

        if (event instanceof InventoryCloseEvent) {
            return (Player) ((InventoryCloseEvent) event).getPlayer();
        }

        if (event instanceof InventoryInteractEvent) {
            return (Player) ((InventoryInteractEvent) event).getWhoClicked();
        }

        if (event instanceof InventoryOpenEvent) {
            return (Player) ((InventoryOpenEvent) event).getPlayer();
        }


        if (event instanceof VehicleDamageEvent) {
            Entity attacker = ((VehicleDamageEvent) event).getAttacker();
            return attacker instanceof Player ? (Player) attacker : null;
        }

        if (event instanceof VehicleDestroyEvent) {
            Entity attacker = ((VehicleDestroyEvent) event).getAttacker();
            return attacker instanceof Player ? (Player) attacker : null;
        }

        if (event instanceof VehicleEnterEvent) {
            Entity enteredEntity = ((VehicleEnterEvent) event).getEntered();
            return enteredEntity instanceof Player ? (Player) enteredEntity : null;
        }

        if (event instanceof VehicleEntityCollisionEvent) {
            Entity entity = ((VehicleEntityCollisionEvent) event).getEntity();
            return entity instanceof Player ? (Player) entity : null;
        }

        if (event instanceof VehicleExitEvent) {
            Entity exitedEntity = ((VehicleExitEvent) event).getExited();
            return exitedEntity instanceof Player ? (Player) exitedEntity : null;
        }

        return null;

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

}
