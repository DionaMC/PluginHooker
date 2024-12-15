package dev.diona.pluginhooker.patch.impl.netty.channelhandler;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.NettyCodecEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class WrappedOutboundHandler extends ChannelOutboundHandlerAdapter {

    @ConfigPath("hook.netty.call-event")
    public static boolean callEvent;

    static {
        PluginHooker.getConfigManager().loadConfig(WrappedOutboundHandler.class);
    }

    private final ChannelOutboundHandlerAdapter outbound;
    private final Plugin plugin;
    private final DionaPlayer dionaPlayer;

    public WrappedOutboundHandler(ChannelOutboundHandlerAdapter outbound, Plugin plugin, Player player) {
        this.outbound = outbound;
        this.plugin = plugin;
        this.dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
    }

    @Override
    public void write(ChannelHandlerContext channelHandlerContext, Object data, ChannelPromise channelPromise) throws Exception {
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            if (!callEvent) {
                outbound.write(channelHandlerContext, data, channelPromise);
                return;
            }
            NettyCodecEvent nettyCodecEvent = new NettyCodecEvent(plugin, dionaPlayer, data, true);
            Bukkit.getPluginManager().callEvent(nettyCodecEvent);
            if (nettyCodecEvent.isCancelled()) {
                super.write(channelHandlerContext, data, channelPromise);
            } else {
                outbound.write(channelHandlerContext, data, channelPromise);
            }
        } else {
            super.write(channelHandlerContext, data, channelPromise);
        }
    }

}
