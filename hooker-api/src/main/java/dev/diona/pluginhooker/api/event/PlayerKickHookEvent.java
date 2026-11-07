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
 * Fired when a hooked plugin attempts to kick a player via {@code Player#kickPlayer(String)}
 * or Paper's {@code Player#kick(Component, Cause)}.
 * <p>
 * If the hooked plugin is currently disabled for the target player, {@link #isCancelled()}
 * is initialized to {@code true} by default to prevent false kicks (e.g. transaction timeouts).
 */
@Getter
public class PlayerKickHookEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final HookedPlayer hookedPlayer;
    /**
     * The kick reason as a legacy-formatted String (automatically serialized via
     * {@code LegacyComponentSerializer} if kicked using Paper's Adventure {@code Component} API).
     */
    private final String reason;
    /**
     * The raw reason object passed to the kick method (either a {@link String} or Paper's
     * {@code net.kyori.adventure.text.Component}).
     * Kept as {@link Object} in bytecode descriptor to remain binary-compatible with Spigot 1.8.8.
     */
    private final Object rawReason;
    private final boolean pluginEnabledForPlayer;
    @Setter
    private boolean cancelled;

    public PlayerKickHookEvent(
            Plugin plugin,
            HookedPlayer hookedPlayer,
            String reason,
            Object rawReason,
            boolean pluginEnabledForPlayer,
            boolean defaultCancelled
    ) {
        super(!Bukkit.isPrimaryThread());
        this.plugin = plugin;
        this.hookedPlayer = hookedPlayer;
        this.reason = reason;
        this.rawReason = rawReason;
        this.pluginEnabledForPlayer = pluginEnabledForPlayer;
        this.cancelled = defaultCancelled;
    }

    /**
     * Returns the raw kick reason cast to the caller's expected type
     * (e.g. {@code Component comp = event.getRawReason();}).
     */
    @SuppressWarnings("unchecked")
    public <T> T getRawReason() {
        return (T) rawReason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
