package io.github.dionatestserver.pluginhooker.events;

import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import lombok.Getter;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ProtocolLibPacketEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    private boolean cancel;

    @Getter
    private final PacketListener packetListener;
    @Getter
    private final PacketEvent packetEvent;
    @Getter
    private final boolean outbound;

    public ProtocolLibPacketEvent(PacketListener packetListener, PacketEvent packetEvent, boolean outbound) {
        super(packetEvent.isAsynchronous());
        this.packetListener = packetListener;
        this.packetEvent = packetEvent;
        this.outbound = outbound;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return cancel;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancel = cancel;
    }
}
