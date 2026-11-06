package dev.diona.pluginhooker.patch.impl;

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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Hooks Bukkit Server & World player lookup APIs ({@code getOnlinePlayers}, {@code getPlayers}, {@code getPlayer})
 * to hide players from hooked plugins when that plugin is disabled for the player.
 */
@RequiredArgsConstructor
public final class BukkitApiPatch extends ClassPatch {

    private static final ClassDesc COLLECTION_DESC = ClassDesc.of("java.util.Collection");
    private static final ClassDesc LIST_DESC = ClassDesc.of("java.util.List");
    private static final ClassDesc PLAYER_DESC = ClassDesc.of("org.bukkit.entity.Player");

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
        if (!internalClassName.startsWith("org/bukkit/craftbukkit/")) {
            return false;
        }
        return internalClassName.endsWith("/CraftServer") || internalClassName.endsWith("/CraftWorld");
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        if (internalClassName.endsWith("/CraftServer")) {
            return ClassTransform.transformingMethods(
                    method -> {
                        if ((method.flags().flagsMask() & java.lang.classfile.ClassFile.ACC_BRIDGE) != 0) {
                            return false;
                        }
                        String name = method.methodName().stringValue();
                        ClassDesc returnType = method.methodTypeSymbol().returnType();
                        if (name.equals("getOnlinePlayers") && method.methodTypeSymbol().parameterCount() == 0) {
                            return returnType.equals(LIST_DESC) || returnType.equals(COLLECTION_DESC);
                        }
                        if ((name.equals("getPlayer") || name.equals("getPlayerExact"))
                                && method.methodTypeSymbol().parameterCount() == 1) {
                            return returnType.equals(PLAYER_DESC);
                        }
                        return false;
                    },
                    (mb, element) -> {
                        if (element instanceof java.lang.classfile.CodeModel codeModel) {
                            var methodOpt = codeModel.parent();
                            String name = methodOpt.map(m -> m.methodName().stringValue()).orElse("");
                            ClassDesc returnType = methodOpt.map(m -> m.methodTypeSymbol().returnType()).orElse(COLLECTION_DESC);
                            if (name.equals("getOnlinePlayers")) {
                                mb.transformCode(
                                        codeModel,
                                        CodeTransforms.filterObjectReturn(
                                                HookIds.FILTER_ONLINE_PLAYERS,
                                                returnType,
                                                ArgSpec.thisRef()
                                        )
                                );
                            } else {
                                mb.transformCode(
                                        codeModel,
                                        CodeTransforms.filterObjectReturn(
                                                HookIds.FILTER_GET_PLAYER,
                                                PLAYER_DESC,
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

        // CraftWorld#getPlayers()
        return ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("getPlayers")
                        && method.methodTypeSymbol().parameterCount() == 0
                        && method.methodTypeSymbol().returnType().equals(LIST_DESC),
                CodeTransforms.filterObjectReturn(
                        HookIds.FILTER_WORLD_PLAYERS,
                        LIST_DESC,
                        ArgSpec.thisRef()
                )
        );
    }

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
                || !(returnValue instanceof List<?> rawList)
                || rawList.isEmpty()) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return returnValue;
        }

        return filterPlayerList((Collection<? extends Player>) rawList, caller);
    }

    public Object filterGetPlayer(Object returnValue) {
        if (!config.bukkitApi().isHideFromGetPlayer() || !(returnValue instanceof Player player)) {
            return returnValue;
        }

        Plugin caller = PluginCallerResolver.resolveHookedCaller(pluginRegistry, selfClassLoader);
        if (caller == null) {
            return returnValue;
        }

        HookedPlayer hookedPlayer = playerRegistry.getPlayer(player);
        if (hookedPlayer != null && !hookedPlayer.isPluginEnabled(caller)) {
            return null;
        }
        return returnValue;
    }

    private List<Player> filterPlayerList(Collection<? extends Player> original, Plugin caller) {
        List<Player> filtered = new ArrayList<>(original.size());
        for (Player player : original) {
            HookedPlayer hookedPlayer = playerRegistry.getPlayer(player);
            if (hookedPlayer == null || hookedPlayer.isPluginEnabled(caller)) {
                filtered.add(player);
            }
        }
        return Collections.unmodifiableList(filtered);
    }
}
