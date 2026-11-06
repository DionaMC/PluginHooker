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
import java.util.Optional;

/**
 * High-performance extractor that resolves the associated {@link Player} from a Bukkit {@link Event}.
 * Uses fast instanceof checks for common Bukkit event hierarchies and a lock-free {@link ClassValue}
 * {@link MethodHandle} cache for unknown or custom events.
 */
public final class EventPlayerResolver {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final ClassValue<Optional<MethodHandle>> REFLECTIVE_ACCESSOR_CACHE = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> eventClass) {
            return Optional.ofNullable(findPlayerAccessor(eventClass));
        }
    };

    private EventPlayerResolver() {
    }

    public static Player resolvePlayer(Event event, boolean useReflectionFallback) {
        if (event instanceof PlayerEvent playerEvent) {
            return playerEvent.getPlayer();
        }

        if (event instanceof EntityDamageByEntityEvent damageEvent) {
            Entity damager = damageEvent.getDamager();
            if (damager instanceof Player player) {
                return player;
            }
            if (damager instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof Player shooterPlayer) {
                    return shooterPlayer;
                }
            }
        }

        if (event instanceof EntityEvent entityEvent) {
            Entity entity = entityEvent.getEntity();
            if (entity instanceof Player player) {
                return player;
            }
            if (entityEvent instanceof ProjectileLaunchEvent launchEvent) {
                ProjectileSource shooter = launchEvent.getEntity().getShooter();
                return shooter instanceof Player shooterPlayer ? shooterPlayer : null;
            }
        }

        if (event instanceof InventoryInteractEvent invInteract) {
            HumanEntity clicker = invInteract.getWhoClicked();
            return clicker instanceof Player player ? player : null;
        }
        if (event instanceof InventoryOpenEvent invOpen) {
            HumanEntity viewer = invOpen.getPlayer();
            return viewer instanceof Player player ? player : null;
        }
        if (event instanceof InventoryCloseEvent invClose) {
            HumanEntity viewer = invClose.getPlayer();
            return viewer instanceof Player player ? player : null;
        }

        if (event instanceof BlockBreakEvent e) return e.getPlayer();
        if (event instanceof BlockPlaceEvent e) return e.getPlayer();
        if (event instanceof BlockDamageEvent e) return e.getPlayer();
        if (event instanceof BlockIgniteEvent e) return e.getPlayer();
        if (event instanceof SignChangeEvent e) return e.getPlayer();
        if (event instanceof FurnaceExtractEvent e) return e.getPlayer();
        if (event instanceof EnchantItemEvent e) return e.getEnchanter();
        if (event instanceof PrepareItemEnchantEvent e) return e.getEnchanter();

        if (event instanceof VehicleDamageEvent e) {
            return e.getAttacker() instanceof Player p ? p : null;
        }
        if (event instanceof VehicleDestroyEvent e) {
            return e.getAttacker() instanceof Player p ? p : null;
        }
        if (event instanceof VehicleEnterEvent e) {
            return e.getEntered() instanceof Player p ? p : null;
        }
        if (event instanceof VehicleExitEvent e) {
            return e.getExited() instanceof Player p ? p : null;
        }
        if (event instanceof VehicleEntityCollisionEvent e) {
            return e.getEntity() instanceof Player p ? p : null;
        }

        if (!useReflectionFallback) {
            return null;
        }

        Optional<MethodHandle> handleOpt = REFLECTIVE_ACCESSOR_CACHE.get(event.getClass());
        if (handleOpt.isEmpty()) {
            return null;
        }
        try {
            Object result = handleOpt.get().invokeExact((Object) event);
            return result instanceof Player player ? player : null;
        } catch (Throwable t) {
            return null;
        }
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
                            .asType(MethodType.methodType(Object.class, Object.class));
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
                                .asType(MethodType.methodType(Object.class, Object.class));
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
        }
        return null;
    }
}
