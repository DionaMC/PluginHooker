package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Version-independent resolver for extracting a player's Netty {@link Channel} across Spigot 1.8.8 ~ 26.3.
 * Uses bounded BFS over NMS fields to locate {@link Channel} and compiles the access path into a {@link MethodHandle} chain.
 */
public final class PlayerChannelResolver {

    public static final AttributeKey<HookedPlayer> HOOKED_PLAYER_KEY =
            AttributeKey.valueOf("pluginhooker_hooked_player");

    private static volatile List<MethodHandle> channelAccessorChain;

    private PlayerChannelResolver() {
    }

    /**
     * Binds a {@link HookedPlayer} directly onto the player's Netty {@link Channel} attributes for O(1) lookup.
     */
    public static void bindPlayerToChannel(Player bukkitPlayer, HookedPlayer hookedPlayer) {
        Channel channel = getChannel(bukkitPlayer);
        if (channel != null) {
            channel.attr(HOOKED_PLAYER_KEY).set(hookedPlayer);
        }
    }

    /**
     * Unbinds the {@link HookedPlayer} from the player's Netty {@link Channel}.
     */
    public static void unbindPlayerFromChannel(Player bukkitPlayer) {
        Channel channel = getChannel(bukkitPlayer);
        if (channel != null) {
            channel.attr(HOOKED_PLAYER_KEY).set(null);
        }
    }

    /**
     * Retrieves the {@link HookedPlayer} bound to the given Netty {@link Channel} in O(1) time.
     */
    public static HookedPlayer getHookedPlayer(Channel channel) {
        if (channel == null) {
            return null;
        }
        return channel.attr(HOOKED_PLAYER_KEY).get();
    }

    /**
     * Extracts the Netty {@link Channel} for the given Bukkit {@link Player}.
     */
    public static Channel getChannel(Player player) {
        if (player == null) {
            return null;
        }
        try {
            List<MethodHandle> chain = channelAccessorChain;
            if (chain == null) {
                synchronized (PlayerChannelResolver.class) {
                    chain = channelAccessorChain;
                    if (chain == null) {
                        chain = buildAccessorChain(player.getClass());
                        channelAccessorChain = chain;
                    }
                }
            }
            Object current = player;
            for (MethodHandle handle : chain) {
                if (current == null) {
                    return null;
                }
                current = handle.invoke(current);
            }
            return (Channel) current;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<MethodHandle> buildAccessorChain(Class<?> craftPlayerClass) throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Method getHandle = craftPlayerClass.getMethod("getHandle");
        getHandle.setAccessible(true);

        List<MethodHandle> initial = new ArrayList<>();
        initial.add(lookup.unreflect(getHandle));

        Class<?> entityPlayerClass = getHandle.getReturnType();
        List<Field> fieldPath = findChannelFieldPath(entityPlayerClass, 4);
        if (fieldPath == null) {
            throw new IllegalStateException("Unable to locate io.netty.channel.Channel path from " + entityPlayerClass.getName());
        }

        for (Field field : fieldPath) {
            field.setAccessible(true);
            initial.add(lookup.unreflectGetter(field));
        }
        return List.copyOf(initial);
    }

    private static List<Field> findChannelFieldPath(Class<?> rootClass, int maxDepth) {
        record SearchNode(Class<?> clazz, List<Field> path) {
        }

        Queue<SearchNode> queue = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        queue.add(new SearchNode(rootClass, List.of()));
        visited.add(rootClass);

        while (!queue.isEmpty()) {
            SearchNode current = queue.poll();
            if (current.path().size() >= maxDepth) {
                continue;
            }

            for (Class<?> c = current.clazz(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    Class<?> fieldType = field.getType();
                    if (Channel.class.isAssignableFrom(fieldType)) {
                        List<Field> foundPath = new ArrayList<>(current.path());
                        foundPath.add(field);
                        return foundPath;
                    }
                    String typeName = fieldType.getName();
                    if ((typeName.startsWith("net.minecraft.") || typeName.contains(".server."))
                            && visited.add(fieldType)) {
                        List<Field> nextPath = new ArrayList<>(current.path());
                        nextPath.add(field);
                        queue.add(new SearchNode(fieldType, nextPath));
                    }
                }
            }
        }
        return null;
    }

    /**
     * Fallback lookup by scanning online players if a channel attribute has not been bound yet.
     */
    public static Player findOnlinePlayerByChannel(Channel targetChannel) {
        if (targetChannel == null) {
            return null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (getChannel(player) == targetChannel) {
                return player;
            }
        }
        return null;
    }
}
