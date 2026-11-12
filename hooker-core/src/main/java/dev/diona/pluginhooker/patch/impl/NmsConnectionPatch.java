package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.PlayerHookRegisterEvent;
import dev.diona.pluginhooker.api.event.PlayerKickHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.BukkitEvents;
import dev.diona.pluginhooker.util.KickReasonSerializer;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.constant.ConstantDescs;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Hooks NMS {@code PlayerConnection}, {@code NetworkManager}, {@code Connection},
 * {@code ServerCommonPacketListenerImpl}, and {@code ServerGamePacketListenerImpl}
 * across Spigot 1.8.8 ~ Paper 26.3 to:
 * <ul>
 *   <li>Register players and bind their Netty channel at connection {@code <init>} before {@code JOIN_GAME}</li>
 *   <li>Block direct/reflective {@code sendPacket}/{@code send}/{@code handle} calls from disabled plugins</li>
 *   <li>Block direct/reflective {@code disconnect}/{@code disconnectAsync}/{@code close} kicks from disabled plugins</li>
 * </ul>
 */
@RequiredArgsConstructor
public final class NmsConnectionPatch extends ClassPatch {

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
        // 1. Direct zero-arg method returning Player (e.g. PlayerConnection#getPlayer, ServerGamePacketListenerImpl#getCraftPlayer)
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
        // 2. Zero-arg method returning an NMS entity with getBukkitEntity(): Player (e.g. Connection#getPlayer -> ServerPlayer)
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
        // 3. Instance field whose type has getBukkitEntity(): Player or a zero-arg Player getter
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

    private final Logger logger;
    private final HookConfig config;
    private final PluginRegistryImpl pluginRegistry;
    private final PlayerRegistryImpl playerRegistry;
    private final ClassLoader selfClassLoader;

    @Override
    public boolean isEnabled() {
        return config.bukkitApi().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        if (internalClassName.equals("net/minecraft/network/Connection")
                || internalClassName.equals("net/minecraft/network/NetworkManager")) {
            return true;
        }
        if (!internalClassName.startsWith("net/minecraft/server/")) {
            return false;
        }
        return internalClassName.endsWith("/PlayerConnection")
                || internalClassName.endsWith("/NetworkManager")
                || internalClassName.equals("net/minecraft/server/network/ServerCommonPacketListenerImpl")
                || internalClassName.equals("net/minecraft/server/network/ServerGamePacketListenerImpl");
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        boolean isGameConnection = internalClassName.endsWith("/PlayerConnection")
                || internalClassName.endsWith("/ServerGamePacketListenerImpl");
        boolean isRawConnection = internalClassName.equals("net/minecraft/network/Connection")
                || internalClassName.endsWith("/NetworkManager");
        CodeTransform channelWriteWrapper = isRawConnection
                ? CodeTransforms.wrapNmsChannelWriteCalls(HookIds.NOTIFY_NMS_INTERNAL_WRITE)
                : null;

        return ClassTransform.transformingMethods(
                method -> isTargetGuardMethod(method, isGameConnection, isRawConnection)
                        || (isRawConnection && hasChannelWriteCall(method)),
                (mb, element) -> {
                    if (element instanceof CodeModel codeModel) {
                        var parentOpt = codeModel.parent();
                        boolean guardTarget = parentOpt
                                .map(m -> isTargetGuardMethod(m, isGameConnection, isRawConnection))
                                .orElse(false);
                        String name = parentOpt.map(m -> m.methodName().stringValue()).orElse("");

                        CodeTransform transform = null;
                        if (guardTarget) {
                            if (name.equals(ConstantDescs.INIT_NAME)) {
                                transform = CodeTransforms.notifyBeforeReturn(
                                        HookIds.NOTIFY_NMS_PLAYER_CONNECTION_INIT,
                                        ArgSpec.thisRef(),
                                        ArgSpec.nullConst()
                                );
                            } else if (name.equals("disconnect") || name.equals("disconnectAsync") || name.equals("close")) {
                                transform = CodeTransforms.guardVoidEntry(
                                        HookIds.GUARD_NMS_DISCONNECT,
                                        ArgSpec.thisRef(),
                                        ArgSpec.param(0),
                                        ArgSpec.callerClass()
                                );
                            } else {
                                transform = CodeTransforms.guardVoidEntry(
                                        HookIds.GUARD_NMS_SEND_PACKET,
                                        ArgSpec.thisRef(),
                                        ArgSpec.param(0),
                                        ArgSpec.callerClass()
                                );
                            }
                        }
                        if (channelWriteWrapper != null && containsChannelWriteCall(codeModel)) {
                            transform = transform != null ? transform.andThen(channelWriteWrapper) : channelWriteWrapper;
                        }
                        if (transform != null) {
                            mb.transformCode(codeModel, transform);
                        } else {
                            mb.with(element);
                        }
                    } else {
                        mb.with(element);
                    }
                }
        );
    }

    private static boolean isTargetGuardMethod(MethodModel method, boolean isGameConnection, boolean isRawConnection) {
        int flags = method.flags().flagsMask();
        if ((flags & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC)) != 0) {
            return false;
        }
        String name = method.methodName().stringValue();
        var mtd = method.methodTypeSymbol();
        if (!mtd.returnType().equals(ConstantDescs.CD_void)) {
            return false;
        }
        if (isGameConnection && name.equals(ConstantDescs.INIT_NAME)) {
            return true;
        }
        int paramCount = mtd.parameterCount();
        if ((flags & ClassFile.ACC_PUBLIC) == 0
                || paramCount < 1
                || mtd.parameterType(0).isPrimitive()) {
            return false;
        }
        if ((name.equals("sendPacket") || name.equals("send")) && paramCount <= 3) {
            return true;
        }
        if (isRawConnection) {
            if (name.equals("sendPacketAndScheduleNettyTask") && paramCount == 3) {
                return true;
            }
            if ((name.equals("handle") && paramCount == 1)
                    || (name.equals("a") && (paramCount == 2 || paramCount == 3))) {
                return isPacketDescriptor(mtd.parameterType(0).descriptorString());
            }
            if (name.equals("close") && paramCount == 1) {
                return true;
            }
        }
        return name.equals("disconnect") || name.equals("disconnectAsync");
    }

    private static boolean hasChannelWriteCall(MethodModel method) {
        return method.code().map(NmsConnectionPatch::containsChannelWriteCall).orElse(false);
    }

    private static boolean containsChannelWriteCall(CodeModel code) {
        for (CodeElement element : code) {
            if (CodeTransforms.isChannelWriteCall(element)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPacketDescriptor(String descriptor) {
        return descriptor.startsWith("Lnet/minecraft/") && descriptor.endsWith("/Packet;");
    }

    public boolean handleSendPacket(Object connectionObj, Object packetObj, Object callerClassObj) {
        if (!playerRegistry.hasAnyPlayerWithDisabledPlugin() || connectionObj == null) {
            return false;
        }
        HookedPlayer hookedPlayer = resolveHookedPlayer(connectionObj);
        if (hookedPlayer == null || !hookedPlayer.hasAnyDisabledPlugin()) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveDirectNmsCaller(
                pluginRegistry,
                selfClassLoader,
                connectionObj.getClass(),
                callerClassObj
        );
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                String packetName = packetObj != null ? packetObj.getClass().getSimpleName() : "null";
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from sending NMS packet " + packetName
                        + " to player " + hookedPlayer.getName());
            }
            return true;
        }
        return false;
    }

    public boolean handleDisconnect(Object connectionObj, Object reasonObj, Object callerClassObj) {
        if (!pluginRegistry.hasHookedPlugins() || connectionObj == null) {
            return false;
        }
        HookedPlayer hookedPlayer = resolveHookedPlayer(connectionObj);
        if (hookedPlayer == null || (!hookedPlayer.hasAnyDisabledPlugin() && !config.bukkitApi().isCallKickEvent())) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveDirectNmsCaller(
                pluginRegistry,
                selfClassLoader,
                connectionObj.getClass(),
                callerClassObj
        );
        if (caller == null) {
            return false;
        }

        boolean enabled = hookedPlayer.isPluginEnabled(caller);
        boolean defaultBlock = !enabled && config.bukkitApi().isBlockDisabledKick();
        String reason = KickReasonSerializer.serialize(reasonObj);
        boolean shouldBlock = BukkitEvents.fireCancellable(
                config.bukkitApi().isCallKickEvent(),
                defaultBlock,
                () -> new PlayerKickHookEvent(caller, hookedPlayer, reason, reasonObj, enabled, defaultBlock)
        );

        if (shouldBlock && config.isDebug()) {
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from disconnecting player " + hookedPlayer.getName()
                    + " via NMS (reason: " + reason + ")");
        }
        return shouldBlock;
    }

    public void handleConnectionInit(Object connectionObj) {
        if (connectionObj == null) {
            return;
        }
        String simpleName = connectionObj.getClass().getSimpleName();
        if (!simpleName.equals("PlayerConnection") && !simpleName.equals("ServerGamePacketListenerImpl")) {
            return;
        }
        Player bukkitPlayer = extractPlayer(connectionObj);
        if (bukkitPlayer != null) {
            HookedPlayer hookedPlayer = playerRegistry.registerPlayer(bukkitPlayer);
            if (hookedPlayer != null) {
                BukkitEvents.fireIfReady(() -> new PlayerHookRegisterEvent(bukkitPlayer, hookedPlayer));
            }
        }
    }

    private HookedPlayer resolveHookedPlayer(Object connectionObj) {
        ConnectionAccessor acc = CONNECTION_ACCESSOR.get(connectionObj.getClass());
        if (acc.channelGetter() != null) {
            try {
                Channel ch = (Channel) acc.channelGetter().invokeExact(connectionObj);
                if (ch != null) {
                    HookedPlayer hp = PlayerChannelResolver.getHookedPlayer(ch);
                    if (hp != null) {
                        return hp;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Player bukkitPlayer = extractPlayer(acc, connectionObj);
        return playerRegistry.getPlayerFast(bukkitPlayer);
    }

    private static Player extractPlayer(Object connectionObj) {
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
}
