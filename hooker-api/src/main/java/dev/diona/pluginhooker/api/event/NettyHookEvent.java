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
 * Fired before a hooked plugin's Netty ChannelHandler processes an inbound or outbound message.
 */
@Getter
public class NettyHookEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final HookedPlayer hookedPlayer;
    private final Object message;
    private final boolean outbound;
    @Setter
    private boolean cancelled;

    public NettyHookEvent(Plugin plugin, HookedPlayer hookedPlayer, Object message, boolean outbound) {
        super(!Bukkit.isPrimaryThread());
        this.plugin = plugin;
        this.hookedPlayer = hookedPlayer;
        this.message = message;
        this.outbound = outbound;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
