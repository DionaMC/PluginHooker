package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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

    private static final ClassValue<MethodHandle> CHANNEL_PROXY_UNWRAPPER = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> channelClass) {
            if (!channelClass.getSimpleName().equals("NettyChannelProxy")) {
                return null;
            }
            try {
                Field delegateField = channelClass.getDeclaredField("delegate");
                delegateField.setAccessible(true);
                return MethodHandles.lookup()
                        .unreflectGetter(delegateField)
                        .asType(MethodType.methodType(Channel.class, Object.class));
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    };

    private static final MethodHandle BOOTSTRAP_EXTRACTOR;
    private static final MutableCallSite CHANNEL_EXTRACTOR_SITE;
    private static final MethodHandle CHANNEL_EXTRACTOR;

    static {
        try {
            BOOTSTRAP_EXTRACTOR = MethodHandles.lookup().findStatic(
                    PlayerChannelResolver.class,
                    "resolveAndBindExtractor",
                    MethodType.methodType(Channel.class, Player.class)
            );
            CHANNEL_EXTRACTOR_SITE = new MutableCallSite(BOOTSTRAP_EXTRACTOR);
            CHANNEL_EXTRACTOR = CHANNEL_EXTRACTOR_SITE.dynamicInvoker();
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

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
     * Returns true if the given channel is an open real Netty network channel.
     */
    public static boolean isOpenRealNetworkChannel(Channel channel) {
        return isRealNetworkChannel(channel) && channel.isOpen();
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
     * Extracts the Netty {@link Channel} for the given Bukkit {@link Player} via a C2-inlineable
     * composite {@link MethodHandle}.
     */
    public static Channel getChannel(Player player) {
        if (isInvalidOrTemporaryPlayer(player)) {
            return null;
        }
        try {
            return (Channel) CHANNEL_EXTRACTOR.invokeExact(player);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static synchronized Channel resolveAndBindExtractor(Player player) throws Throwable {
        MethodHandle current = CHANNEL_EXTRACTOR_SITE.getTarget();
        if (current != BOOTSTRAP_EXTRACTOR) {
            return (Channel) current.invokeExact(player);
        }
        try {
            MethodHandle compiled = buildCompositeExtractor(player.getClass());
            CHANNEL_EXTRACTOR_SITE.setTarget(compiled);
            MutableCallSite.syncAll(new MutableCallSite[]{CHANNEL_EXTRACTOR_SITE});
            return (Channel) compiled.invokeExact(player);
        } catch (Exception ignored) {
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
        MethodHandle getter = CHANNEL_PROXY_UNWRAPPER.get(channel.getClass());
        if (getter == null) {
            return channel;
        }
        try {
            Channel unwrapped = (Channel) getter.invokeExact((Object) channel);
            return unwrapped != null ? unwrapped : channel;
        } catch (Throwable ignored) {
            return channel;
        }
    }

    static MethodHandle buildCompositeExtractor(Class<?> craftPlayerClass) throws Exception {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Method getHandle = craftPlayerClass.getMethod("getHandle");
        getHandle.setAccessible(true);

        Class<?> entityPlayerClass = getHandle.getReturnType();
        List<Field> fieldPath = findChannelFieldPath(entityPlayerClass, 4);
        if (fieldPath == null) {
            throw new IllegalStateException("Unable to locate io.netty.channel.Channel path from " + entityPlayerClass.getName());
        }

        List<MethodHandle> steps = new ArrayList<>(1 + fieldPath.size());
        steps.add(lookup.unreflect(getHandle));
        for (Field field : fieldPath) {
            field.setAccessible(true);
            steps.add(lookup.unreflectGetter(field));
        }

        MethodHandle nonNullTest = lookup.findStatic(
                Objects.class,
                "nonNull",
                MethodType.methodType(boolean.class, Object.class)
        );
        MethodHandle tail = lookup.findStatic(
                PlayerChannelResolver.class,
                "unwrapChannelProxy",
                MethodType.methodType(Channel.class, Channel.class)
        ).asType(MethodType.methodType(Channel.class, steps.getLast().type().returnType()));

        // Fold right-to-left into a single null-safe MethodHandle chain
        for (int i = steps.size() - 1; i >= 0; i--) {
            MethodHandle step = steps.get(i);
            Class<?> stepOut = step.type().returnType();
            MethodHandle test = nonNullTest.asType(MethodType.methodType(boolean.class, stepOut));
            MethodHandle nullBranch = MethodHandles.dropArguments(
                    MethodHandles.constant(Channel.class, null),
                    0,
                    stepOut
            );
            MethodHandle safeTail = MethodHandles.guardWithTest(test, tail, nullBranch);
            tail = MethodHandles.filterReturnValue(step, safeTail);
        }

        return tail.asType(MethodType.methodType(Channel.class, Player.class));
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
}
