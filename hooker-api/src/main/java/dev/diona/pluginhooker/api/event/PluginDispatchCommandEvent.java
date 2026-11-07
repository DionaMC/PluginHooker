package dev.diona.pluginhooker.api.event;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Fired when a hooked plugin dispatches a command via {@code Server#dispatchCommand}.
 * <p>
 * If the command targets an online player for whom the calling plugin is disabled,
 * {@link #isCancelled()} is initialized to {@code true} by default.
 */
@Getter
public class PluginDispatchCommandEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin plugin;
    private final CommandSender sender;
    private final String commandLine;
    private final HookedPlayer targetPlayer;
    private final boolean pluginEnabledForTarget;
    @Setter
    private boolean cancelled;

    public PluginDispatchCommandEvent(
            Plugin plugin,
            CommandSender sender,
            String commandLine,
            HookedPlayer targetPlayer,
            boolean pluginEnabledForTarget,
            boolean defaultCancelled
    ) {
        super(!Bukkit.isPrimaryThread());
        this.plugin = plugin;
        this.sender = sender;
        this.commandLine = commandLine;
        this.targetPlayer = targetPlayer;
        this.pluginEnabledForTarget = pluginEnabledForTarget;
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
