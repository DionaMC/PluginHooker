package dev.diona.pluginhooker.utils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

public class HookerUtils {

    public static final AttributeKey<List<Consumer<Player>>> HANDLER_REPLACEMENT_FUNCTIONS
            = AttributeKey.valueOf("HANDLER_REPLACEMENT_FUNCTION");

    private static final Field pluginsField;

    static {

        try {
            pluginsField = Bukkit.getPluginManager().getClass().getDeclaredField("plugins");
            pluginsField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Plugin> getServerPlugins() {
        try {
            return (List<Plugin>) pluginsField.get(Bukkit.getPluginManager());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Player getPlayerByChannelContext(Object ctx) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ChannelPipeline pipeline = NMSUtils.getPipelineByPlayer(player);
            for (String name : pipeline.names()) {
                if (pipeline.context(name) != ctx) continue;
                return player;
            }
        }
        return null;
    }

    public static void addToOutList(Object msg, List<Object> out) {
        if (msg instanceof ByteBuf) {
            ByteBuf byteBuf = (ByteBuf) msg;
            if (byteBuf.isReadable())
                out.add(byteBuf.retain());
        } else {
            out.add(msg);
        }
    }
}
