package testplugin;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.concurrent.atomic.AtomicInteger;

/** Dispatches on its own like Intave's listener, never through {@link RegisteredListener#callEvent}. */
public final class OverridingRegisteredListener extends RegisteredListener {

    private final AtomicInteger calls;

    public OverridingRegisteredListener(Plugin plugin, AtomicInteger calls) {
        super(new Listener() {
        }, null, EventPriority.NORMAL, plugin, false);
        this.calls = calls;
    }

    @Override
    public void callEvent(Event event) {
        calls.incrementAndGet();
    }
}
