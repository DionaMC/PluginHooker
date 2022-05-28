package io.github.dionatestserver.pluginhooker.events;

import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import lombok.Getter;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

public class DionaBukkitListenerEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    private boolean cancel;

    @Getter
    private final Plugin plugin;
    @Getter
    private final Event event;
    @Getter
    private final DionaPlayer dionaPlayer;

    public DionaBukkitListenerEvent(Plugin plugin, Event event) {
        this(plugin, event, null);
    }

    public DionaBukkitListenerEvent(Plugin plugin, Event event, DionaPlayer dionaPlayer) {
        super(event.isAsynchronous());
        this.plugin = plugin;
        this.event = event;
        this.dionaPlayer = dionaPlayer;
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
