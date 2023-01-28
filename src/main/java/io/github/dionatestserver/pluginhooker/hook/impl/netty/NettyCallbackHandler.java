package io.github.dionatestserver.pluginhooker.hook.impl.netty;

import io.github.dionatestserver.pluginhooker.hook.impl.netty.channelhandler.DecoderWrapper;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;

public class NettyCallbackHandler {

    private final static Class<?> channelHandlerContext;
    private final static Field handlerField;

    static {
        try {
            channelHandlerContext = Class.forName("io.netty.channel.DefaultChannelHandlerContext");
            handlerField = channelHandlerContext.getDeclaredField("handler");
            handlerField.setAccessible(true);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //ctx = DefaultChannelHandlerContext
    public static Object handlePipelineAdd(Object ctx, Object pipeline) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stackTraceElements.length; i++) {
            if (stackTraceElements[i].getClassName().startsWith("io.netty")) continue;

            if (!stackTraceElements[i].getClassName().equals("com.comphenix.protocol.injector.netty.PipelineProxy"))
                continue;

            try {
                Class<?> aClass = Class.forName(stackTraceElements[i + 1].getClassName());

                for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    if (plugin.getClass().getClassLoader() != aClass.getClassLoader()) continue;

//                    if (!DionaPluginHooker.getPluginManager().getPluginsToHook().contains(plugin))
//                        return ctx;

                    ChannelHandler handler = getContextHandler(ctx);

                    if (handler instanceof MessageToMessageDecoder) {
                        setContextHandler(ctx, new DecoderWrapper((MessageToMessageDecoder<?>) handler, plugin, pipeline));
                        System.out.println("plugin: " + plugin.getName());
                    }
                    break;

                }

            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }

            break;
        }
        return ctx;
    }


    private static ChannelHandler getContextHandler(Object ctx) {
        try {
            return (ChannelHandler) handlerField.get(ctx);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setContextHandler(Object ctx, ChannelHandler handler) {
        try {
            handlerField.set(ctx, handler);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

}
