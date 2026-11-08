package dev.diona.pluginhooker.api.event;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player's network connection is initialized at the end of NMS
 * {@code PlayerConnection.<init>} or {@code ServerGamePacketListenerImpl.<init>},
 * immediately after the {@link HookedPlayer} is registered and bound to the Netty channel,
 * and before the initial {@code JOIN_GAME} packet is sent.
 * <p>
 * Management plugins can listen to this event to pre-enable plugins on the {@link HookedPlayer}
 * so that the selected plugins observe the entire login/join packet sequence natively.
 */
@Getter
public class PlayerHookRegisterEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final HookedPlayer hookedPlayer;

    public PlayerHookRegisterEvent(Player player, HookedPlayer hookedPlayer) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
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
