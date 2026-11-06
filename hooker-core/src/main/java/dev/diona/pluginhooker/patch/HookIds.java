package dev.diona.pluginhooker.patch;

/**
 * Constant hook identifiers dispatched through {@link dev.diona.pluginhooker.bridge.HookBridge}.
 */
public final class HookIds {

    // Guard hooks (return true to block/cancel)
    public static final int GUARD_BUKKIT_EVENT = 1;
    public static final int GUARD_PROTOCOLLIB_INBOUND = 2;
    public static final int GUARD_PROTOCOLLIB_OUTBOUND = 3;
    public static final int GUARD_PACKETEVENTS_RECEIVE = 4;
    public static final int GUARD_PACKETEVENTS_SEND = 5;

    // Return-value filter hooks
    public static final int FILTER_ONLINE_PLAYERS = 101;
    public static final int FILTER_WORLD_PLAYERS = 102;
    public static final int FILTER_GET_PLAYER = 103;

    // Notification hooks
    public static final int NOTIFY_NETTY_HANDLER_ADD = 201;
    public static final int NOTIFY_PACKETEVENTS_REGISTER = 202;
    public static final int NOTIFY_PACKETEVENTS_UNREGISTER = 203;

    private HookIds() {
    }
}
