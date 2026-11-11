package testplugin;

import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/** Ends in {@link RegisteredListener#callEvent} like Bukkit's TimedRegisteredListener. */
public final class DelegatingRegisteredListener extends RegisteredListener {

    public DelegatingRegisteredListener(Plugin plugin, EventExecutor executor) {
        super(new Listener() {
        }, executor, EventPriority.NORMAL, plugin, false);
    }

    @Override
    public void callEvent(Event event) throws EventException {
        super.callEvent(event);
    }
}
