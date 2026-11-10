package dev.diona.pluginhooker.util;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

import java.util.function.Supplier;

/**
 * Null-safe Bukkit event dispatch helpers that avoid allocating or firing events when the server
 * is not initialized (e.g. early bootstrap, shutdown, or offline verification).
 */
public final class BukkitEvents {

    private BukkitEvents() {
    }

    public static void fireIfReady(Supplier<? extends Event> eventSupplier) {
        if (Bukkit.getServer() != null) {
            Bukkit.getPluginManager().callEvent(eventSupplier.get());
        }
    }

    public static <E extends Event & Cancellable> boolean fireCancellable(
            boolean enabled,
            boolean defaultCancelled,
            Supplier<E> eventSupplier
    ) {
        if (!enabled || Bukkit.getServer() == null) {
            return defaultCancelled;
        }
        E event = eventSupplier.get();
        Bukkit.getPluginManager().callEvent(event);
        return event.isCancelled();
    }
}
