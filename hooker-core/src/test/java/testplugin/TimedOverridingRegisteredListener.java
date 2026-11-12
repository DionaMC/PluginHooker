package testplugin;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.TimedRegisteredListener;

import java.util.concurrent.atomic.AtomicInteger;

/** Subclass of {@link TimedRegisteredListener} that overrides {@code callEvent} without delegating to super. */
public final class TimedOverridingRegisteredListener extends TimedRegisteredListener {

    private final AtomicInteger calls;

    public TimedOverridingRegisteredListener(Plugin plugin, AtomicInteger calls) {
        super(new Listener() {
        }, null, EventPriority.NORMAL, plugin, false);
        this.calls = calls;
    }

    @Override
    public void callEvent(Event event) {
        calls.incrementAndGet();
    }
}
