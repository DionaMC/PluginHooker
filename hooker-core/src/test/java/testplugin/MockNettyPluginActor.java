package testplugin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

public final class MockNettyPluginActor extends ChannelDuplexHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        throw new AssertionError("Disabled plugin channelRead should have been bypassed");
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        throw new AssertionError("Disabled plugin write should have been bypassed");
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        forwardCloseViaHelper(ctx, promise);
    }

    private void forwardCloseViaHelper(ChannelHandlerContext ctx, ChannelPromise promise) {
        ctx.close(promise);
    }

    public static ChannelFuture writeTwoArgVoidPromise(Channel ch, ByteBuf buf) {
        return ch.write(buf, ch.voidPromise());
    }

    public static ChannelFuture writeAndFlushTwoArgPromise(Channel ch, ByteBuf buf, ChannelPromise promise) {
        return ch.writeAndFlush(buf, promise);
    }

    public static ChannelFuture directClose(Channel ch) {
        return ch.close();
    }

    public static <T> T callViaAdventureWrapper(java.util.function.Supplier<T> apiCall) {
        return net.kyori.adventure.audience.SimulatedServerAudience.forwardFromAdventureDefault(apiCall);
    }

    public static void directNmsSend(net.minecraft.server.test.PlayerConnection conn, Object packet) {
        conn.sendPacket(packet);
    }

    public static void reflectiveNmsSend(net.minecraft.server.test.PlayerConnection conn, Object packet) throws Exception {
        net.minecraft.server.test.PlayerConnection.class.getMethod("sendPacket", Object.class).invoke(conn, packet);
    }

    public static void directNmsDisconnect(net.minecraft.server.test.PlayerConnection conn, String reason) {
        conn.disconnect(reason);
    }
}
