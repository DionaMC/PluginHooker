package dev.diona.pluginhooker.hook.impl.netty;

import com.google.common.collect.Lists;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.hook.impl.netty.channelhandler.*;
import dev.diona.pluginhooker.player.DionaPlayer;
import dev.diona.pluginhooker.utils.HookerUtils;
import io.netty.channel.*;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

public class NettyCallbackHandler {

    private final static Field handlerField;
    private final static Method channelMethod;

    static {
        try {
            Class<?> channelHandlerContext = Class.forName("io.netty.channel.DefaultChannelHandlerContext");
            handlerField = channelHandlerContext.getDeclaredField("handler");
            handlerField.setAccessible(true);

            Class<?> abstractChannelHandlerContext = channelHandlerContext.getSuperclass();
            channelMethod = abstractChannelHandlerContext.getMethod("channel");
            channelMethod.setAccessible(true);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void handlePipelineAdd(Object channelHandlerContext) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stackTraceElements.length; i++) {
            if (stackTraceElements[i].getClassName().startsWith("io.netty"))
                continue;

            if (stackTraceElements[i].getClassName().startsWith("com.comphenix.protocol"))
                continue;

            try {
                Class<?> aClass = Class.forName(stackTraceElements[i].getClassName());

                // WTF
                if (aClass.getClassLoader() == null || aClass.getClassLoader() == PluginHooker.class.getClassLoader())
                    continue;

                // check if the class is loaded by the plugin classloader
                if (!aClass.getClassLoader().getClass().getSimpleName().equals("PluginClassLoader"))
                    continue;

                List<Plugin> pluginList = HookerUtils.getServerPlugins();

                for (Plugin plugin : pluginList) {
                    // check if the plugin is loaded by the same classloader
                    if (plugin.getClass().getClassLoader() != aClass.getClassLoader())
                        continue;

                    if (!PluginHooker.getPluginManager().getPluginsToHook().contains(plugin))
                        break;

                    ChannelHandler handler = getContextHandler(channelHandlerContext);

                    // replace the ChannelHandlerContext with our wrapper
                    Consumer<Player> consumer = player -> {
                        if (handler instanceof MessageToMessageDecoder) {
                            setContextHandler(channelHandlerContext, new DecoderWrapper((MessageToMessageDecoder<?>) handler, plugin, player));
                            // System.out.println("plugin: " + plugin.getName() + " MessageToMessageDecoder");
                        } else if (handler instanceof MessageToMessageEncoder) {
                            setContextHandler(channelHandlerContext, new EncoderWrapper((MessageToMessageEncoder<?>) handler, plugin, player));
                            // System.out.println("plugin: " + plugin.getName() + " MessageToMessageEncoder");
                        } else if (handler instanceof ChannelDuplexHandler) {
                            setContextHandler(channelHandlerContext, new DuplexHandlerWrapper((ChannelDuplexHandler) handler, plugin, player));
                            // System.out.println("plugin: " + plugin.getName() + " ChannelDuplexHandler");
                        } else if (handler instanceof ChannelInboundHandlerAdapter) {
                            setContextHandler(channelHandlerContext, new InboundHandlerWrapper((ChannelInboundHandlerAdapter) handler, plugin, player));
                        } else if (handler instanceof ChannelOutboundHandlerAdapter) {
                            setContextHandler(channelHandlerContext, new OutboundHandlerWrapper((ChannelOutboundHandlerAdapter) handler, plugin, player));
                        }
                    };

                    Player player = HookerUtils.getPlayerByChannelContext(channelHandlerContext);
                    // if player is null then the player is not joined yet
                    if (player != null) {
                        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
                        if (dionaPlayer != null && dionaPlayer.isInitialized()) {
                            consumer.accept(player);
                            return;
                        }
                    }
                    Channel channel = getChannel(channelHandlerContext);
                    // add consumer to list, wait for player join event to replace the handler
                    this.appendConsumer(consumer, channel);
                    return;
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            break;
        }
    }

    /**
     * add consumer to channel attr
     *
     * @param consumer consumer
     * @param channel  channel
     */
    private void appendConsumer(Consumer<Player> consumer, Channel channel) {
        List<Consumer<Player>> list = channel.attr(HookerUtils.HANDLER_REPLACEMENT_FUNCTIONS).get();
        if (list == null) {
            list = Lists.newArrayList();
        }
        list.add(consumer);
        channel.attr(HookerUtils.HANDLER_REPLACEMENT_FUNCTIONS).set(list);
    }

    /**
     * get channel from ChannelHandlerContext
     *
     * @param channelHandlerContext ChannelHandlerContext
     * @return channel
     */
    public static AbstractChannel getChannel(Object channelHandlerContext) {
        try {
            return (AbstractChannel) channelMethod.invoke(channelHandlerContext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ChannelHandler getContextHandler(Object channelHandlerContext) {
        try {
            return (ChannelHandler) handlerField.get(channelHandlerContext);
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
