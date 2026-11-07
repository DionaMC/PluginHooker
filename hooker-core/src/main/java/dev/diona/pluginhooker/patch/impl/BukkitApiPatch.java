package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.PlayerKickHookEvent;
import dev.diona.pluginhooker.api.event.PluginDispatchCommandEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Hooks Bukkit Server, World, RegionAccessor, Player, and Entity APIs across Spigot 1.8.8 ~ Paper 26.3:
 * <ul>
 *   <li>Hides disabled players from {@code getOnlinePlayers}, {@code matchPlayer}, {@code getPlayers},
 *       {@code getPlayersSeeingChunk}, {@code getPlayer}, and entity lists</li>
 *   <li>Intercepts {@code kickPlayer}/{@code kick} and {@code dispatchCommand} with custom hook events</li>
 *   <li>Blocks disabled plugins from calling {@code teleport}/{@code teleport0}, {@code setVelocity}, and {@code sendMessage} on players</li>
 * </ul>
 */
@RequiredArgsConstructor
public final class BukkitApiPatch extends ClassPatch {

    private static final ClassDesc COLLECTION_DESC = ClassDesc.of("java.util.Collection");
    private static final ClassDesc LIST_DESC = ClassDesc.of("java.util.List");
    private static final ClassDesc PLAYER_DESC = ClassDesc.of("org.bukkit.entity.Player");
    private static final ClassDesc UUID_DESC = ClassDesc.of("java.util.UUID");
    private static final ClassDesc COMPLETABLE_FUTURE_DESC = ClassDesc.of("java.util.concurrent.CompletableFuture");

    private static final Set<String> WORLD_ENTITY_LIST_METHODS = Set.of(
            "getEntities",
            "getLivingEntities",
            "getEntitiesByClass",
            "getEntitiesByClasses",
            "getNearbyEntities"
    );

    private static volatile MethodHandle componentSerializerHandle;
    private static volatile boolean componentSerializerResolved;

    private static final ClassValue<Optional<MethodHandle>> NMS_CONNECTION_PLAYER_GETTER = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> connectionClass) {
            for (String methodName : List.of("getCraftPlayer", "getPlayer")) {
                try {
                    Method m = connectionClass.getMethod(methodName);
                    if (Player.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        return Optional.of(
                                MethodHandles.lookup()
                                        .unreflect(m)
                                        .asType(MethodType.methodType(Player.class, Object.class))
                        );
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }
            return Optional.empty();
        }
    };

    private final Logger logger;
    private final HookConfig config;
    private final PluginRegistry pluginRegistry;
    private final PlayerRegistry playerRegistry;
    private final ClassLoader selfClassLoader;

    @Override
    public boolean isEnabled() {
        return config.bukkitApi().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        if (internalClassName.startsWith("net/minecraft/server/")) {
            return internalClassName.endsWith("/PlayerConnection")
                    || internalClassName.equals("net/minecraft/server/network/ServerCommonPacketListenerImpl");
        }
        if (!internalClassName.startsWith("org/bukkit/craftbukkit/")) {
            return false;
        }
        return internalClassName.endsWith("/CraftServer")
                || internalClassName.endsWith("/CraftWorld")
                || internalClassName.endsWith("/CraftRegionAccessor")
                || internalClassName.endsWith("/entity/CraftPlayer")
                || isAnonymousInnerClass(internalClassName, "/entity/CraftPlayer$")
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
        if (internalClassName.startsWith("net/minecraft/server/")) {
            return createNmsPlayerConnectionTransform(internalClassName);
        }
        if (internalClassName.endsWith("/CraftServer")) {
            return createCraftServerTransform();
        }
        if (internalClassName.endsWith("/CraftWorld") || internalClassName.endsWith("/CraftRegionAccessor")) {
            return createCraftWorldTransform();
        }
        if (internalClassName.endsWith("/entity/CraftPlayer")) {
            return createCraftPlayerTransform();
        }
        if (internalClassName.contains("/entity/CraftPlayer$")) {
            return createCraftPlayerSpigotTransform(internalClassName, classModel);
        }
        return createCraftEntityTransform();
    }

    private ClassTransform createCraftServerTransform() {
        return ClassTransform.transformingMethods(
                method -> {
                    if ((method.flags().flagsMask() & ClassFile.ACC_BRIDGE) != 0) {
                        return false;
                    }
                    String name = method.methodName().stringValue();
                    var mtd = method.methodTypeSymbol();
                    ClassDesc returnType = mtd.returnType();

                    if (name.equals("getOnlinePlayers") && mtd.parameterCount() == 0) {
                        return returnType.equals(LIST_DESC) || returnType.equals(COLLECTION_DESC);
                    }
                    if (name.equals("matchPlayer") && mtd.parameterCount() == 1) {
                        return returnType.equals(LIST_DESC);
                    }
                    if ((name.equals("getPlayer") || name.equals("getPlayerExact")) && mtd.parameterCount() == 1) {
                        ClassDesc param0 = mtd.parameterType(0);
                        return returnType.equals(PLAYER_DESC)
                                && (param0.equals(ConstantDescs.CD_String) || param0.equals(UUID_DESC));
                    }
                    if (name.equals("dispatchCommand") && mtd.parameterCount() == 2) {
                        return returnType.equals(ConstantDescs.CD_boolean);
                    }
                    return false;
                },
                (mb, element) -> {
                    if (element instanceof CodeModel codeModel) {
                        var methodOpt = codeModel.parent();
                        String name = methodOpt.map(m -> m.methodName().stringValue()).orElse("");
                        ClassDesc returnType = methodOpt.map(m -> m.methodTypeSymbol().returnType()).orElse(COLLECTION_DESC);

                        switch (name) {
                            case "getOnlinePlayers", "matchPlayer" -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.filterObjectReturn(
                                            HookIds.FILTER_ONLINE_PLAYERS,
                                            returnType,
                                            ArgSpec.thisRef()
                                    )
                            );
                            case "getPlayer", "getPlayerExact" -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.filterObjectReturn(
                                            HookIds.FILTER_GET_PLAYER,
                                            PLAYER_DESC,
                                            ArgSpec.thisRef()
                                    )
                            );
                            case "dispatchCommand" -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardBooleanEntry(
                                            HookIds.GUARD_DISPATCH_COMMAND,
                                            ArgSpec.param(0),
                                            ArgSpec.param(1),
                                            ArgSpec.nullConst()
                                    )
                            );
                            default -> mb.with(element);
                        }
                    } else {
                        mb.with(element);
                    }
                }
        );
    }

    private ClassTransform createCraftWorldTransform() {
        return ClassTransform.transformingMethods(
                method -> {
                    if ((method.flags().flagsMask() & ClassFile.ACC_BRIDGE) != 0) {
                        return false;
                    }
                    String name = method.methodName().stringValue();
                    ClassDesc returnType = method.methodTypeSymbol().returnType();
                    if (name.equals("getPlayers") && method.methodTypeSymbol().parameterCount() == 0) {
                        return returnType.equals(LIST_DESC);
                    }
                    if (name.equals("getPlayersSeeingChunk")) {
                        return returnType.equals(COLLECTION_DESC) || returnType.equals(LIST_DESC);
                    }
                    if (WORLD_ENTITY_LIST_METHODS.contains(name)) {
                        return returnType.equals(LIST_DESC) || returnType.equals(COLLECTION_DESC);
                    }
                    return false;
                },
                (mb, element) -> {
                    if (element instanceof CodeModel codeModel) {
                        var methodOpt = codeModel.parent();
                        String name = methodOpt.map(m -> m.methodName().stringValue()).orElse("");
                        ClassDesc returnType = methodOpt.map(m -> m.methodTypeSymbol().returnType()).orElse(LIST_DESC);
                        int hookId = (name.equals("getPlayers") || name.equals("getPlayersSeeingChunk"))
                                ? HookIds.FILTER_WORLD_PLAYERS
                                : HookIds.FILTER_ENTITY_COLLECTION;
                        mb.transformCode(
                                codeModel,
                                CodeTransforms.filterObjectReturn(hookId, returnType, ArgSpec.thisRef())
                        );
                    } else {
                        mb.with(element);
                    }
                }
        );
    }

    private ClassTransform createCraftPlayerTransform() {
        return ClassTransform.transformingMethods(
                method -> {
                    if ((method.flags().flagsMask() & ClassFile.ACC_BRIDGE) != 0) {
                        return false;
                    }
                    String name = method.methodName().stringValue();
                    var mtd = method.methodTypeSymbol();
                    // Spigot/Paper kickPlayer(String) & Paper kick(Component, Cause)
                    if ((name.equals("kickPlayer") && mtd.parameterCount() == 1)
                            || (name.equals("kick") && mtd.parameterCount() >= 1)) {
                        return mtd.returnType().equals(ConstantDescs.CD_void);
                    }
                    // Spigot teleport(Location, TeleportCause) & Paper teleport0(Location, TeleportCause, TeleportFlag...)
                    if ((name.equals("teleport") && mtd.parameterCount() == 2)
                            || (name.equals("teleport0") && mtd.parameterCount() == 3)) {
                        return mtd.returnType().equals(ConstantDescs.CD_boolean);
                    }
                    // Spigot & Paper sendMessage overloads
                    return name.equals("sendMessage")
                            && mtd.parameterCount() >= 1
                            && mtd.returnType().equals(ConstantDescs.CD_void);
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
                            case "sendMessage" -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardVoidEntry(
                                            HookIds.GUARD_PLAYER_SEND_MESSAGE,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0),
                                            ArgSpec.nullConst()
                                    )
                            );
                            default -> mb.with(element);
                        }
                    } else {
                        mb.with(element);
                    }
                }
        );
    }

    private ClassTransform createCraftPlayerSpigotTransform(String internalClassName, ClassModel classModel) {
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

    private ClassTransform createNmsPlayerConnectionTransform(String internalClassName) {
        boolean isModernCommon = internalClassName.endsWith("/ServerCommonPacketListenerImpl");
        return ClassTransform.transformingMethodBodies(
                method -> {
                    String name = method.methodName().stringValue();
                    var mtd = method.methodTypeSymbol();
                    if (!mtd.returnType().equals(ConstantDescs.CD_void)) {
                        return false;
                    }
                    if (isModernCommon) {
                        return name.equals("send") && mtd.parameterCount() == 2;
                    }
                    return name.equals("sendPacket") && mtd.parameterCount() == 1;
                },
                CodeTransforms.guardVoidEntry(
                        HookIds.GUARD_NMS_SEND_PACKET,
                        ArgSpec.thisRef(),
                        ArgSpec.param(0),
                        ArgSpec.nullConst()
                )
        );
    }

    private ClassTransform createCraftEntityTransform() {
        return ClassTransform.transformingMethods(
                method -> {
                    if ((method.flags().flagsMask() & ClassFile.ACC_BRIDGE) != 0) {
                        return false;
                    }
                    String name = method.methodName().stringValue();
                    var mtd = method.methodTypeSymbol();
                    if (name.equals("setVelocity") && mtd.parameterCount() == 1 && mtd.returnType().equals(ConstantDescs.CD_void)) {
                        return true;
                    }
                    if (name.equals("teleportAsync")
                            && mtd.parameterCount() >= 2
                            && mtd.returnType().equals(COMPLETABLE_FUTURE_DESC)) {
                        return true;
                    }
                    return name.equals("getNearbyEntities")
                            && mtd.parameterCount() == 3
                            && mtd.returnType().equals(LIST_DESC);
                },
                (mb, element) -> {
                    if (element instanceof CodeModel codeModel) {
                        String name = codeModel.parent().map(m -> m.methodName().stringValue()).orElse("");
                        switch (name) {
                            case "setVelocity" -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardVoidEntry(
                                            HookIds.GUARD_ENTITY_VELOCITY,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0),
                                            ArgSpec.nullConst()
                                    )
                            );
                            case "teleportAsync" -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.guardCompletableFutureBooleanEntry(
                                            HookIds.GUARD_PLAYER_TELEPORT,
                                            ArgSpec.thisRef(),
                                            ArgSpec.param(0),
                                            ArgSpec.param(1)
                                    )
                            );
                            default -> mb.transformCode(
                                    codeModel,
                                    CodeTransforms.filterObjectReturn(
                                            HookIds.FILTER_ENTITY_COLLECTION,
                                            LIST_DESC,
                                            ArgSpec.thisRef()
                                    )
                            );
                        }
                    } else {
                        mb.with(element);
                    }
                }
        );
    }

    // =========================================================================
    // Guard Handlers (Kick, DispatchCommand, Teleport, Velocity, SendMessage)
    // =========================================================================

    public boolean handlePlayerKick(Object playerObj, Object reasonObj) {
        if (!(playerObj instanceof Player bukkitPlayer)) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        if (hookedPlayer == null) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return false;
        }

        boolean enabled = hookedPlayer.isPluginEnabled(caller);
        boolean shouldBlock = !enabled && config.bukkitApi().isBlockDisabledKick();
        String reason = serializeKickReason(reasonObj);

        if (config.bukkitApi().isCallKickEvent()) {
            PlayerKickHookEvent hookEvent = new PlayerKickHookEvent(
                    caller,
                    hookedPlayer,
                    reason,
                    reasonObj,
                    enabled,
                    shouldBlock
            );
            Bukkit.getPluginManager().callEvent(hookEvent);
            shouldBlock = hookEvent.isCancelled();
        }

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

        HookedPlayer targetPlayer = resolveTargetPlayerFromCommand(commandLine);
        boolean enabledForTarget = targetPlayer == null || targetPlayer.isPluginEnabled(caller);
        boolean shouldBlock = !enabledForTarget && config.bukkitApi().isBlockDisabledDispatchCommand();

        if (config.bukkitApi().isCallDispatchCommandEvent()) {
            PluginDispatchCommandEvent hookEvent = new PluginDispatchCommandEvent(
                    caller,
                    sender,
                    commandLine,
                    targetPlayer,
                    enabledForTarget,
                    shouldBlock
            );
            Bukkit.getPluginManager().callEvent(hookEvent);
            shouldBlock = hookEvent.isCancelled();
        }

        if (shouldBlock && config.isDebug()) {
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from dispatching command"
                    + (targetPlayer != null ? " targeting " + targetPlayer.getName() : "")
                    + ": /" + commandLine);
        }
        return shouldBlock;
    }

    public boolean handlePlayerTeleport(Object playerObj, Object locationObj, Object causeObj) {
        if (!config.bukkitApi().isBlockDisabledTeleport() || !(playerObj instanceof Player bukkitPlayer)) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        if (hookedPlayer == null) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from teleporting player " + bukkitPlayer.getName()
                        + " (cause: " + causeObj + ")");
            }
            return true;
        }
        return false;
    }

    public boolean handleEntityVelocity(Object entityObj, Object velocityObj) {
        if (!config.bukkitApi().isBlockDisabledVelocity() || !(entityObj instanceof Player bukkitPlayer)) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        if (hookedPlayer == null) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from setting velocity on player " + bukkitPlayer.getName()
                        + " (" + velocityObj + ")");
            }
            return true;
        }
        return false;
    }

    public boolean handlePlayerSendMessage(Object playerObj, Object msgObj) {
        if (!config.bukkitApi().isBlockDisabledSendMessage() || !(playerObj instanceof Player bukkitPlayer)) {
            return false;
        }
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        if (hookedPlayer == null) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
            if (config.isDebug()) {
                logger.info("[Debug] Blocked " + caller.getName()
                        + " from sending message to player " + bukkitPlayer.getName());
            }
            return true;
        }
        return false;
    }

    public boolean handleNmsSendPacket(Object connectionObj, Object packetObj) {
        if (connectionObj == null || !pluginRegistry.hasHookedPlugins()) {
            return false;
        }
        Plugin caller = PluginCallerResolver.resolveDirectNmsCaller(
                pluginRegistry,
                selfClassLoader,
                connectionObj.getClass()
        );
        if (caller == null) {
            return false;
        }
        Optional<MethodHandle> getterOpt = NMS_CONNECTION_PLAYER_GETTER.get(connectionObj.getClass());
        if (getterOpt.isEmpty()) {
            return false;
        }
        try {
            Player bukkitPlayer = (Player) getterOpt.get().invokeExact(connectionObj);
            HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
            if (hookedPlayer != null && !hookedPlayer.isPluginAllowed(caller)) {
                if (config.isDebug()) {
                    String packetName = packetObj != null ? packetObj.getClass().getSimpleName() : "null";
                    logger.info("[Debug] Blocked " + caller.getName()
                            + " from sending NMS packet " + packetName
                            + " to player " + bukkitPlayer.getName());
                }
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // =========================================================================
    // Return-Value Filter Handlers (Player & Entity Lookups)
    // =========================================================================

    @SuppressWarnings("unchecked")
    public Object filterOnlinePlayers(Object returnValue) {
        if (!config.bukkitApi().isHideFromOnlinePlayers()
                || !(returnValue instanceof Collection<?> rawCollection)
                || rawCollection.isEmpty()) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return returnValue;
        }

        return filterPlayerList((Collection<? extends Player>) rawCollection, caller);
    }

    @SuppressWarnings("unchecked")
    public Object filterWorldPlayers(Object returnValue) {
        if (!config.bukkitApi().isHideFromWorldPlayers()
                || !(returnValue instanceof Collection<?> rawCollection)
                || rawCollection.isEmpty()) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return returnValue;
        }

        return filterPlayerList((Collection<? extends Player>) rawCollection, caller);
    }

    public Object filterGetPlayer(Object returnValue) {
        if (!config.bukkitApi().isHideFromGetPlayer() || !(returnValue instanceof Player player)) {
            return returnValue;
        }

        HookedPlayer hookedPlayer = playerRegistry.getPlayer(player);
        if (hookedPlayer == null) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller != null && !hookedPlayer.isPluginAllowed(caller)) {
            return null;
        }
        return returnValue;
    }

    public Object filterEntityCollection(Object returnValue) {
        if (!config.bukkitApi().isHideFromEntityLists()
                || !(returnValue instanceof Collection<?> rawCollection)
                || rawCollection.isEmpty()) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return returnValue;
        }

        List<Object> filtered = new ArrayList<>(rawCollection.size());
        for (Object element : rawCollection) {
            if (element instanceof Player player) {
                HookedPlayer hookedPlayer = playerRegistry.getPlayer(player);
                if (hookedPlayer != null && !hookedPlayer.isPluginAllowed(caller)) {
                    continue;
                }
            }
            filtered.add(element);
        }
        return Collections.unmodifiableList(filtered);
    }

    private List<Player> filterPlayerList(Collection<? extends Player> original, Plugin caller) {
        List<Player> filtered = new ArrayList<>(original.size());
        for (Player player : original) {
            HookedPlayer hookedPlayer = playerRegistry.getPlayer(player);
            if (hookedPlayer == null || hookedPlayer.isPluginAllowed(caller)) {
                filtered.add(player);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    private static String serializeKickReason(Object reasonObj) {
        if (reasonObj == null) {
            return "";
        }
        if (reasonObj instanceof String str) {
            return str;
        }
        MethodHandle mh = getComponentSerializerHandle(reasonObj.getClass().getClassLoader());
        if (mh != null) {
            try {
                return (String) mh.invokeExact(reasonObj);
            } catch (Throwable ignored) {
            }
        }
        return reasonObj.toString();
    }

    private static MethodHandle getComponentSerializerHandle(ClassLoader loader) {
        if (componentSerializerResolved) {
            return componentSerializerHandle;
        }
        synchronized (BukkitApiPatch.class) {
            if (componentSerializerResolved) {
                return componentSerializerHandle;
            }
            try {
                Class<?> serializerClass = Class.forName(
                        "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer",
                        true,
                        loader
                );
                Class<?> componentClass = Class.forName(
                        "net.kyori.adventure.text.Component",
                        true,
                        loader
                );
                Object legacySectionInstance = serializerClass.getMethod("legacySection").invoke(null);
                Method serializeMethod = serializerClass.getMethod("serialize", componentClass);
                componentSerializerHandle = MethodHandles.lookup()
                        .unreflect(serializeMethod)
                        .bindTo(legacySectionInstance)
                        .asType(MethodType.methodType(String.class, Object.class));
            } catch (Throwable ignored) {
                componentSerializerHandle = null;
            }
            componentSerializerResolved = true;
            return componentSerializerHandle;
        }
    }

    private HookedPlayer resolveTargetPlayerFromCommand(String commandLine) {
        String[] tokens = commandLine.split("\\s+");
        if (tokens.length < 2) {
            return null;
        }
        Collection<HookedPlayer> trackedPlayers = playerRegistry.getPlayers();
        if (trackedPlayers.isEmpty()) {
            return null;
        }
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                continue;
            }
            for (HookedPlayer hp : trackedPlayers) {
                if (hp.getName().equalsIgnoreCase(token)) {
                    return hp;
                }
            }
        }
        return null;
    }
}
