package dev.diona.pluginhooker.utils;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;


public class BukkitUtils {

    private static final String BUKKIT_PACKAGE = Bukkit.getServer().getClass().getPackage().getName();

    private static Field playerConnectionField = null;
    private static Field networkManagerField = null;
    private static Field channelField = null;
    private static final MethodHandle getHandleMethod;
    private static final MethodHandle pipelineMethod;

    static final Field pluginsField;

    static {
        try {
            Method getHandle = Class.forName(BUKKIT_PACKAGE + ".entity.CraftPlayer")
                    .getMethod("getHandle");
            getHandleMethod = MethodHandles.lookup().unreflect(getHandle);

            for (Field field : getHandle.getReturnType().getDeclaredFields()) {
                if (field.getType().getSimpleName().endsWith("ServerGamePacketListenerImpl")
                        || field.getType().getSimpleName().endsWith("PlayerConnection")) {
                    playerConnectionField = field;
                    field.setAccessible(true);
                    break;
                }
            }
            for (Field field : playerConnectionField.getType().getDeclaredFields()) {
                if (field.getType().getSimpleName().equals("Connection")
                    || field.getType().getSimpleName().equals("NetworkManager")) {
                    networkManagerField = field;
                    field.setAccessible(true);
                    break;
                }
            }
            if (networkManagerField == null) {
                for (Field field : playerConnectionField.getType().getSuperclass().getDeclaredFields()) {
                    if (field.getType().getSimpleName().equals("Connection")
                            || field.getType().getSimpleName().equals("NetworkManager")) {
                        networkManagerField = field;
                        field.setAccessible(true);
                        break;
                    }
                }
            }
            for (Field field : networkManagerField.getType().getDeclaredFields()) {
                if (field.getType().getSimpleName().equals("Channel")) {
                    channelField = field;
                    field.setAccessible(true);
                    break;
                }
            }
            pipelineMethod = MethodHandles.lookup().unreflect(channelField.getType().getMethod("pipeline"));

            pluginsField = Bukkit.getPluginManager().getClass().getDeclaredField("plugins");
            pluginsField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static ChannelPipeline getPipelineByPlayer(Player player) {
        try {
            Object channel = getChannelByPlayer(player);
            return (ChannelPipeline) pipelineMethod.invoke(channel);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static Channel getChannelByPlayer(Player player) {
        try {
            Object entityPlayer = getHandleMethod.invoke(player);
            Object playerConnection = playerConnectionField.get(entityPlayer);
            Object networkManager = networkManagerField.get(playerConnection);
            return (Channel) channelField.get(networkManager);
        } catch (Throwable e) {
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
            ChannelPipeline pipeline = getPipelineByPlayer(player);
            for (String name : pipeline.names()) {
                if (pipeline.context(name) != ctx) continue;
                return player;
            }
        }
        return null;
    }
}
