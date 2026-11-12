package testplugin;

import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/** Intermediate base listener that leaves {@code callEvent} to concrete subclasses. */
public abstract class AbstractBaseRegisteredListener extends RegisteredListener {

    protected AbstractBaseRegisteredListener(Plugin plugin) {
        super(new Listener() {
        }, null, EventPriority.NORMAL, plugin, false);
    }
}
