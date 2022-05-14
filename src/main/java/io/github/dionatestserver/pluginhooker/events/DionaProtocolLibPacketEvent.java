package io.github.dionatestserver.pluginhooker.events;

import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import lombok.Getter;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class DionaProtocolLibPacketEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    private boolean cancel;

    @Getter
    private PacketListener packetListener;
    @Getter
    private PacketEvent packetEvent;
    @Getter
    private boolean outbound;

    public DionaProtocolLibPacketEvent(PacketListener packetListener, PacketEvent packetEvent, boolean outbound) {
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
