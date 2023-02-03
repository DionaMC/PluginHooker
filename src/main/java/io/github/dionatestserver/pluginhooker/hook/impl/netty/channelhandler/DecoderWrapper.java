package io.github.dionatestserver.pluginhooker.hook.impl.netty.channelhandler;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.events.NettyCodecEvent;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;

public class DecoderWrapper extends MessageToMessageDecoder<Object> {

    private final static Method decoderMethod;

    static {
        try {
            decoderMethod = MessageToMessageDecoder.class
                    .getDeclaredMethod("decode", ChannelHandlerContext.class, Object.class, List.class);
            decoderMethod.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private final MessageToMessageDecoder<?> decoder;
    private final Plugin plugin;

    private final DionaPlayer dionaPlayer;

    public DecoderWrapper(MessageToMessageDecoder<?> decoder, Plugin plugin, Player player) {
        this.decoder = decoder;
        this.plugin = plugin;
        this.dionaPlayer = DionaPluginHooker.getPlayerManager().getDionaPlayer(player);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            NettyCodecEvent nettyCodecEvent = new NettyCodecEvent(plugin, dionaPlayer, msg, false);
            Bukkit.getPluginManager().callEvent(nettyCodecEvent);
            if (nettyCodecEvent.isCancelled()) {
                out.add(msg);
            } else {
                decoderMethod.invoke(decoder, ctx, msg, out);
            }
        } else {
            out.add(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext channelHandlerContext, Throwable throwable) throws Exception {
        throwable.printStackTrace();
    }
}
