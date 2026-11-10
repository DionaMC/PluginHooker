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
import dev.diona.pluginhooker.util.PluginCallerResolver;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.constant.ConstantDescs;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Hooks NMS {@code PlayerConnection}, {@code ServerCommonPacketListenerImpl}, and {@code ServerGamePacketListenerImpl}
 * across Spigot 1.8.8 ~ Paper 26.3 to:
 * <ul>
 *   <li>Register players and bind their Netty channel at connection {@code <init>} before {@code JOIN_GAME}</li>
 *   <li>Block direct/reflective {@code sendPacket}/{@code send} calls from disabled plugins without {@code StackWalker.walk}</li>
 *   <li>Block direct/reflective {@code disconnect}/{@code disconnectAsync} kicks from disabled plugins</li>
 * </ul>
 */
@RequiredArgsConstructor
public final class NmsConnectionPatch extends ClassPatch {

    private static final ClassValue<Optional<MethodHandle>> NMS_CONNECTION_PLAYER_GETTER = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> connectionClass) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            for (Method m : connectionClass.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                    continue;
                }
                if (Player.class.isAssignableFrom(m.getReturnType())) {
                    try {
                        m.setAccessible(true);
                        return Optional.of(
                                lookup.unreflect(m).asType(MethodType.methodType(Player.class, Object.class))
                        );
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }
            for (Class<?> c = connectionClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    for (Method m : f.getType().getMethods()) {
                        if (m.getName().equals("getBukkitEntity")
                                && m.getParameterCount() == 0
                                && Player.class.isAssignableFrom(m.getReturnType())) {
                            try {
                                f.setAccessible(true);
                                m.setAccessible(true);
                                MethodHandle fieldGetter = lookup.unreflectGetter(f);
                                MethodHandle entityGetter = lookup.unreflect(m);
                                return Optional.of(
                                        MethodHandles.filterReturnValue(fieldGetter, entityGetter)
                                                .asType(MethodType.methodType(Player.class, Object.class))
                                );
                            } catch (ReflectiveOperationException ignored) {
                            }
                        }
                    }
                }
            }
            return Optional.empty();
        }
    };

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
        if (!internalClassName.startsWith("net/minecraft/server/")) {
            return false;
        }
        return internalClassName.endsWith("/PlayerConnection")
                || internalClassName.equals("net/minecraft/server/network/ServerCommonPacketListenerImpl")
                || internalClassName.equals("net/minecraft/server/network/ServerGamePacketListenerImpl");
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        boolean isGameConnection = internalClassName.endsWith("/PlayerConnection")
                || internalClassName.endsWith("/ServerGamePacketListenerImpl");

        return ClassTransform.transformingMethods(
                method -> {
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
                    if ((flags & ClassFile.ACC_PUBLIC) == 0
                            || mtd.parameterCount() < 1
                            || mtd.parameterType(0).isPrimitive()) {
                        return false;
                    }
                    if ((name.equals("sendPacket") || name.equals("send"))
                            && (mtd.parameterCount() == 1 || mtd.parameterCount() == 2)) {
                        return true;
                    }
                    return name.equals("disconnect") || name.equals("disconnectAsync");
                },
                (mb, element) -> {
                    if (element instanceof CodeModel codeModel) {
                        String name = codeModel.parent().map(m -> m.methodName().stringValue()).orElse("");
                        if (name.equals(ConstantDescs.INIT_NAME)) {
                            mb.transformCode(
                                    codeModel,
                                    CodeTransforms.notifyBeforeReturn(
                                            HookIds.NOTIFY_NMS_PLAYER_CONNECTION_INIT,
                                            ArgSpec.thisRef(),
                                            ArgSpec.nullConst()
                                    )
                            );
                        } else if (name.equals("disconnect") || name.equals("disconnectAsync")) {
                            mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardVoidEntryWithCaller(
                                            HookIds.GUARD_NMS_DISCONNECT,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0)
                                    )
                            );
                        } else {
                            mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardVoidEntryWithCaller(
                                            HookIds.GUARD_NMS_SEND_PACKET,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0)
                                    )
                            );
                        }
                    } else {
                        mb.with(element);
                    }
                }
        );
    }

    public boolean handleSendPacket(Object connectionObj, Object packetObj, Object callerClassObj) {
        Plugin caller = resolveDirectHookedCaller(callerClassObj);
        if (caller == null || connectionObj == null) {
            return false;
        }
        Player bukkitPlayer = extractPlayer(connectionObj);
        if (bukkitPlayer == null) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer.getUniqueId());
        if (hookedPlayer != null && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                String packetName = packetObj != null ? packetObj.getClass().getSimpleName() : "null";
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from sending NMS packet " + packetName
                        + " to player " + bukkitPlayer.getName());
            }
            return true;
        }
        return false;
    }

    public boolean handleDisconnect(Object connectionObj, Object reasonObj, Object callerClassObj) {
        Plugin caller = resolveDirectHookedCaller(callerClassObj);
        if (caller == null || connectionObj == null) {
            return false;
        }
        Player bukkitPlayer = extractPlayer(connectionObj);
        if (bukkitPlayer == null) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayerFast(bukkitPlayer);
        if (hookedPlayer == null) {
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
                    + " from disconnecting player " + bukkitPlayer.getName()
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

    private Plugin resolveDirectHookedCaller(Object callerClassObj) {
        if (!(callerClassObj instanceof Class<?> callerClass)) {
            return null;
        }
        ClassLoader callerLoader = callerClass.getClassLoader();
        if (callerLoader == null || callerLoader == selfClassLoader) {
            return null;
        }
        Plugin caller = pluginRegistry.getPluginByClassLoader(callerLoader);
        if (caller == null || PluginCallerResolver.isSharedPacketFramework(caller)) {
            return null;
        }
        return pluginRegistry.isHooked(caller) ? caller : null;
    }

    private static Player extractPlayer(Object connectionObj) {
        Optional<MethodHandle> getterOpt = NMS_CONNECTION_PLAYER_GETTER.get(connectionObj.getClass());
        if (getterOpt.isEmpty()) {
            return null;
        }
        try {
            return (Player) getterOpt.get().invokeExact(connectionObj);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
