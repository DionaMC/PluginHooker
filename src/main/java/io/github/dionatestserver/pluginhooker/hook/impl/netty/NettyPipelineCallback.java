package io.github.dionatestserver.pluginhooker.hook.impl.netty;

import io.netty.channel.ChannelPipeline;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.function.BiConsumer;

public class NettyPipelineCallback {

    @Getter
    private static NettyPipelineCallback instance;

    private final BiConsumer<Object, Object> callback;

    private static final Field pluginsField;

    static {
        try {
            pluginsField = Bukkit.getPluginManager().getClass().getDeclaredField("plugins");
            pluginsField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public NettyPipelineCallback(BiConsumer<Object, Object> callback) {
        instance = this;
        this.callback = callback;
    }

    public void onHandlerAdd(Object ctx, Object pipeline) {
        callback.accept(ctx, pipeline);
    }

    public void onHandlerAdd(Object ctx) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            //TODO 使用反射代替直接调用nms
            ChannelPipeline pipeline = ((CraftPlayer) player).getHandle().playerConnection.networkManager.channel.pipeline();
            for (String name : pipeline.names()) {
                if (pipeline.context(name) != ctx) continue;

                this.onHandlerAdd(ctx, pipeline);
            }
        }
    }
}
