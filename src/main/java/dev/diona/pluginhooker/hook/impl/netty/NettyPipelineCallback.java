package dev.diona.pluginhooker.hook.impl.netty;

import lombok.Getter;

import java.util.function.BiConsumer;

public class NettyPipelineCallback {

    @Getter
    private static NettyPipelineCallback instance;

    private final BiConsumer<Object, Object> callback;

    public NettyPipelineCallback(BiConsumer<Object, Object> callback) {
        instance = this;
        this.callback = callback;
    }

    public void onHandlerAdd(Object ctx, Object pipeline) {
        callback.accept(ctx, pipeline);
    }

    public void onHandlerAdd(Object ctx) {
        this.onHandlerAdd(ctx, null);
//        for (Player player : Bukkit.getOnlinePlayers()) {
//            //TODO 使用反射代替直接调用nms
//            ChannelPipeline pipeline = ((CraftPlayer) player).getHandle().playerConnection.networkManager.channel.pipeline();
//            for (String name : pipeline.names()) {
//                if (pipeline.context(name) != ctx) continue;
//
//                this.onHandlerAdd(ctx, pipeline);
//            }
//        }
    }
}
