package io.github.dionatestserver.pluginhooker.hook.impl.netty;

import lombok.Getter;

import java.util.function.BiFunction;

public class NettyPipelineHooker {

    @Getter
    private static NettyPipelineHooker instance;

    private final BiFunction<Object, Object, Object> callback;

    public NettyPipelineHooker(BiFunction<Object, Object, Object> callback) {
        instance = this;
        this.callback = callback;
    }

    public Object onHandlerAdd(Object ctx, Object pipeline) {
        return callback.apply(ctx, pipeline);
    }
}
