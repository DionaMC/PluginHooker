package io.github.dionatestserver.pluginhooker.hook.impl.bukkit;

import lombok.Getter;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.function.BiPredicate;

public class BukkitEventHooker {

    @Getter
    private static BukkitEventHooker instance;

    private final BiPredicate<Plugin, Event> callback;

    public BukkitEventHooker(BiPredicate<Plugin, Event> callback) {
        instance = this;
        this.callback = callback;
    }

    public boolean onCallEvent(Plugin plugin, Event event) {
        return callback.test(plugin, event);
    }
}
