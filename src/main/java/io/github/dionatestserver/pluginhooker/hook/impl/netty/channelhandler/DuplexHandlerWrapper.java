package io.github.dionatestserver.pluginhooker.hook.impl.netty.channelhandler;

import io.github.dionatestserver.pluginhooker.PluginHooker;
import io.github.dionatestserver.pluginhooker.events.NettyCodecEvent;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class DuplexHandlerWrapper extends ChannelDuplexHandler {

    private final ChannelDuplexHandler duplexHandler;
    private final Plugin plugin;
    private final DionaPlayer dionaPlayer;

    public DuplexHandlerWrapper(ChannelDuplexHandler duplexHandler, Plugin plugin, Player player) {
        this.duplexHandler = duplexHandler;
        this.plugin = plugin;
        this.dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
    }

    @Override
    public void channelRead(ChannelHandlerContext channelHandlerContext, Object packet) throws Exception {
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            NettyCodecEvent nettyCodecEvent = new NettyCodecEvent(plugin, dionaPlayer, packet, false);
            Bukkit.getPluginManager().callEvent(nettyCodecEvent);
            if (nettyCodecEvent.isCancelled()) {
                super.channelRead(channelHandlerContext, packet);
            } else {
                duplexHandler.channelRead(channelHandlerContext, packet);
            }
        } else {
            super.channelRead(channelHandlerContext, packet);
        }
    }

    @Override
    public void write(ChannelHandlerContext channelHandlerContext, Object packet, ChannelPromise channelPromise) throws Exception {
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            NettyCodecEvent nettyCodecEvent = new NettyCodecEvent(plugin, dionaPlayer, packet, true);
            Bukkit.getPluginManager().callEvent(nettyCodecEvent);
            if (nettyCodecEvent.isCancelled()) {
                super.write(channelHandlerContext, packet, channelPromise);
            } else {
                duplexHandler.write(channelHandlerContext, packet, channelPromise);
            }
        } else {
            super.write(channelHandlerContext, packet, channelPromise);
        }
    }
}
