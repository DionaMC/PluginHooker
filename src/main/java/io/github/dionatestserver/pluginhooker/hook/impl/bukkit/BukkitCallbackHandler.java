package io.github.dionatestserver.pluginhooker.hook.impl.bukkit;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.events.DionaBukkitListenerEvent;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class BukkitCallbackHandler {
    private final Map<Class<? extends Event>, Function<Event, Player>> eventMap = new LinkedHashMap<>();

    private final Map<Class<? extends Event>, Field> eventFieldCache = new LinkedHashMap<>();

    private final Set<Class<? extends Event>> failedFieldCache = new HashSet<>();

    public BukkitCallbackHandler() {
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

        if (event.getClass().getClassLoader().equals(this.getClass().getClassLoader()))
            return false;

        if (!DionaPluginHooker.getPluginManager().getPluginsToHook().contains(plugin))
            return false;

        DionaPlayer dionaPlayer = DionaPluginHooker.getPlayerManager().getDionaPlayer(this.getPlayerByEvent(event));
        if (dionaPlayer == null) {
            DionaBukkitListenerEvent bukkitListenerEvent = new DionaBukkitListenerEvent(plugin, event);
            Bukkit.getPluginManager().callEvent(bukkitListenerEvent);

            return bukkitListenerEvent.isCancelled();
        } else {
            if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
                DionaBukkitListenerEvent bukkitListenerEvent = new DionaBukkitListenerEvent(plugin, event, dionaPlayer);
                Bukkit.getPluginManager().callEvent(bukkitListenerEvent);
                return bukkitListenerEvent.isCancelled();
            } else {
                return true;
            }
        }
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
                if (damager instanceof Projectile) {
                    Projectile projectile = (Projectile) damager;
                    ProjectileSource projectileSource = projectile.getShooter();
                    if (projectileSource instanceof Player)
                        return (Player) projectileSource;
                }
            }
            Entity entity = ((EntityEvent) event).getEntity();
            if (entity instanceof Player)
                return (Player) entity;
            else if (event instanceof ProjectileLaunchEvent) {
                ProjectileSource shooter = ((ProjectileLaunchEvent) event).getEntity().getShooter();
                return shooter instanceof Player ? (Player) shooter : null;
            }
        }

        Function<Event, Player> function = this.eventMap.get(event.getClass());
        if (function != null) return function.apply(event);

        // Try to get the player field from the event

        if (DionaConfig.useReflectionToGetEventPlayer) {
            if (this.failedFieldCache.contains(event.getClass())) {
                return null;
            }
            Field playerField = this.eventFieldCache.getOrDefault(event.getClass(), null);
            if (playerField == null) {
                try {
                    playerField = event.getClass().getDeclaredField("player");
                    playerField.setAccessible(true);
                    Player player = (Player) playerField.get(event);
                    this.eventFieldCache.put(event.getClass(), playerField);
                    return player;
                } catch (Exception e) {
                    this.failedFieldCache.add(event.getClass());
                    return null;
                }
            }

            try {
                return (Player) playerField.get(event);
            } catch (Exception e) {
                return null;
            }

        }

        return null;
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
