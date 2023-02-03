package io.github.dionatestserver.pluginhooker.utils;

import io.netty.channel.ChannelPipeline;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.List;

public class HookerUtils {

    private static Field channelPipelineField;

    private static final Field pluginsField;

    static {
        try {
            // 兼容plib 4.x
            channelPipelineField = Class.forName("com.comphenix.protocol.injector.netty.PipelineProxy")
                    .getDeclaredField("pipeline");
            channelPipelineField.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            // 找不到class则当前plib版本为5.0 不需要做兼容处理
        }

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


    public static Player getPlayerByPipeline(Object pipeline) {
//        System.out.println("source: " + pipeline);
        for (Player player : Bukkit.getOnlinePlayers()) {
            //TODO 使用反射代替直接调用nms
            EntityPlayer handle = ((CraftPlayer) player).getHandle();
            ChannelPipeline playerPipeline = handle.playerConnection.networkManager.channel.pipeline();
//            System.out.println("player: " + playerPipeline);
            if (playerPipeline == pipeline) {
                return player;
            } else {
                if (pipeline.getClass().getSimpleName().equals("DefaultChannelPipeline")) {
                    try {
                        Object internalPipeline = channelPipelineField.get(playerPipeline);
                        if (internalPipeline == pipeline) return player;
                    } catch (IllegalAccessException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
