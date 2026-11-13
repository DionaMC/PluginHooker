package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Lock-free {@link ClassValue} and {@link MethodHandle} resolver for extracting {@link HookedPlayer}
 * and Bukkit {@link Player} instances from NMS {@code PlayerConnection}, {@code NetworkManager},
 * {@code Connection}, {@code ServerCommonPacketListenerImpl}, and {@code ServerGamePacketListenerImpl}.
 */
public final class NmsConnectionResolver {

    private record ConnectionAccessor(MethodHandle channelGetter, MethodHandle playerGetter) {
    }

    private static final MethodHandle NON_NULL_TEST;
    private static final MethodHandle NULL_PLAYER_CONSTANT;

    static {
        try {
            NON_NULL_TEST = MethodHandles.lookup().findStatic(
                    Objects.class,
                    "nonNull",
                    MethodType.methodType(boolean.class, Object.class)
            );
            NULL_PLAYER_CONSTANT = MethodHandles.dropArguments(
                    MethodHandles.constant(Player.class, null),
                    0,
                    Object.class
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final ClassValue<ConnectionAccessor> CONNECTION_ACCESSOR = new ClassValue<>() {
        @Override
        protected ConnectionAccessor computeValue(Class<?> connectionClass) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            return new ConnectionAccessor(
                    findChannelGetter(lookup, connectionClass),
                    findPlayerGetter(lookup, connectionClass)
            );
        }
    };

    private NmsConnectionResolver() {
    }

    /**
     * Resolves the {@link HookedPlayer} bound directly to the NMS connection's Netty {@link Channel}
     * attribute in O(1) time (unwrapping ProtocolLib channel proxies if present), or {@code null} if
     * the connection class has no direct {@link Channel} field or the channel is not bound.
     */
    public static HookedPlayer resolveChannelHookedPlayer(Object connectionObj) {
        ConnectionAccessor acc = CONNECTION_ACCESSOR.get(connectionObj.getClass());
        if (acc.channelGetter() == null) {
            return null;
        }
        try {
            Channel ch = (Channel) acc.channelGetter().invokeExact(connectionObj);
            return ch != null ? PlayerChannelResolver.getHookedPlayer(PlayerChannelResolver.unwrapChannelProxy(ch)) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Extracts the Bukkit {@link Player} from an NMS connection or packet listener object.
     */
    public static Player extractPlayer(Object connectionObj) {
        return extractPlayer(CONNECTION_ACCESSOR.get(connectionObj.getClass()), connectionObj);
    }

    private static Player extractPlayer(ConnectionAccessor acc, Object connectionObj) {
        MethodHandle getter = acc.playerGetter();
        if (getter == null) {
            return null;
        }
        try {
            return (Player) getter.invokeExact(connectionObj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle findChannelGetter(MethodHandles.Lookup lookup, Class<?> connectionClass) {
        for (Class<?> c = connectionClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && Channel.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return lookup.unreflectGetter(f).asType(MethodType.methodType(Channel.class, Object.class));
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static MethodHandle findPlayerGetter(MethodHandles.Lookup lookup, Class<?> connectionClass) {
        // 1. Direct zero-arg method returning Player (PlayerConnection#getPlayer, ServerGamePacketListenerImpl#getCraftPlayer)
        for (Method m : connectionClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                continue;
            }
            if (Player.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    return lookup.unreflect(m).asType(MethodType.methodType(Player.class, Object.class));
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        // 2. Zero-arg method returning an NMS entity with getBukkitEntity(): Player (Connection#getPlayer -> ServerPlayer)
        for (Method m : connectionClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0 || m.getReturnType().isPrimitive()) {
                continue;
            }
            MethodHandle toPlayer = findBukkitEntityMethod(lookup, m.getReturnType());
            if (toPlayer != null) {
                try {
                    m.setAccessible(true);
                    return chainNonNullToPlayer(lookup.unreflect(m), toPlayer);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        // 3. Instance field whose type has getBukkitEntity(): Player (ServerGamePacketListenerImpl.player)
        for (Class<?> c = connectionClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                MethodHandle toPlayer = findBukkitEntityMethod(lookup, f.getType());
                if (toPlayer != null) {
                    try {
                        f.setAccessible(true);
                        return chainNonNullToPlayer(lookup.unreflectGetter(f), toPlayer);
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static MethodHandle findBukkitEntityMethod(MethodHandles.Lookup lookup, Class<?> entityType) {
        for (Method m : entityType.getMethods()) {
            if (m.getName().equals("getBukkitEntity")
                    && m.getParameterCount() == 0
                    && Player.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    return lookup.unreflect(m);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    private static MethodHandle chainNonNullToPlayer(MethodHandle firstGetter, MethodHandle toPlayerGetter) {
        MethodHandle objFirst = firstGetter.asType(MethodType.methodType(Object.class, Object.class));
        MethodHandle objSecond = toPlayerGetter.asType(MethodType.methodType(Player.class, Object.class));
        MethodHandle guardedSecond = MethodHandles.guardWithTest(NON_NULL_TEST, objSecond, NULL_PLAYER_CONSTANT);
        return MethodHandles.filterReturnValue(objFirst, guardedSecond);
    }
}
