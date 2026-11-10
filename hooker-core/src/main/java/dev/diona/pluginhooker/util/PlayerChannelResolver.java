package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Version-independent resolver for extracting a player's Netty {@link Channel} across Spigot 1.8.8 ~ 26.3.
 * Uses bounded BFS over NMS fields to locate {@link Channel} and compiles the access path into a {@link MethodHandle} chain.
 */
public final class PlayerChannelResolver {

    public static final AttributeKey<HookedPlayer> HOOKED_PLAYER_KEY =
            AttributeKey.valueOf("pluginhooker_hooked_player");
    private static final AttributeKey<Boolean> CLOSE_LISTENER_BOUND_KEY =
            AttributeKey.valueOf("pluginhooker_close_listener_bound");

    private static final ClassValue<Boolean> REAL_CHANNEL_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> channelClass) {
            String className = channelClass.getName();
            return className.startsWith("io.netty.") && !className.startsWith("io.netty.channel.embedded.");
        }
    };

    private static final ClassValue<Boolean> TEMPORARY_PLAYER_CACHE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> playerClass) {
            return playerClass.getName().startsWith("com.comphenix.protocol.injector.");
        }
    };

    private static final ClassValue<Optional<MethodHandle>> CHANNEL_PROXY_UNWRAPPER = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> channelClass) {
            if (!channelClass.getSimpleName().equals("NettyChannelProxy")) {
                return Optional.empty();
            }
            try {
                Field delegateField = channelClass.getDeclaredField("delegate");
                delegateField.setAccessible(true);
                return Optional.of(
                        MethodHandles.lookup()
                                .unreflectGetter(delegateField)
                                .asType(MethodType.methodType(Channel.class, Object.class))
                );
            } catch (ReflectiveOperationException e) {
                return Optional.empty();
            }
        }
    };

    private static volatile MethodHandle[] channelAccessorChain;

    private PlayerChannelResolver() {
    }

    /**
     * Returns true if the given player is null or a ProtocolLib temporary player proxy.
     */
    public static boolean isInvalidOrTemporaryPlayer(Player player) {
        return player == null || TEMPORARY_PLAYER_CACHE.get(player.getClass());
    }

    /**
     * Returns true if the given channel is a real Netty network channel
     * (not null and not a plugin dummy/embedded channel such as {@code EmptyChannel}).
     */
    public static boolean isRealNetworkChannel(Channel channel) {
        return channel != null && REAL_CHANNEL_CACHE.get(channel.getClass());
    }

    /**
     * Binds a {@link HookedPlayer} directly onto the player's Netty {@link Channel} attributes for O(1) lookup,
     * and registers a cleanup callback on the channel's close future.
     */
    public static void bindPlayerToChannel(
            Channel channel,
            HookedPlayer hookedPlayer,
            Consumer<HookedPlayer> onChannelClose
    ) {
        if (!isRealNetworkChannel(channel)) {
            return;
        }
        channel.attr(HOOKED_PLAYER_KEY).set(hookedPlayer);
        if (onChannelClose != null) {
            if (!Boolean.TRUE.equals(channel.attr(CLOSE_LISTENER_BOUND_KEY).getAndSet(Boolean.TRUE))) {
                channel.closeFuture().addListener(future -> {
                    HookedPlayer bound = channel.attr(HOOKED_PLAYER_KEY).getAndSet(null);
                    onChannelClose.accept(bound != null ? bound : hookedPlayer);
                });
            }
            if (!channel.isOpen()) {
                HookedPlayer bound = channel.attr(HOOKED_PLAYER_KEY).getAndSet(null);
                if (bound != null) {
                    onChannelClose.accept(bound);
                }
            }
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
     * Resolves the {@link HookedPlayer} for a Netty {@link Channel} in O(1) time via the channel attribute.
     */
    public static HookedPlayer resolveHookedPlayer(Channel channel, PlayerRegistry playerRegistry) {
        if (channel == null) {
            return null;
        }
        return channel.attr(HOOKED_PLAYER_KEY).get();
    }

    /**
     * Extracts the Netty {@link Channel} for the given Bukkit {@link Player}.
     */
    public static Channel getChannel(Player player) {
        if (isInvalidOrTemporaryPlayer(player)) {
            return null;
        }
        try {
            MethodHandle[] chain = channelAccessorChain;
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
                current = handle.invokeExact(current);
            }
            return unwrapChannelProxy((Channel) current);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Unwraps ProtocolLib's {@code NettyChannelProxy} (if present) so we always return the underlying
     * Netty {@link Channel} instance.
     */
    public static Channel unwrapChannelProxy(Channel channel) {
        if (channel == null) {
            return null;
        }
        Optional<MethodHandle> getterOpt = CHANNEL_PROXY_UNWRAPPER.get(channel.getClass());
        if (getterOpt.isEmpty()) {
            return channel;
        }
        try {
            Channel unwrapped = (Channel) getterOpt.get().invokeExact((Object) channel);
            return unwrapped != null ? unwrapped : channel;
        } catch (Throwable ignored) {
            return channel;
        }
    }

    private static MethodHandle[] buildAccessorChain(Class<?> craftPlayerClass) throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType genericGetterType = MethodType.methodType(Object.class, Object.class);
        Method getHandle = craftPlayerClass.getMethod("getHandle");
        getHandle.setAccessible(true);

        List<MethodHandle> initial = new ArrayList<>();
        initial.add(lookup.unreflect(getHandle).asType(genericGetterType));

        Class<?> entityPlayerClass = getHandle.getReturnType();
        List<Field> fieldPath = findChannelFieldPath(entityPlayerClass, 4);
        if (fieldPath == null) {
            throw new IllegalStateException("Unable to locate io.netty.channel.Channel path from " + entityPlayerClass.getName());
        }

        for (Field field : fieldPath) {
            field.setAccessible(true);
            initial.add(lookup.unreflectGetter(field).asType(genericGetterType));
        }
        return initial.toArray(MethodHandle[]::new);
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
