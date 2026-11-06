package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.BukkitHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.util.EventPlayerResolver;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;

/**
 * Hooks {@code org.bukkit.plugin.RegisteredListener#callEvent(Event)} to sandbox Bukkit event listeners per player.
 */
@RequiredArgsConstructor
public final class BukkitEventPatch extends ClassPatch {

    private static final String TARGET_CLASS = "org/bukkit/plugin/RegisteredListener";
    private static final ClassDesc REGISTERED_LISTENER_DESC = ClassDesc.of("org.bukkit.plugin.RegisteredListener");
    private static final ClassDesc PLUGIN_DESC = ClassDesc.of("org.bukkit.plugin.Plugin");

    private final HookConfig config;
    private final PluginRegistry pluginRegistry;
    private final PlayerRegistry playerRegistry;
    private final ClassLoader selfClassLoader;

    @Override
    public boolean isEnabled() {
        return config.bukkit().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        return TARGET_CLASS.equals(internalClassName);
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        return ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("callEvent")
                        && method.methodType().equalsString("(Lorg/bukkit/event/Event;)V"),
                CodeTransforms.guardVoidEntry(
                        HookIds.GUARD_BUKKIT_EVENT,
                        ArgSpec.getThisField(REGISTERED_LISTENER_DESC, "plugin", PLUGIN_DESC),
                        ArgSpec.param(0),
                        ArgSpec.nullConst()
                )
        );
    }

    /**
     * Returns true if the event execution for this listener should be blocked.
     */
    @SuppressWarnings("deprecation")
    public boolean handleCallEvent(Object pluginObj, Object eventObj) {
        if (!(pluginObj instanceof Plugin plugin) || !(eventObj instanceof Event event)) {
            return false;
        }

        // Always allow connection lifecycle events so plugins can create/remove their internal PlayerData
        if (event instanceof AsyncPlayerPreLoginEvent
                || event instanceof PlayerPreLoginEvent
                || event instanceof PlayerLoginEvent
                || event instanceof PlayerJoinEvent
                || event instanceof PlayerQuitEvent) {
            return false;
        }

        // Ignore PluginHooker's own hook events
        if (event.getClass().getClassLoader() == selfClassLoader) {
            return false;
        }

        if (!pluginRegistry.isHooked(plugin)) {
            return false;
        }

        Player bukkitPlayer = EventPlayerResolver.resolvePlayer(
                event,
                config.bukkit().isUseReflectionToGetPlayer()
        );
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);

        if (hookedPlayer == null) {
            if (!config.bukkit().isCallEvent()) {
                return false;
            }
            BukkitHookEvent hookEvent = new BukkitHookEvent(plugin, event, null);
            Bukkit.getPluginManager().callEvent(hookEvent);
            return hookEvent.isCancelled();
        }

        if (!hookedPlayer.isPluginEnabled(plugin)) {
            return true;
        }

        if (config.bukkit().isCallEvent()) {
            BukkitHookEvent hookEvent = new BukkitHookEvent(plugin, event, hookedPlayer);
            Bukkit.getPluginManager().callEvent(hookEvent);
            return hookEvent.isCancelled();
        }
        return false;
    }
}
