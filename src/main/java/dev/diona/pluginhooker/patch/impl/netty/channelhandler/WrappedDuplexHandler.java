package dev.diona.pluginhooker.patch.impl.netty.channelhandler;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.NettyCodecEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class WrappedDuplexHandler extends ChannelDuplexHandler {

    @ConfigPath("hook.netty.call-event")
    public static boolean callEvent;

    static {
        PluginHooker.getConfigManager().loadConfig(WrappedDuplexHandler.class);
    }

    private final ChannelDuplexHandler duplexHandler;
    private final Plugin plugin;
    private final DionaPlayer dionaPlayer;


    public WrappedDuplexHandler(ChannelDuplexHandler duplexHandler, Plugin plugin, Player player) {
        this.duplexHandler = duplexHandler;
        this.plugin = plugin;
        this.dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
    }

    @Override
    public void channelRead(ChannelHandlerContext channelHandlerContext, Object packet) throws Exception {
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            if (!callEvent) {
                duplexHandler.channelRead(channelHandlerContext, packet);
                return;
            }
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
            if (!callEvent) {
                duplexHandler.write(channelHandlerContext, packet, channelPromise);
                return;
            }
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
