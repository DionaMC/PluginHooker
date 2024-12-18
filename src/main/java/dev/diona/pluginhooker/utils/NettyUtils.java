package dev.diona.pluginhooker.utils;

import io.netty.buffer.ByteBuf;
import io.netty.util.AttributeKey;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

public class NettyUtils {

    public static final AttributeKey<List<Consumer<Player>>> WRAPPER_FUNCTIONS
            = AttributeKey.valueOf("WRAPPER_FUNCTIONS");

    public static void processPacket(Object msg, List<Object> out) {
        if (msg instanceof ByteBuf) {
            ByteBuf byteBuf = (ByteBuf) msg;
            if (byteBuf.isReadable())
                out.add(byteBuf.retain());
        } else {
            out.add(msg);
        }
    }
}
