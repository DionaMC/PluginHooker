package io.github.dionatestserver.pluginhooker.hook.impl.netty;

import io.github.dionatestserver.pluginhooker.PluginHooker;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import io.github.dionatestserver.pluginhooker.utils.NettyVersion;
import io.netty.channel.DefaultChannelConfig;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;

import java.util.Arrays;
import java.util.function.BiConsumer;

public class NettyPipelineInjector extends Injector {

    @Getter
    private static final NettyCallbackHandler callbackHandler = new NettyCallbackHandler();

    private static final CtClass CALLBACK_CLASS;

    static {
        try {
            CALLBACK_CLASS = classPool.get(NettyPipelineCallback.class.getName());
            CALLBACK_CLASS.replaceClassName(
                    NettyPipelineCallback.class.getName(),
                    DefaultChannelConfig.class.getPackage().getName() + "." + NettyPipelineCallback.class.getSimpleName()
            );
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public NettyPipelineInjector() {
        super("io.netty.channel.DefaultChannelPipeline", "io.netty.channel.DefaultChannelConfig");

        try {
            Class<?> nettyPipelineHooker =
                    DefineClassHelper.toClass(
                            CALLBACK_CLASS.getName(),
                            DefaultChannelConfig.class,
                            DefaultChannelConfig.class.getClassLoader(),
                            null,
                            CALLBACK_CLASS.toBytecode()
                    );

            BiConsumer<Object, Object> callback = callbackHandler::handlePipelineAdd;
            nettyPipelineHooker.getConstructor(BiConsumer.class).newInstance(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void hookClass() {
        Arrays.stream(targetClass.getDeclaredMethods())
                .filter(method -> { // filter out methods that are not addLast0 or addFirst0
                    return method.getName().equals("addBefore0") || method.getName().equals("addAfter0");
                })
                .forEach(method -> {
                    try {
                        NettyVersion version = NettyVersion.getVersion();
                        if (version.getMinor() == 1) {
                            method.insertAfter(CALLBACK_CLASS.getName() + ".getInstance().onHandlerAdd($2);");
                        } else if (version.getMinor() == 0) {
                            method.insertBefore(CALLBACK_CLASS.getName() + ".getInstance().onHandlerAdd($3,$0);");
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Override
    public boolean canHook() {
        if (NettyVersion.getVersion().getMajor() != 4) {
            PluginHooker.getInstance().getLogger().warning("PluginHooker only supports netty 4.1/4.0");
            return false;
        }
        return DionaConfig.hookNetty;
    }

    @Override
    protected void initClassPath() {
        // empty
    }
}
