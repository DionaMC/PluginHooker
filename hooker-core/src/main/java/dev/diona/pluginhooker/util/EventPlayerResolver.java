package dev.diona.pluginhooker.util;

import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Function;

/**
 * High-performance O(1) extractor that resolves the associated {@link Player} from a Bukkit {@link Event}.
 * Uses a direct {@link PlayerEvent} superclass fast-path and a lock-free {@link ClassValue} dispatch table
 * so non-{@link PlayerEvent}s never fall through a chain of sequential {@code instanceof} checks.
 */
public final class EventPlayerResolver {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ResolvedExtractor EMPTY_EXTRACTOR = new ResolvedExtractor(null, null);

    private record ResolvedExtractor(
            Function<Event, Player> knownExtractor,
            Function<Event, Player> reflectiveExtractor
    ) {
        Player extract(Event event, boolean useReflectionFallback) {
            if (knownExtractor != null) {
                return knownExtractor.apply(event);
            }
            if (useReflectionFallback && reflectiveExtractor != null) {
                return reflectiveExtractor.apply(event);
            }
            return null;
        }
    }

    private static final ClassValue<ResolvedExtractor> EXTRACTOR_CACHE = new ClassValue<>() {
        @Override
        protected ResolvedExtractor computeValue(Class<?> eventClass) {
            Function<Event, Player> known = buildKnownExtractor(eventClass);
            if (known != null) {
                return new ResolvedExtractor(known, null);
            }
            MethodHandle mh = findPlayerAccessor(eventClass);
            if (mh == null) {
                return EMPTY_EXTRACTOR;
            }
            return new ResolvedExtractor(null, ev -> {
                try {
                    Object res = mh.invokeExact(ev);
                    return res instanceof Player p ? p : null;
                } catch (Throwable t) {
                    return null;
                }
            });
        }
    };

    private EventPlayerResolver() {
    }

    public static Player resolvePlayer(Event event, boolean useReflectionFallback) {
        if (event == null) {
            return null;
        }
        if (event instanceof PlayerEvent playerEvent) {
            return playerEvent.getPlayer();
        }
        return EXTRACTOR_CACHE.get(event.getClass()).extract(event, useReflectionFallback);
    }

    private static Function<Event, Player> buildKnownExtractor(Class<?> eventClass) {
        if (PlayerEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((PlayerEvent) ev).getPlayer();
        }
        if (EntityDamageByEntityEvent.class.isAssignableFrom(eventClass)) {
            return ev -> {
                EntityDamageByEntityEvent dmg = (EntityDamageByEntityEvent) ev;
                Entity damager = dmg.getDamager();
                if (damager instanceof Player player) {
                    return player;
                }
                if (damager instanceof Projectile projectile
                        && projectile.getShooter() instanceof Player shooterPlayer) {
                    return shooterPlayer;
                }
                return dmg.getEntity() instanceof Player victim ? victim : null;
            };
        }
        if (ProjectileLaunchEvent.class.isAssignableFrom(eventClass)) {
            return ev -> {
                Projectile entity = ((ProjectileLaunchEvent) ev).getEntity();
                if (entity != null && entity.getShooter() instanceof Player shooter) {
                    return shooter;
                }
                return null;
            };
        }
        if (EntityEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((EntityEvent) ev).getEntity() instanceof Player p ? p : null;
        }
        if (InventoryInteractEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((InventoryInteractEvent) ev).getWhoClicked() instanceof Player p ? p : null;
        }
        if (InventoryOpenEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((InventoryOpenEvent) ev).getPlayer() instanceof Player p ? p : null;
        }
        if (InventoryCloseEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((InventoryCloseEvent) ev).getPlayer() instanceof Player p ? p : null;
        }
        if (BlockBreakEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((BlockBreakEvent) ev).getPlayer();
        }
        if (BlockPlaceEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((BlockPlaceEvent) ev).getPlayer();
        }
        if (BlockDamageEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((BlockDamageEvent) ev).getPlayer();
        }
        if (BlockIgniteEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((BlockIgniteEvent) ev).getPlayer();
        }
        if (SignChangeEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((SignChangeEvent) ev).getPlayer();
        }
        if (FurnaceExtractEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((FurnaceExtractEvent) ev).getPlayer();
        }
        if (EnchantItemEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((EnchantItemEvent) ev).getEnchanter();
        }
        if (PrepareItemEnchantEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((PrepareItemEnchantEvent) ev).getEnchanter();
        }
        if (VehicleDamageEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((VehicleDamageEvent) ev).getAttacker() instanceof Player p ? p : null;
        }
        if (VehicleDestroyEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((VehicleDestroyEvent) ev).getAttacker() instanceof Player p ? p : null;
        }
        if (VehicleEnterEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((VehicleEnterEvent) ev).getEntered() instanceof Player p ? p : null;
        }
        if (VehicleExitEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((VehicleExitEvent) ev).getExited() instanceof Player p ? p : null;
        }
        if (VehicleEntityCollisionEvent.class.isAssignableFrom(eventClass)) {
            return ev -> ((VehicleEntityCollisionEvent) ev).getEntity() instanceof Player p ? p : null;
        }
        return null;
    }

    private static MethodHandle findPlayerAccessor(Class<?> eventClass) {
        // 1. Check for public no-arg getter returning Player or HumanEntity (e.g. getPlayer, getWhoClicked)
        for (Method method : eventClass.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                continue;
            }
            if (Player.class.isAssignableFrom(method.getReturnType())
                    || HumanEntity.class.isAssignableFrom(method.getReturnType())) {
                try {
                    method.setAccessible(true);
                    return LOOKUP.unreflect(method)
                            .asType(MethodType.methodType(Object.class, Event.class));
                } catch (IllegalAccessException ignored) {
                }
            }
        }

        // 2. Walk class hierarchy for any field assignable to Player
        for (Class<?> c = eventClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (Player.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return LOOKUP.unreflectGetter(field)
                                .asType(MethodType.methodType(Object.class, Event.class));
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
        }
        return null;
    }
}
