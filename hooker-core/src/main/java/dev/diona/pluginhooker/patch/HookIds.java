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
    public static final int GUARD_PLAYER_KICK = 6;
    public static final int GUARD_DISPATCH_COMMAND = 7;
    public static final int GUARD_PLAYER_TELEPORT = 8;
    public static final int GUARD_ENTITY_VELOCITY = 9;
    public static final int GUARD_PLAYER_SEND_MESSAGE = 10;
    public static final int GUARD_NMS_SEND_PACKET = 11;
    public static final int GUARD_PROTOCOLLIB_SEND_PACKET = 12;
    public static final int GUARD_PACKETEVENTS_SEND_PACKET = 13;
    public static final int GUARD_NETTY_WRITE_BYTEBUF = 14;
    public static final int GUARD_PROTOCOLLIB_ASYNC = 15;
    public static final int GUARD_NETTY_CLOSE = 16;
    public static final int GUARD_NETTY_WRITE_BYTEBUF_TAIL = 17;
    public static final int GUARD_NMS_DISCONNECT = 18;
    public static final int GUARD_PLAYER_CLOSE_INVENTORY = 19;

    // Return-value filter hooks
    public static final int FILTER_ONLINE_PLAYERS = 101;
    public static final int FILTER_WORLD_PLAYERS = 102;
    public static final int FILTER_GET_PLAYER = 103;
    public static final int FILTER_ENTITY_COLLECTION = 104;

    // Notification hooks
    public static final int NOTIFY_NETTY_HANDLER_ADD = 201;
    public static final int NOTIFY_PACKETEVENTS_REGISTER = 202;
    public static final int NOTIFY_PACKETEVENTS_UNREGISTER = 203;
    public static final int NOTIFY_NMS_PLAYER_CONNECTION_INIT = 204;

    private HookIds() {
    }
}
