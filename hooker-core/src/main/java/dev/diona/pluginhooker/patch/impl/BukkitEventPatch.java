package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.BukkitHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.BukkitEvents;
import dev.diona.pluginhooker.util.EventPlayerResolver;
import lombok.RequiredArgsConstructor;
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
import java.util.Set;

/**
 * Hooks {@code org.bukkit.plugin.RegisteredListener#callEvent(Event)} to sandbox Bukkit event listeners per player.
 */
@RequiredArgsConstructor
public final class BukkitEventPatch extends ClassPatch {

    private static final String TARGET_CLASS = "org/bukkit/plugin/RegisteredListener";
    private static final ClassDesc REGISTERED_LISTENER_DESC = ClassDesc.of("org.bukkit.plugin.RegisteredListener");
    private static final ClassDesc PLUGIN_DESC = ClassDesc.of("org.bukkit.plugin.Plugin");

    private static final Set<String> WHITELISTED_LIFECYCLE_EVENTS = Set.of(
            "PlayerSpawnLocationEvent",
            "AsyncPlayerSpawnLocationEvent",
            "PlayerInitialSpawnEvent",
            "PlayerConnectionValidateLoginEvent",
            "AsyncPlayerConnectionConfigureEvent",
            "PlayerConnectionInitialConfigureEvent",
            "PlayerConnectionReconfigureEvent",
            "PlayerConnectionCloseEvent",
            "PlayerHandshakeEvent",
            "PlayerServerFullCheckEvent"
    );

    private static final ClassValue<Boolean> WHITELISTED_EVENT_CACHE = new ClassValue<>() {
        @Override
        @SuppressWarnings("deprecation")
        protected Boolean computeValue(Class<?> eventClass) {
            return AsyncPlayerPreLoginEvent.class.isAssignableFrom(eventClass)
                    || PlayerPreLoginEvent.class.isAssignableFrom(eventClass)
                    || PlayerLoginEvent.class.isAssignableFrom(eventClass)
                    || PlayerJoinEvent.class.isAssignableFrom(eventClass)
                    || PlayerQuitEvent.class.isAssignableFrom(eventClass)
                    || WHITELISTED_LIFECYCLE_EVENTS.contains(eventClass.getSimpleName());
        }
    };

    private final HookConfig config;
    private final PluginRegistryImpl pluginRegistry;
    private final PlayerRegistryImpl playerRegistry;
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
    public boolean handleCallEvent(Object pluginObj, Object eventObj) {
        if (!(pluginObj instanceof Plugin plugin)
                || !pluginRegistry.isHooked(plugin)
                || !(eventObj instanceof Event event)) {
            return false;
        }

        Class<?> eventClass = event.getClass();
        // Always allow connection lifecycle events so plugins can create/remove their internal PlayerData,
        // and ignore PluginHooker's own hook events
        if (eventClass.getClassLoader() == selfClassLoader || WHITELISTED_EVENT_CACHE.get(eventClass)) {
            return false;
        }

        Player bukkitPlayer = EventPlayerResolver.resolvePlayer(
                event,
                config.bukkit().isUseReflectionToGetPlayer()
        );
        HookedPlayer hookedPlayer = playerRegistry.getPlayerFast(bukkitPlayer);

        if (hookedPlayer != null && !hookedPlayer.isPluginEnabled(plugin)) {
            return true;
        }
        return BukkitEvents.fireCancellable(
                config.bukkit().isCallEvent(),
                false,
                () -> new BukkitHookEvent(plugin, event, hookedPlayer)
        );
    }
}
