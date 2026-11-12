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

    public static void directRawConnectionSend(net.minecraft.network.Connection conn, Object packet) {
        conn.send(packet);
    }

    public static void directRawConnectionSendThreeArg(net.minecraft.network.Connection conn, Object packet) {
        conn.send(packet, null, false);
    }

    public static void reflectiveRawConnectionSend(net.minecraft.network.Connection conn, Object packet) throws Exception {
        net.minecraft.network.Connection.class.getMethod("send", Object.class).invoke(conn, packet);
    }

    public static void directRawConnectionDisconnect(net.minecraft.network.Connection conn, Object reason) {
        conn.disconnect(reason);
    }

    public static ChannelFuture directWriteNmsPacket(Channel ch, Object nmsPacket) {
        return ch.writeAndFlush(nmsPacket);
    }

    public static Object writeViaSharedPeOperator(Class<?> peOperatorClass, Channel ch, Object msg) throws Exception {
        return peOperatorClass.getMethod("writeAndFlush", Channel.class, Object.class).invoke(null, ch, msg);
    }

    public static Object writeInContextViaSharedPeOperator(
            Class<?> peOperatorClass,
            Channel ch,
            String handlerName,
            Object msg
    ) throws Exception {
        return peOperatorClass.getMethod("writeAndFlushInContext", Channel.class, String.class, Object.class)
                .invoke(null, ch, handlerName, msg);
    }

    public static Object closeViaSharedPeOperator(Class<?> peOperatorClass, Channel ch) throws Exception {
        return peOperatorClass.getMethod("close", Channel.class).invoke(null, ch);
    }

    public static void triggerServerActionOnStack(Runnable serverAction) {
        serverAction.run();
    }
}
