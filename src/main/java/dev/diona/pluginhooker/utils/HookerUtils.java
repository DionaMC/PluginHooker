package dev.diona.pluginhooker.utils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.List;

public class HookerUtils {

//    private static Field channelPipelineField;

    private static final Field pluginsField;

    static {
//        try {
//            // 兼容plib 4.x
//            channelPipelineField = Class.forName("com.comphenix.protocol.injector.netty.PipelineProxy")
//                    .getDeclaredField("pipeline");
//            channelPipelineField.setAccessible(true);
//        } catch (ClassNotFoundException | NoSuchFieldException e) {
//            // 找不到class则当前plib版本为5.0 不需要做兼容处理
//        }

        try {
            pluginsField = Bukkit.getPluginManager().getClass().getDeclaredField("plugins");
            pluginsField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Plugin> getServerPlugins() {
        try {
            return (List<Plugin>) pluginsField.get(Bukkit.getPluginManager());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Player getPlayerByChannelContext(Object ctx) {
        for (Player player : Bukkit.getOnlinePlayers()) {
//            ChannelPipeline pipeline = ((CraftPlayer) player).getHandle().playerConnection.networkManager.channel.pipeline();
//            ChannelPipeline pipeline = ((CraftPlayer) player).getHandle().b.a.k.pipeline();
            ChannelPipeline pipeline = NMSUtils.getPipelineByPlayer(player);
            for (String name : pipeline.names()) {
                if (pipeline.context(name) != ctx) continue;
                return player;
            }
        }
        return null;
    }

    public static void addToOutList(Object msg, List<Object> out) {
        if (msg instanceof ByteBuf) {
            ByteBuf byteBuf = (ByteBuf) msg;
            if (byteBuf.isReadable())
                out.add(byteBuf.retain());
        } else {
            out.add(msg);
        }
    }


//    public static Player getPlayerByPipeline(Object pipeline) {
//        for (Player player : Bukkit.getOnlinePlayers()) {
//            //TODO 使用反射代替直接调用nms
//            EntityPlayer handle = ((CraftPlayer) player).getHandle();
//            ChannelPipeline playerPipeline = handle.playerConnection.networkManager.channel.pipeline();
//            if (playerPipeline == pipeline) {
//                return player;
//            } else {
//                if (pipeline.getClass().getSimpleName().equals("DefaultChannelPipeline")) {
//                    try {
//                        Object internalPipeline = channelPipelineField.get(playerPipeline);
//                        if (internalPipeline == pipeline) return player;
//                    } catch (IllegalAccessException e) {
//                        return null;
//                    }
//                }
//            }
//        }
//        return null;
//    }
}
