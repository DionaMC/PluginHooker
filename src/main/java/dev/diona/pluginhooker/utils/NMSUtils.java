package dev.diona.pluginhooker.utils;

import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;


public class NMSUtils {

    private static final String BUKKIT_PACKAGE = Bukkit.getServer().getClass().getPackage().getName();

    private static final Field playerConnectionField;
    private static final Field networkManagerField;
    private static final Field channelField;
    private static final MethodHandle getHandleMethod;
    private static final MethodHandle pipelineMethod;

    static {
        try {
            int majorVersion = getNMSMajorVersion();
            Method getHandle = Class.forName(BUKKIT_PACKAGE + ".entity.CraftPlayer")
                    .getMethod("getHandle");
            getHandleMethod = MethodHandles.lookup().unreflect(getHandle);

            playerConnectionField = getHandle.getReturnType()
                    .getField(majorVersion > 16 ? "b" : "playerConnection");
            networkManagerField = playerConnectionField.getType()
                    .getField(majorVersion > 16 ? "a" : "networkManager");
            channelField = networkManagerField.getType()
                    .getField(majorVersion > 16 ? "k" : "channel");
            pipelineMethod = MethodHandles.lookup().unreflect(channelField.getType().getMethod("pipeline"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public static String getNMSVersion() {
        return BUKKIT_PACKAGE.substring(BUKKIT_PACKAGE.lastIndexOf('.') + 1);
    }

    public static int getNMSMajorVersion() {
        String nmsVersion = getNMSVersion();
        return Integer.parseInt(nmsVersion.substring(nmsVersion.indexOf('_') + 1, nmsVersion.lastIndexOf('_')));
    }

    public static ChannelPipeline getPipelineByPlayer(Player player) {
        try {
            Object entityPlayer = getHandleMethod.invoke(player);
            Object playerConnection = playerConnectionField.get(entityPlayer);
            Object networkManager = networkManagerField.get(playerConnection);
            Object channel = channelField.get(networkManager);
            return (ChannelPipeline) pipelineMethod.invoke(channel);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
