package dev.diona.pluginhooker.events;

import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import lombok.Getter;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PacketEventsPacketEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    @Getter
    private final PacketListenerCommon packetListener;
    @Getter
    private final PacketEvent packetEvent;
    private boolean cancel;

    public PacketEventsPacketEvent(PacketListenerCommon packetListener, PacketEvent packetEvent) {
        super(true);
        this.packetListener = packetListener;
        this.packetEvent = packetEvent;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
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
