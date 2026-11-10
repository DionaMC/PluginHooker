package dev.diona.pluginhooker.api.event;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Fired when a hooked plugin attempts to close or disconnect a player's Netty {@link Channel}
 * directly (e.g. via {@code channel.close()}, {@code ctx.close()}, or PacketEvents' {@code user.closeConnection()}).
 * <p>
 * If the hooked plugin is currently disabled for the target player, {@link #isCancelled()}
 * is initialized to {@code true} by default to prevent false disconnections.
 */
@Getter
public class PlayerChannelCloseHookEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final HookedPlayer hookedPlayer;
    private final Channel channel;
    private final boolean pluginEnabledForPlayer;
    @Setter
    private boolean cancelled;

    public PlayerChannelCloseHookEvent(
            Plugin plugin,
            HookedPlayer hookedPlayer,
            Channel channel,
            boolean pluginEnabledForPlayer,
            boolean defaultCancelled
    ) {
        super(!Bukkit.isPrimaryThread());
        this.plugin = plugin;
        this.hookedPlayer = hookedPlayer;
        this.channel = channel;
        this.pluginEnabledForPlayer = pluginEnabledForPlayer;
        this.cancelled = defaultCancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
