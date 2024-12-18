package dev.diona.pluginhooker.patch.impl.netty.channelhandler;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.NettyCodecEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import dev.diona.pluginhooker.utils.NettyUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;

public class WrappedDecoder extends MessageToMessageDecoder<Object> {

    private final static MethodHandle decoderMethodHandle;

    @ConfigPath("hook.netty.call-event")
    public static boolean callEvent;

    static {
        PluginHooker.getConfigManager().loadConfig(WrappedDecoder.class);
        try {
            Method decoderMethod = MessageToMessageDecoder.class
                    .getDeclaredMethod("decode", ChannelHandlerContext.class, Object.class, List.class);
            decoderMethod.setAccessible(true);
            decoderMethodHandle = MethodHandles.lookup().unreflect(decoderMethod);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final MessageToMessageDecoder<?> decoder;
    private final Plugin plugin;

    private final DionaPlayer dionaPlayer;

    public WrappedDecoder(MessageToMessageDecoder<?> decoder, Plugin plugin, Player player) {
        this.decoder = decoder;
        this.plugin = plugin;
        this.dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            if (!callEvent) {
                invokeDecodeMethod(ctx, msg, out);
                return;
            }
            NettyCodecEvent nettyCodecEvent = new NettyCodecEvent(plugin, dionaPlayer, msg, false);
            Bukkit.getPluginManager().callEvent(nettyCodecEvent);
            if (nettyCodecEvent.isCancelled()) {
                NettyUtils.processPacket(msg, out);
            } else {
                invokeDecodeMethod(ctx, msg, out);
            }
        } else {
            NettyUtils.processPacket(msg, out);
        }
    }

    private void invokeDecodeMethod(ChannelHandlerContext ctx, Object msg, List<Object> out) {
        try {
            decoderMethodHandle.invoke(decoder, ctx, msg, out);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext channelHandlerContext, Throwable throwable) {
        throwable.printStackTrace();
    }
}
