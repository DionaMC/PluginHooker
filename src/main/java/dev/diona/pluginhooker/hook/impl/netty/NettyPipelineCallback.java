package dev.diona.pluginhooker.hook.impl.netty;

import lombok.Getter;

import java.util.function.Consumer;

public class NettyPipelineCallback {

    @Getter
    private static NettyPipelineCallback instance;

    private final Consumer<Object> callback;

    public NettyPipelineCallback(Consumer<Object> callback) {
        instance = this;
        this.callback = callback;
    }

    public void onHandlerAdd(Object ctx) {
        callback.accept(ctx);
    }
}
