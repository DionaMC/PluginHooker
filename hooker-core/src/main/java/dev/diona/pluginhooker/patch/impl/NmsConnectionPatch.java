package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.PlayerHookRegisterEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.BukkitEvents;
import dev.diona.pluginhooker.util.KickGuardSupport;
import dev.diona.pluginhooker.util.NmsConnectionResolver;
import dev.diona.pluginhooker.util.PluginCallerResolver;
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
        if (!playerRegistry.canBlockAnyPlayerAction() || connectionObj == null) {
            return false;
        }
        Class<?> connectionClass = connectionObj.getClass();
        Plugin caller = null;
        boolean reflectiveCall = PluginCallerResolver.isReflectiveNmsCall(callerClassObj);
        if (!reflectiveCall) {
            caller = PluginCallerResolver.resolveFastDirectNmsCaller(
                    pluginRegistry, selfClassLoader, connectionClass, (Class<?>) callerClassObj
            );
            if (caller == null) {
                return false;
            }
        }

        HookedPlayer hookedPlayer = NmsConnectionResolver.resolveChannelHookedPlayer(connectionObj);
        Player bukkitPlayer = null;
        if (hookedPlayer == null) {
            bukkitPlayer = NmsConnectionResolver.extractPlayer(connectionObj);
            hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        }
        if (reflectiveCall) {
            if (!playerRegistry.hasDisabledPlugins(bukkitPlayer, hookedPlayer)) {
                return false;
            }
            caller = PluginCallerResolver.resolveReflectiveNmsCaller(
                    pluginRegistry, selfClassLoader, connectionClass
            );
        }
        if (!playerRegistry.isPluginDisabled(bukkitPlayer, hookedPlayer, caller)) {
            return false;
        }
        if (config.isDebug()) {
            String packetName = packetObj != null ? packetObj.getClass().getSimpleName() : "null";
            String targetName = hookedPlayer != null ? hookedPlayer.getName() : bukkitPlayer.getName();
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from sending NMS packet " + packetName
                    + " to player " + targetName);
        }
        return true;
    }

    public boolean handleDisconnect(Object connectionObj, Object reasonObj, Object callerClassObj) {
        if (!pluginRegistry.hasHookedPlugins() || connectionObj == null) {
            return false;
        }
        Class<?> connectionClass = connectionObj.getClass();
        Plugin caller = null;
        boolean reflectiveCall = PluginCallerResolver.isReflectiveNmsCall(callerClassObj);
        if (!reflectiveCall) {
            caller = PluginCallerResolver.resolveFastDirectNmsCaller(
                    pluginRegistry, selfClassLoader, connectionClass, (Class<?>) callerClassObj
            );
            if (caller == null) {
                return false;
            }
        }

        HookedPlayer hookedPlayer = NmsConnectionResolver.resolveChannelHookedPlayer(connectionObj);
        Player bukkitPlayer = null;
        if (hookedPlayer == null) {
            bukkitPlayer = NmsConnectionResolver.extractPlayer(connectionObj);
            hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        }
        if (!KickGuardSupport.shouldInspectKick(bukkitPlayer, hookedPlayer, config.bukkitApi(), playerRegistry)) {
            return false;
        }
        if (reflectiveCall) {
            caller = PluginCallerResolver.resolveReflectiveNmsCaller(
                    pluginRegistry, selfClassLoader, connectionClass
            );
        }
        return KickGuardSupport.evaluateKick(
                caller, bukkitPlayer, hookedPlayer, reasonObj, config, playerRegistry, logger, "disconnecting via NMS"
        );
    }

    public void handleConnectionInit(Object connectionObj) {
        if (connectionObj == null) {
            return;
        }
        String simpleName = connectionObj.getClass().getSimpleName();
        if (!simpleName.equals("PlayerConnection") && !simpleName.equals("ServerGamePacketListenerImpl")) {
            return;
        }
        Player bukkitPlayer = NmsConnectionResolver.extractPlayer(connectionObj);
        if (bukkitPlayer != null) {
            HookedPlayer hookedPlayer = playerRegistry.registerPlayer(bukkitPlayer);
            if (hookedPlayer != null) {
                BukkitEvents.fireIfReady(() -> new PlayerHookRegisterEvent(bukkitPlayer, hookedPlayer));
            }
        }
    }
}
