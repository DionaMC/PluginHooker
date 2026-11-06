package dev.diona.pluginhooker.api.event;

import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Fired before a hooked plugin's ProtocolLib {@link PacketListener} processes a packet.
 */
@Getter
public class ProtocolLibHookEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final HookedPlayer hookedPlayer;
    private final PacketListener packetListener;
    private final PacketEvent packetEvent;
    private final boolean outbound;
    @Setter
    private boolean cancelled;

    public ProtocolLibHookEvent(
            Plugin plugin,
            HookedPlayer hookedPlayer,
            PacketListener packetListener,
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
