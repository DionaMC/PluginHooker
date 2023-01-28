package io.github.dionatestserver.pluginhooker.config;

public class DionaConfig {

    //hook
    @ConfigPath("hook.bukkit-event")
    public static boolean hookBukkitEvent;
    @ConfigPath("hook.protocollib-packet")
    public static boolean hookProtocolLibPacket;
    @ConfigPath("hook.netty")
    public static boolean hookNetty;

    @ConfigPath("use-reflection-to-get-event-player")
    public static boolean useReflectionToGetEventPlayer;
}
