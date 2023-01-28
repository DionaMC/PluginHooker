package io.github.dionatestserver.pluginhooker.hook.impl.netty;

import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import io.netty.channel.DefaultChannelConfig;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.util.proxy.DefineClassHelper;

import java.util.Arrays;
import java.util.function.BiFunction;

public class NettyPipelineInjector extends Injector {

    private static final CtClass HOOKER_CLASS;

    static {
        try {
            HOOKER_CLASS = classPool.get(NettyPipelineHooker.class.getName());
            HOOKER_CLASS.replaceClassName(
                    NettyPipelineHooker.class.getName(),
                    DefaultChannelConfig.class.getPackage().getName() + "." + NettyPipelineHooker.class.getSimpleName()
            );
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public NettyPipelineInjector() {
        super("io.netty.channel.DefaultChannelPipeline", DefaultChannelConfig.class);

        try {
            Class<?> nettyPipelineHooker =
                    DefineClassHelper.toClass(
                            HOOKER_CLASS.getName(),
                            DefaultChannelConfig.class,
                            DefaultChannelConfig.class.getClassLoader(),
                            null,
                            HOOKER_CLASS.toBytecode()
                    );

            BiFunction<Object, Object, Object> callback = NettyCallbackHandler::handlePipelineAdd;
            nettyPipelineHooker.getConstructor(BiFunction.class).newInstance(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void hookClass() {
        Arrays.stream(targetClass.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("add") && method.getName().endsWith("0")) //addFirst0, addLast0, addBefore0, addAfter0
                .filter(method -> { // filter out methods that are not addLast0 or addFirst0
                    try {
                        return method.getParameterTypes().length == 3;
                    } catch (NotFoundException e) {
                        throw new RuntimeException(e);
                    }
                })
                .forEach(method -> {
                    try {
                        System.out.println(method.getName());
                        method.insertBefore(HOOKER_CLASS.getName() + ".getInstance().onHandlerAdd($3,$0);");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Override
    public boolean canHook() {
        return DionaConfig.hookNetty;
    }

    @Override
    protected void initClassPath() {
        // empty
    }
}
