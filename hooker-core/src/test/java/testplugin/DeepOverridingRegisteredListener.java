package testplugin;

import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicInteger;

/** Indirect subclass of {@link org.bukkit.plugin.RegisteredListener} whose constant pool never names RegisteredListener. */
public final class DeepOverridingRegisteredListener extends AbstractBaseRegisteredListener {

    private final AtomicInteger calls;

    public DeepOverridingRegisteredListener(Plugin plugin, AtomicInteger calls) {
        super(plugin);
        this.calls = calls;
    }

    @Override
    public void callEvent(Event event) {
        calls.incrementAndGet();
    }
}
