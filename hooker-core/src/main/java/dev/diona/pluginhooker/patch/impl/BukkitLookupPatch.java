package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.PluginCallerResolver;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Hooks Bukkit player and entity lookup APIs across {@code CraftServer}, {@code CraftWorld},
 * {@code CraftRegionAccessor}, and {@code CraftEntity} to hide disabled players from hooked plugins.
 */
@RequiredArgsConstructor
public final class BukkitLookupPatch extends ClassPatch {

    private static final ClassDesc COLLECTION_DESC = ClassDesc.of("java.util.Collection");
    private static final ClassDesc LIST_DESC = ClassDesc.of("java.util.List");
    private static final ClassDesc PLAYER_DESC = ClassDesc.of("org.bukkit.entity.Player");
    private static final ClassDesc UUID_DESC = ClassDesc.of("java.util.UUID");

    private static final Set<String> WORLD_ENTITY_LIST_METHODS = Set.of(
            "getEntities",
            "getLivingEntities",
            "getEntitiesByClass",
            "getEntitiesByClasses",
            "getNearbyEntities"
    );

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
                || internalClassName.endsWith("/CraftWorld")
                || internalClassName.endsWith("/CraftRegionAccessor")
                || internalClassName.endsWith("/entity/CraftEntity");
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        if (internalClassName.endsWith("/CraftServer")) {
            return createCraftServerLookupTransform();
        }
        if (internalClassName.endsWith("/entity/CraftEntity")) {
            return createCraftEntityLookupTransform();
        }
        return createCraftWorldLookupTransform();
    }

    private static ClassTransform createCraftServerLookupTransform() {
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
                    return false;
                },
                (mb, element) -> {
                    if (element instanceof CodeModel codeModel) {
                        var methodOpt = codeModel.parent();
                        String name = methodOpt.map(m -> m.methodName().stringValue()).orElse("");
                        ClassDesc returnType = methodOpt.map(m -> m.methodTypeSymbol().returnType()).orElse(COLLECTION_DESC);
                        int hookId = (name.equals("getOnlinePlayers") || name.equals("matchPlayer"))
                                ? HookIds.FILTER_ONLINE_PLAYERS
                                : HookIds.FILTER_GET_PLAYER;
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

    private static ClassTransform createCraftWorldLookupTransform() {
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
                    return WORLD_ENTITY_LIST_METHODS.contains(name)
                            && (returnType.equals(LIST_DESC) || returnType.equals(COLLECTION_DESC));
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

    private static ClassTransform createCraftEntityLookupTransform() {
        return ClassTransform.transformingMethodBodies(
                method -> (method.flags().flagsMask() & ClassFile.ACC_BRIDGE) == 0
                        && method.methodName().equalsString("getNearbyEntities")
                        && method.methodTypeSymbol().parameterCount() == 3
                        && method.methodTypeSymbol().returnType().equals(LIST_DESC),
                CodeTransforms.filterObjectReturn(HookIds.FILTER_ENTITY_COLLECTION, LIST_DESC, ArgSpec.thisRef())
        );
    }

    public Object filterOnlinePlayers(Object returnValue) {
        return filterPlayerCollection(returnValue, config.bukkitApi().isHideFromOnlinePlayers());
    }

    public Object filterWorldPlayers(Object returnValue) {
        return filterPlayerCollection(returnValue, config.bukkitApi().isHideFromWorldPlayers());
    }

    @SuppressWarnings("unchecked")
    private Object filterPlayerCollection(Object returnValue, boolean hideEnabled) {
        if (!hideEnabled
                || !(returnValue instanceof Collection<?> rawCollection)
                || rawCollection.isEmpty()
                || !hasRestrictablePlayer(rawCollection)) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return returnValue;
        }

        return lazyFilterCollection(
                (Collection<? extends Player>) rawCollection,
                player -> isPlayerHiddenFrom(player, caller)
        );
    }

    public Object filterGetPlayer(Object returnValue) {
        if (!config.bukkitApi().isHideFromGetPlayer()
                || !pluginRegistry.hasHookedPlugins()
                || !(returnValue instanceof Player player)) {
            return returnValue;
        }

        HookedPlayer hookedPlayer = playerRegistry.getPlayerFast(player);
        if (hookedPlayer == null
                || !hookedPlayer.hasAnyDisabledPlugin()
                || hookedPlayer.isInJoinGracePeriod()) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller != null && !hookedPlayer.isPluginEnabled(caller)) {
            return null;
        }
        return returnValue;
    }

    public Object filterEntityCollection(Object returnValue) {
        if (!config.bukkitApi().isHideFromEntityLists()
                || !(returnValue instanceof Collection<?> rawCollection)
                || rawCollection.isEmpty()
                || !hasRestrictablePlayer(rawCollection)) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return returnValue;
        }

        return lazyFilterCollection(
                rawCollection,
                element -> element instanceof Player player && isPlayerHiddenFrom(player, caller)
        );
    }

    private boolean hasRestrictablePlayer(Collection<?> collection) {
        if (!playerRegistry.hasAnyPlayerWithDisabledPlugin()) {
            return false;
        }
        if (collection instanceof List<?> list && list instanceof RandomAccess) {
            for (int i = 0, size = list.size(); i < size; i++) {
                if (list.get(i) instanceof Player player && isPlayerRestrictable(player)) {
                    return true;
                }
            }
            return false;
        }
        for (Object element : collection) {
            if (element instanceof Player player && isPlayerRestrictable(player)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlayerRestrictable(Player player) {
        HookedPlayer hookedPlayer = playerRegistry.getPlayerFast(player);
        return hookedPlayer != null
                && hookedPlayer.hasAnyDisabledPlugin()
                && !hookedPlayer.isInJoinGracePeriod();
    }

    private boolean isPlayerHiddenFrom(Player player, Plugin caller) {
        HookedPlayer hookedPlayer = playerRegistry.getPlayerFast(player);
        return hookedPlayer != null
                && !hookedPlayer.isPluginEnabled(caller)
                && !hookedPlayer.isInJoinGracePeriod();
    }

    private static <T> Object lazyFilterCollection(Collection<T> original, Predicate<T> shouldHide) {
        if (original instanceof List<T> list && list instanceof RandomAccess) {
            int size = list.size();
            List<T> filtered = null;
            for (int i = 0; i < size; i++) {
                T element = list.get(i);
                if (shouldHide.test(element)) {
                    if (filtered == null) {
                        filtered = new ArrayList<>(Math.max(0, size - 1));
                        for (int j = 0; j < i; j++) {
                            filtered.add(list.get(j));
                        }
                    }
                } else if (filtered != null) {
                    filtered.add(element);
                }
            }
            return filtered != null ? filtered : original;
        }

        List<T> filtered = null;
        int keptCount = 0;
        for (T element : original) {
            if (shouldHide.test(element)) {
                if (filtered == null) {
                    filtered = new ArrayList<>(Math.max(0, original.size() - 1));
                    if (keptCount > 0) {
                        int k = 0;
                        for (T prev : original) {
                            if (k++ >= keptCount) {
                                break;
                            }
                            filtered.add(prev);
                        }
                    }
                }
            } else if (filtered != null) {
                filtered.add(element);
            } else {
                keptCount++;
            }
        }
        return filtered != null ? filtered : original;
    }
}
