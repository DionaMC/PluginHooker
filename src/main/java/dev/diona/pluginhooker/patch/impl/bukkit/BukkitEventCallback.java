package dev.diona.pluginhooker.patch.impl.bukkit;

import lombok.Getter;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.function.BiPredicate;

public class BukkitEventCallback {

    @Getter
    private static BukkitEventCallback instance;

    private final BiPredicate<Plugin, Event> callback;

    public BukkitEventCallback(BiPredicate<Plugin, Event> callback) {
        instance = this;
        this.callback = callback;
    }

    public boolean onCallEvent(Plugin plugin, Event event) {
        return callback.test(plugin, event);
    }
}
