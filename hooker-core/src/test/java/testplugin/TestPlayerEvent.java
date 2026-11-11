package testplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Loaded apart from the test classes, whose events count as PluginHooker's own and always pass. */
public final class TestPlayerEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public TestPlayerEvent(Player player) {
        super(player);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
