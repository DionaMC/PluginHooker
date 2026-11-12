package testpe;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

/**
 * Simulates standalone PacketEvents 2.14.0's {@code ChannelOperatorModernImpl} / {@code ChannelHelper}
 * loaded by the shared {@code packetevents} plugin ClassLoader.
 */
public final class ChannelOperatorModernImpl {

    private ChannelOperatorModernImpl() {
    }

    public static Object writeAndFlush(Channel channel, Object msg) {
        return channel.writeAndFlush(msg);
    }

    public static Object writeAndFlushInContext(Channel channel, String handlerName, Object msg) {
        ChannelHandlerContext ctx = channel.pipeline().context(handlerName);
        return ctx.writeAndFlush(msg);
    }

    public static Object close(Channel channel) {
        return channel.close();
    }
}
