package dev.diona.pluginhooker.api.event;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Fired before a hooked plugin receives a Bukkit {@link Event} for an allowed player.
 */
@Getter
public class BukkitHookEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final Event targetEvent;
    private final HookedPlayer hookedPlayer;
    @Setter
    private boolean cancelled;

    public BukkitHookEvent(Plugin plugin, Event targetEvent, HookedPlayer hookedPlayer) {
        super(!Bukkit.isPrimaryThread());
        this.plugin = plugin;
        this.targetEvent = targetEvent;
        this.hookedPlayer = hookedPlayer;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
