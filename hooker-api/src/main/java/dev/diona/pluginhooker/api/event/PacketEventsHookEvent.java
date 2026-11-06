package dev.diona.pluginhooker.api.event;

import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Fired before a hooked plugin's PacketEvents listener processes a packet.
 */
@Getter
public class PacketEventsHookEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final HookedPlayer hookedPlayer;
    private final PacketListenerCommon packetListener;
    private final PacketEvent packetEvent;
    private final boolean outbound;
    @Setter
    private boolean cancelled;

    public PacketEventsHookEvent(
            Plugin plugin,
            HookedPlayer hookedPlayer,
            PacketListenerCommon packetListener,
            PacketEvent packetEvent,
            boolean outbound
    ) {
        super(!Bukkit.isPrimaryThread());
        this.plugin = plugin;
        this.hookedPlayer = hookedPlayer;
        this.packetListener = packetListener;
        this.packetEvent = packetEvent;
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
