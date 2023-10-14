package dev.diona.pluginhooker.hook.impl.netty.channelhandler;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.NettyCodecEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class InboundHandlerWrapper extends ChannelInboundHandlerAdapter {

    @ConfigPath("hook.netty.call-event")
    public static boolean callEvent;

    static {
        PluginHooker.getConfigManager().loadConfig(InboundHandlerWrapper.class);
    }

    private final ChannelInboundHandlerAdapter inbound;
    private final Plugin plugin;
    private final DionaPlayer dionaPlayer;

    public InboundHandlerWrapper(ChannelInboundHandlerAdapter inbound, Plugin plugin, Player player) {
        this.inbound = inbound;
        this.plugin = plugin;
        this.dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
    }

    @Override
    public void channelRead(ChannelHandlerContext channelHandlerContext, Object data) throws Exception {
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            if (!callEvent) {
                inbound.channelRead(channelHandlerContext, data);
                return;
            }
            NettyCodecEvent nettyCodecEvent = new NettyCodecEvent(plugin, dionaPlayer, data, false);
            Bukkit.getPluginManager().callEvent(nettyCodecEvent);
            if (nettyCodecEvent.isCancelled()) {
                super.channelRead(channelHandlerContext, data);
            } else {
                inbound.channelRead(channelHandlerContext, data);
            }
        } else {
            super.channelRead(channelHandlerContext, data);
        }
    }
}
