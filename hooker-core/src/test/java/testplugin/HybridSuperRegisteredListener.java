package testplugin;

import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calls {@code super.callEvent(event)} conditionally and runs side-effects around it; must still be guarded at entry.
 */
public final class HybridSuperRegisteredListener extends RegisteredListener {

    private final boolean fastPath;
    private final AtomicInteger sideEffects;

    public HybridSuperRegisteredListener(
            Plugin plugin,
            EventExecutor executor,
            boolean fastPath,
            AtomicInteger sideEffects
    ) {
        super(new Listener() {
        }, executor, EventPriority.NORMAL, plugin, false);
        this.fastPath = fastPath;
        this.sideEffects = sideEffects;
    }

    @Override
    public void callEvent(Event event) throws EventException {
        if (fastPath) {
            sideEffects.incrementAndGet();
            return;
        }
        super.callEvent(event);
        // Base callEvent returns normally when blocked, so post-super code must also never run when blocked
        sideEffects.incrementAndGet();
    }
}
