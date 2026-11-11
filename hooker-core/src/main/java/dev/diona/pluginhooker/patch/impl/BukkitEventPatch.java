package dev.diona.pluginhooker.patch.impl;

import dev.diona.pluginhooker.api.event.BukkitHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.CodeTransforms;
import dev.diona.pluginhooker.patch.CodeTransforms.ArgSpec;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.service.HookedPlayerImpl;
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

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Hooks {@code org.bukkit.plugin.RegisteredListener#callEvent(Event)} to sandbox Bukkit event listeners per player,
 * and the {@code callEvent} of subclasses that override it without calling it, such as Intave's.
 */
@RequiredArgsConstructor
public final class BukkitEventPatch extends ClassPatch {

    private static final String TARGET_CLASS = "org/bukkit/plugin/RegisteredListener";
    private static final byte[] TARGET_CLASS_UTF8 = TARGET_CLASS.getBytes(StandardCharsets.UTF_8);
    private static final String CALL_EVENT = "callEvent";
    private static final String CALL_EVENT_TYPE = "(Lorg/bukkit/event/Event;)V";
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
    public boolean matchesClassFile(String internalClassName, byte[] classfileBuffer) {
        // Most classes never name RegisteredListener, and finding that out needs no parsing
        if (!contains(classfileBuffer, TARGET_CLASS_UTF8)) {
            return false;
        }
        ClassModel model = ClassFile.of().parse(classfileBuffer);
        boolean subclass = model.superclass()
                .map(superclass -> superclass.asInternalName().equals(TARGET_CLASS))
                .orElse(false);
        return subclass && model.methods().stream()
                .filter(BukkitEventPatch::isCallEvent)
                .anyMatch(method -> method.code().isPresent() && !callsBaseCallEvent(method));
    }

    @Override
    public boolean matchesLoadedClass(Class<?> loadedClass) {
        Class<?> superclass = loadedClass.getSuperclass();
        return superclass != null && superclass.getName().replace('.', '/').equals(TARGET_CLASS);
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        ArgSpec plugin = TARGET_CLASS.equals(internalClassName)
                ? ArgSpec.getThisField(REGISTERED_LISTENER_DESC, "plugin", PLUGIN_DESC)
                // The field is private to RegisteredListener
                : ArgSpec.invokeThisGetter(REGISTERED_LISTENER_DESC, "getPlugin", PLUGIN_DESC);
        return ClassTransform.transformingMethodBodies(
                BukkitEventPatch::isCallEvent,
                CodeTransforms.guardVoidEntry(HookIds.GUARD_BUKKIT_EVENT, plugin, ArgSpec.param(0), ArgSpec.nullConst())
        );
    }

    private static boolean isCallEvent(MethodModel method) {
        return method.methodName().equalsString(CALL_EVENT) && method.methodType().equalsString(CALL_EVENT_TYPE);
    }

    /** An override that calls {@code super.callEvent} goes through the hooked base method already. */
    private static boolean callsBaseCallEvent(MethodModel method) {
        return method.code().orElseThrow().elementStream().anyMatch(element ->
                element instanceof InvokeInstruction invoke
                        && invoke.opcode() == Opcode.INVOKESPECIAL
                        && invoke.owner().asInternalName().equals(TARGET_CLASS)
                        && invoke.name().equalsString(CALL_EVENT)
        );
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Returns true if the event execution for this listener should be blocked.
     */
    public boolean handleCallEvent(Object pluginObj, Object eventObj) {
        if (!pluginRegistry.hasHookedPlugins()
                || !(pluginObj instanceof Plugin plugin)
                || !(eventObj instanceof Event event)) {
            return false;
        }
        HookMode mode = pluginRegistry.getHookMode(plugin);
        if (mode == null) {
            return false;
        }
        boolean callEvent = config.bukkit().isCallEvent();
        if (mode == HookMode.BLACKLIST && !callEvent && !playerRegistry.hasPlayersWithDisabledOverrides()) {
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

        if (hookedPlayer != null && !HookedPlayerImpl.isAllowed(hookedPlayer, plugin, mode)) {
            return true;
        }
        return BukkitEvents.fireCancellable(
                callEvent,
                false,
                () -> new BukkitHookEvent(plugin, event, hookedPlayer)
        );
    }
}
