package io.github.dionatestserver.pluginhooker.hook.impl.netty;

import io.github.dionatestserver.pluginhooker.PluginHooker;
import io.github.dionatestserver.pluginhooker.hook.impl.netty.channelhandler.DecoderWrapper;
import io.github.dionatestserver.pluginhooker.hook.impl.netty.channelhandler.DuplexHandlerWrapper;
import io.github.dionatestserver.pluginhooker.utils.HookerUtils;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.List;

public class NettyCallbackHandler {

    private final static Field handlerField;

    static {
        try {
            Class<?> channelHandlerContext = Class.forName("io.netty.channel.DefaultChannelHandlerContext");
            handlerField = channelHandlerContext.getDeclaredField("handler");
            handlerField.setAccessible(true);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //ctx = DefaultChannelHandlerContext
    public void handlePipelineAdd(Object ctx, Object pipeline) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stackTraceElements.length; i++) {
            if (stackTraceElements[i].getClassName().startsWith("io.netty"))
                continue;

            if (stackTraceElements[i].getClassName().startsWith("com.comphenix.protocol"))
                continue;

            try {
                Class<?> aClass = Class.forName(stackTraceElements[i].getClassName());

                if (aClass.getClassLoader() == null || aClass.getClassLoader() == PluginHooker.class.getClassLoader())
                    continue;

                if (!aClass.getClassLoader().getClass().getSimpleName().equals("PluginClassLoader"))
                    continue;

                // 修复Netty线程获取插件列表后线程死锁的bug
                List<Plugin> pluginList = HookerUtils.getServerPlugins();

                for (Plugin plugin : pluginList) {
                    if (plugin.getClass().getClassLoader() != aClass.getClassLoader()) continue;


                    if (!PluginHooker.getPluginManager().getPluginsToHook().contains(plugin))
                        break;

                    ChannelHandler handler = getContextHandler(ctx);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {


                        Player player = HookerUtils.getPlayerByChannelContext(ctx);
                        if (player == null)
                            return; // packetevents会在初始化时删除编解码器并重新添加导致找不到player对象
//                            throw new RuntimeException("Player not found "
//                                    + " | plugin: " + plugin.getName()
//                                    + " | handler: " + handler.getClass().getName() + "@" + handler.hashCode()
//                            );

                        if (handler instanceof MessageToMessageDecoder) {
                            setContextHandler(ctx, new DecoderWrapper((MessageToMessageDecoder<?>) handler, plugin, player));
                            System.out.println("plugin: " + plugin.getName() + " MessageToMessageDecoder");
                        } else if (handler instanceof ChannelDuplexHandler) {
                            setContextHandler(ctx, new DuplexHandlerWrapper((ChannelDuplexHandler) handler, plugin, player));
                            System.out.println("plugin: " + plugin.getName() + " ChannelDuplexHandler");
                        }
                    }, 10L);
                    break;

                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            break;
        }
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