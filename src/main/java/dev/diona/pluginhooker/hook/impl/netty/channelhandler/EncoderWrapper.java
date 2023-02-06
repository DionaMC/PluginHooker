package dev.diona.pluginhooker.hook.impl.netty.channelhandler;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.events.NettyCodecEvent;
import dev.diona.pluginhooker.player.DionaPlayer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;

public class EncoderWrapper extends MessageToMessageEncoder<Object> {

    private final static MethodHandle encoderMethodHandle;

    @ConfigPath("hook.netty.call-event")
    public static boolean callEvent;

    static {
        PluginHooker.getConfigManager().loadConfig(EncoderWrapper.class);
        try {
            Method encoderMethod = MessageToMessageEncoder.class
                    .getDeclaredMethod("write", ChannelHandlerContext.class, Object.class, ChannelPromise.class);
            encoderMethod.setAccessible(true);
            encoderMethodHandle = MethodHandles.lookup().unreflect(encoderMethod);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final MessageToMessageEncoder<?> encoder;
    private final Plugin plugin;

    private final DionaPlayer dionaPlayer;

    public EncoderWrapper(MessageToMessageEncoder<?> encoder, Plugin plugin, Player player) {
        this.encoder = encoder;
        this.plugin = plugin;
        this.dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
    }


    @Override
    public void write(ChannelHandlerContext ctx, Object data, ChannelPromise promise) throws Exception {
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            if (!callEvent) {
                try {
                    encoderMethodHandle.invoke(encoder, ctx, data, promise);
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
                return;
            }
            NettyCodecEvent nettyCodecEvent = new NettyCodecEvent(plugin, dionaPlayer, data, true);
            Bukkit.getPluginManager().callEvent(nettyCodecEvent);
            if (nettyCodecEvent.isCancelled()) {
                super.write(ctx, data, promise);
            } else {
                try {
                    encoderMethodHandle.invoke(encoder, ctx, data, promise);
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
            }
        } else {
            super.write(ctx, data, promise);
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
        addToOutList(msg, out);
    }

    private void addToOutList(Object msg, List<Object> out) {
        if (msg instanceof ByteBuf) {
            ByteBuf byteBuf = (ByteBuf) msg;
            if (byteBuf.isReadable())
                out.add(byteBuf.retain());
        } else {
            out.add(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext channelHandlerContext, Throwable throwable) {
        throwable.printStackTrace();
    }
}
