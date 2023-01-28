package io.github.dionatestserver.pluginhooker.hook.impl.netty.channelhandler;

import com.comphenix.protocol.injector.netty.PipelineProxy;
import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.events.NettyCodecEvent;
import io.github.dionatestserver.pluginhooker.player.DionaPlayer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToMessageDecoder;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class DecoderWrapper extends MessageToMessageDecoder<Object> {

    private final static Method decoderMethod;
    private final static Field pipelineProxyField;

    static {
        try {
            decoderMethod = MessageToMessageDecoder.class
                    .getDeclaredMethod("decode", ChannelHandlerContext.class, Object.class, List.class);
            decoderMethod.setAccessible(true);

            pipelineProxyField = PipelineProxy.class.getDeclaredField("pipeline");
            pipelineProxyField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private final MessageToMessageDecoder<?> decoder;
    private final Plugin plugin;

    private Player player;

    public DecoderWrapper(MessageToMessageDecoder<?> decoder, Plugin plugin, Object pipeline) {
        this.decoder = decoder;
        this.plugin = plugin;

        boolean flag = Bukkit.getOnlinePlayers().stream().anyMatch(player -> {
            EntityPlayer handle = ((CraftPlayer) player).getHandle();

            ChannelPipeline playerPipeline = handle.playerConnection.networkManager.channel.pipeline();

            if (playerPipeline instanceof PipelineProxy) {
                PipelineProxy pipelineProxy = (PipelineProxy) playerPipeline;
                try {
                    Object plibPipeline = pipelineProxyField.get(pipelineProxy);
                    if (plibPipeline == pipeline) {
                        this.player = player;
                        return true;
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            } else {
                if (playerPipeline == pipeline) {
                    this.player = player;
                    return true;
                }
            }
            return false;
        });

        if (!flag) throw new RuntimeException("Player not found | plugin: " + plugin.getName());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        DionaPlayer dionaPlayer = DionaPluginHooker.getPlayerManager().getDionaPlayer(player);
        if (dionaPlayer.getEnabledPlugins().contains(plugin)) {
            NettyCodecEvent nettyCodecEvent = new NettyCodecEvent(plugin, dionaPlayer, msg, false);
            Bukkit.getPluginManager().callEvent(nettyCodecEvent);
            if (nettyCodecEvent.isCancelled()) {
                out.add(msg);
                System.out.println("cancelled");
            } else {
                decoderMethod.invoke(decoder, ctx, msg, out);
                System.out.println("enabled");
            }
        } else {
            out.add(msg);
            System.out.println("disabled");
        }
//        out.add(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext channelHandlerContext, Throwable throwable) throws Exception {
        throwable.printStackTrace();
    }
}
