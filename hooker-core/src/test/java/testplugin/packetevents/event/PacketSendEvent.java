package testplugin.packetevents.event;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulated shaded PacketSendEvent to test PacketEventsReflector read-only isolation in PatchVerifier.
 */
public class PacketSendEvent {

    private Object byteBuf;
    private Object player;
    private boolean cancelled;
    private boolean needsReEncode;
    private Object lastUsedWrapper;
    private final List<Runnable> postTasks = new ArrayList<>();
    private final List<Runnable> tasksAfterSend = new ArrayList<>();

    public PacketSendEvent(Object byteBuf) {
        this.byteBuf = byteBuf;
    }

    public PacketSendEvent(Object byteBuf, Object player) {
        this.byteBuf = byteBuf;
        this.player = player;
    }

    public Object getPlayer() {
        return player;
    }

    public void call(MockShadedListener listener) {
        listener.onPacketSend(this);
    }

    public Object getByteBuf() {
        return byteBuf;
    }

    public void setByteBuf(Object byteBuf) {
        this.byteBuf = byteBuf;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public boolean needsReEncode() {
        return needsReEncode;
    }

    public void markForReEncode(boolean needsReEncode) {
        this.needsReEncode = needsReEncode;
    }

    public Object getLastUsedWrapper() {
        return lastUsedWrapper;
    }

    public void setLastUsedWrapper(Object lastUsedWrapper) {
        this.lastUsedWrapper = lastUsedWrapper;
    }

    public boolean hasPostTasks() {
        return !postTasks.isEmpty();
    }

    public List<Runnable> getPostTasks() {
        return postTasks;
    }

    public boolean hasTasksAfterSend() {
        return !tasksAfterSend.isEmpty();
    }

    public List<Runnable> getTasksAfterSend() {
        return tasksAfterSend;
    }

    public interface MockShadedListener {
        void onPacketSend(PacketSendEvent event);
    }
}
