package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.PlayerKickHookEvent;
import dev.diona.pluginhooker.api.event.PluginDispatchCommandEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.BukkitEvents;
import dev.diona.pluginhooker.util.CommandTargetResolver;
import dev.diona.pluginhooker.util.KickReasonSerializer;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Hooks Bukkit side-effect APIs across {@code CraftServer}, {@code CraftPlayer}, {@code CraftHumanEntity},
 * and {@code CraftEntity} to block disabled plugins from kicking, teleporting, modifying velocity,
 * sending messages/HUD elements, closing inventories, or dispatching targeted commands.
 */
@RequiredArgsConstructor
public final class BukkitApiPatch extends ClassPatch {

    private static final ClassDesc COMPLETABLE_FUTURE_DESC = ClassDesc.of("java.util.concurrent.CompletableFuture");

    private static final Set<String> PLAYER_HUD_MESSAGE_METHODS = Set.of(
            "sendMessage",
            "sendActionBar",
            "sendTitle",
            "showTitle",
            "sendTitlePart",
            "setSubtitle",
            "updateTitle",
            "resetTitle",
            "clearTitle",
            "hideTitle"
    );

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
        if (!internalClassName.startsWith("org/bukkit/craftbukkit/")) {
            return false;
        }
        return internalClassName.endsWith("/CraftServer")
                || internalClassName.endsWith("/entity/CraftPlayer")
                || isAnonymousInnerClass(internalClassName, "/entity/CraftPlayer$")
                || internalClassName.endsWith("/entity/CraftHumanEntity")
                || internalClassName.endsWith("/entity/CraftEntity");
    }

    private static boolean isAnonymousInnerClass(String internalClassName, String marker) {
        int idx = internalClassName.indexOf(marker);
        if (idx < 0) {
            return false;
        }
        int suffixStart = idx + marker.length();
        return suffixStart < internalClassName.length() && Character.isDigit(internalClassName.charAt(suffixStart));
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        if (internalClassName.endsWith("/CraftServer")) {
            return createCraftServerActionTransform();
        }
        if (internalClassName.endsWith("/entity/CraftPlayer")) {
            return createCraftPlayerTransform();
        }
        if (internalClassName.contains("/entity/CraftPlayer$")) {
            return createCraftPlayerSpigotTransform(internalClassName, classModel);
        }
        if (internalClassName.endsWith("/entity/CraftHumanEntity")) {
            return createCraftHumanEntityTransform();
        }
        return createCraftEntityActionTransform();
    }

    private static ClassTransform createCraftServerActionTransform() {
        return ClassTransform.transformingMethodBodies(
                method -> (method.flags().flagsMask() & ClassFile.ACC_BRIDGE) == 0
                        && method.methodName().equalsString("dispatchCommand")
                        && method.methodTypeSymbol().parameterCount() == 2
                        && method.methodTypeSymbol().returnType().equals(ConstantDescs.CD_boolean),
                CodeTransforms.guardBooleanEntry(
                        HookIds.GUARD_DISPATCH_COMMAND,
                        ArgSpec.param(0),
                        ArgSpec.param(1),
                        ArgSpec.nullConst()
                )
        );
    }

    private static ClassTransform createCraftPlayerTransform() {
        return ClassTransform.transformingMethods(
                method -> {
                    int flags = method.flags().flagsMask();
                    if ((flags & (ClassFile.ACC_BRIDGE | ClassFile.ACC_ABSTRACT)) != 0) {
                        return false;
                    }
                    String name = method.methodName().stringValue();
                    var mtd = method.methodTypeSymbol();
                    if ((name.equals("kickPlayer") && mtd.parameterCount() == 1)
                            || (name.equals("kick") && mtd.parameterCount() >= 1)) {
                        return mtd.returnType().equals(ConstantDescs.CD_void);
                    }
                    if ((name.equals("teleport") && mtd.parameterCount() == 2)
                            || (name.equals("teleport0") && mtd.parameterCount() == 3)) {
                        return mtd.returnType().equals(ConstantDescs.CD_boolean);
                    }
                    return (flags & ClassFile.ACC_PUBLIC) != 0
                            && mtd.returnType().equals(ConstantDescs.CD_void)
                            && PLAYER_HUD_MESSAGE_METHODS.contains(name);
                },
                (mb, element) -> {
                    if (element instanceof CodeModel codeModel) {
                        String name = codeModel.parent().map(m -> m.methodName().stringValue()).orElse("");
                        switch (name) {
                            case "kickPlayer", "kick" -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardVoidEntry(
                                            HookIds.GUARD_PLAYER_KICK,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0),
                                            ArgSpec.nullConst()
                                    )
                            );
                            case "teleport", "teleport0" -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardBooleanEntry(
                                            HookIds.GUARD_PLAYER_TELEPORT,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0),
                                            ArgSpec.param(1)
                                    )
                            );
                            default -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardVoidEntry(
                                            HookIds.GUARD_PLAYER_SEND_MESSAGE,
                                            ArgSpec.thisRef(),
                                            ArgSpec.nullConst(),
                                            ArgSpec.nullConst()
                                    )
                            );
                        }
                    } else {
                        mb.with(element);
                    }
                }
        );
    }

    private static ClassTransform createCraftPlayerSpigotTransform(String internalClassName, ClassModel classModel) {
        boolean isPlayerSpigot = classModel.superclass()
                .map(sc -> sc.asInternalName().equals("org/bukkit/entity/Player$Spigot"))
                .orElse(false);
        if (!isPlayerSpigot) {
            return ClassTransform.ACCEPT_ALL;
        }

        ClassDesc ownerDesc = ClassDesc.ofInternalName(internalClassName);
        ClassDesc outerPlayerDesc = classModel.fields().stream()
                .filter(f -> f.fieldName().equalsString("this$0"))
                .findFirst()
                .map(f -> f.fieldTypeSymbol())
                .orElseGet(() -> ClassDesc.ofInternalName(internalClassName.substring(0, internalClassName.indexOf('$'))));

        return ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("sendMessage")
                        && method.methodTypeSymbol().parameterCount() >= 1
                        && method.methodTypeSymbol().returnType().equals(ConstantDescs.CD_void),
                CodeTransforms.guardVoidEntry(
                        HookIds.GUARD_PLAYER_SEND_MESSAGE,
                        ArgSpec.getThisField(ownerDesc, "this$0", outerPlayerDesc),
                        ArgSpec.param(0),
                        ArgSpec.nullConst()
                )
        );
    }

    private static ClassTransform createCraftHumanEntityTransform() {
        return ClassTransform.transformingMethodBodies(
                method -> (method.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE | ClassFile.ACC_ABSTRACT)) == ClassFile.ACC_PUBLIC
                        && method.methodName().equalsString("closeInventory")
                        && method.methodTypeSymbol().parameterCount() <= 1
                        && method.methodTypeSymbol().returnType().equals(ConstantDescs.CD_void),
                CodeTransforms.guardVoidEntry(
                        HookIds.GUARD_PLAYER_CLOSE_INVENTORY,
                        ArgSpec.thisRef(),
                        ArgSpec.nullConst(),
                        ArgSpec.nullConst()
                )
        );
    }

    private static ClassTransform createCraftEntityActionTransform() {
        return ClassTransform.transformingMethods(
                method -> {
                    if ((method.flags().flagsMask() & ClassFile.ACC_BRIDGE) != 0) {
                        return false;
                    }
                    String name = method.methodName().stringValue();
                    var mtd = method.methodTypeSymbol();
                    if (name.equals("setVelocity") && mtd.parameterCount() == 1) {
                        return mtd.returnType().equals(ConstantDescs.CD_void);
                    }
                    return name.equals("teleportAsync")
                            && mtd.parameterCount() >= 2
                            && mtd.returnType().equals(COMPLETABLE_FUTURE_DESC);
                },
                (mb, element) -> {
                    if (element instanceof CodeModel codeModel) {
                        String name = codeModel.parent().map(m -> m.methodName().stringValue()).orElse("");
                        if (name.equals("setVelocity")) {
                            mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardVoidEntry(
                                            HookIds.GUARD_ENTITY_VELOCITY,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0),
                                            ArgSpec.nullConst()
                                    )
                            );
                        } else {
                            mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardCompletableFutureBooleanEntry(
                                            HookIds.GUARD_PLAYER_TELEPORT,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0),
                                            ArgSpec.param(1)
                                    )
                            );
                        }
                    } else {
                        mb.with(element);
                    }
                }
        );
    }

    public boolean handlePlayerKick(Object playerObj, Object reasonObj) {
        if (!(playerObj instanceof Player bukkitPlayer)) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayerFast(bukkitPlayer);
        if (hookedPlayer == null) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
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
                    + " from kicking player " + bukkitPlayer.getName()
                    + " (reason: " + reason + ")");
        }
        return shouldBlock;
    }

    public boolean handleDispatchCommand(Object senderObj, Object commandLineObj) {
        if (!(senderObj instanceof CommandSender sender) || !(commandLineObj instanceof String commandLine)) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return false;
        }

        HookedPlayer targetPlayer = CommandTargetResolver.resolveTargetPlayer(commandLine, playerRegistry);
        boolean enabledForTarget = targetPlayer == null || targetPlayer.isPluginEnabled(caller);
        boolean defaultBlock = !enabledForTarget && config.bukkitApi().isBlockDisabledDispatchCommand();
        boolean shouldBlock = BukkitEvents.fireCancellable(
                config.bukkitApi().isCallDispatchCommandEvent(),
                defaultBlock,
                () -> new PluginDispatchCommandEvent(
                        caller, sender, commandLine, targetPlayer, enabledForTarget, defaultBlock
                )
        );

        if (shouldBlock && config.isDebug()) {
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from dispatching command"
                    + (targetPlayer != null ? " targeting " + targetPlayer.getName() : "")
                    + ": /" + commandLine);
        }
        return shouldBlock;
    }

    public boolean handlePlayerTeleport(Object playerObj, Object locationObj, Object causeObj) {
        Plugin caller = resolveDisabledCallerForPlayer(playerObj, config.bukkitApi().isBlockDisabledTeleport());
        if (caller == null) {
            return false;
        }
        if (config.isDebug()) {
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from teleporting player " + ((Player) playerObj).getName()
                    + " (cause: " + causeObj + ")");
        }
        return true;
    }

    public boolean handleEntityVelocity(Object entityObj, Object velocityObj) {
        Plugin caller = resolveDisabledCallerForPlayer(entityObj, config.bukkitApi().isBlockDisabledVelocity());
        if (caller == null) {
            return false;
        }
        if (config.isDebug()) {
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from setting velocity on player " + ((Player) entityObj).getName()
                    + " (" + velocityObj + ")");
        }
        return true;
    }

    public boolean handlePlayerSendMessage(Object playerObj, Object msgObj) {
        Plugin caller = resolveDisabledCallerForPlayer(playerObj, config.bukkitApi().isBlockDisabledSendMessage());
        if (caller == null) {
            return false;
        }
        if (config.isDebug()) {
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from sending message/HUD to player " + ((Player) playerObj).getName());
        }
        return true;
    }

    public boolean handlePlayerCloseInventory(Object humanObj) {
        Plugin caller = resolveDisabledCallerForPlayer(humanObj, config.bukkitApi().isBlockDisabledCloseInventory());
        if (caller == null) {
            return false;
        }
        if (config.isDebug()) {
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from closing inventory of player " + ((Player) humanObj).getName());
        }
        return true;
    }

    private Plugin resolveDisabledCallerForPlayer(Object playerObj, boolean blockEnabled) {
        if (!blockEnabled || !(playerObj instanceof Player bukkitPlayer)) {
            return null;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayerFast(bukkitPlayer);
        if (hookedPlayer == null || !hookedPlayer.hasAnyDisabledPlugin()) {
            return null;
        }
        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        return caller != null && !hookedPlayer.isPluginEnabled(caller) ? caller : null;
    }
}
