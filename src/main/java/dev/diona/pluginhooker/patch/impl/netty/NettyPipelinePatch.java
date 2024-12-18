package dev.diona.pluginhooker.patch.impl.netty;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.patch.Patch;
import dev.diona.pluginhooker.utils.NettyVersion;
import io.netty.channel.DefaultChannelConfig;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;

import java.util.Arrays;
import java.util.function.Consumer;

public class NettyPipelinePatch extends Patch {

    @ConfigPath("hook.netty.enabled")
    public boolean hookNetty;

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

    public NettyPipelinePatch() {
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

            Consumer<Object> callback = callbackHandler::handlePipelineAdd;
            nettyPipelineHooker.getConstructor(Consumer.class).newInstance(callback);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void applyPatch() {
        Arrays.stream(targetClass.getDeclaredMethods())
                .filter(method -> { // filter out methods that are not addLast0 or addFirst0
                    return method.getName().equals("addBefore0") || method.getName().equals("addAfter0");
                })
                .forEach(method -> {
                    try {
                        String src = String.format(
                                CALLBACK_CLASS.getName() + ".getInstance().onHandlerAdd($%d);",
                                method.getParameterTypes().length
                        );
                        method.insertAfter(src);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Override
    public boolean canPatch() {
        String version = NettyVersion.getVersion().getMajor() + "." + NettyVersion.getVersion().getMinor();
        if (!version.equals("4.1") && !version.equals("4.0")) {
            PluginHooker.getInstance().getLogger().warning("PluginHooker only supports netty 4.1/4.0");
            return false;
        }
        return hookNetty;
    }

    @Override
    protected void initClassPath() {
        // empty
    }
}
