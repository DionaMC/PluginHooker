package dev.diona.pluginhooker.patch.impl;

import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import dev.diona.pluginhooker.api.event.PacketEventsHookEvent;
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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Hooks PacketEvents' {@code EventManager}, {@code PacketReceiveEvent}, and {@code PacketSendEvent}.
 * Supports both standalone PacketEvents and shaded/relocated PacketEvents instances.
 */
@RequiredArgsConstructor
public final class PacketEventsPatch extends ClassPatch {

    private static final String EVENT_MANAGER_SUFFIX = "packetevents/event/EventManager";
    private static final String PACKET_RECEIVE_SUFFIX = "packetevents/event/PacketReceiveEvent";
    private static final String PACKET_SEND_SUFFIX = "packetevents/event/PacketSendEvent";

    private static final Set<String> IGNORED_EVENT_SIMPLE_NAMES = Set.of(
            "PacketHandshakeReceiveEvent",
            "PacketStatusReceiveEvent",
            "PacketLoginReceiveEvent",
            "PacketConfigReceiveEvent",
            "PacketHandshakeSendEvent",
            "PacketStatusSendEvent",
            "PacketLoginSendEvent",
            "PacketConfigSendEvent"
    );

    private static final ClassValue<Optional<MethodHandle>> GET_PLAYER_HANDLE = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> eventClass) {
            try {
                Method m = eventClass.getMethod("getPlayer");
                m.setAccessible(true);
                return Optional.of(
                        MethodHandles.lookup()
                                .unreflect(m)
                                .asType(MethodType.methodType(Object.class, Object.class))
                );
            } catch (ReflectiveOperationException e) {
                return Optional.empty();
            }
        }
    };

    private final HookConfig config;
    private final PluginRegistry pluginRegistry;
    private final PlayerRegistry playerRegistry;
    private final ClassLoader selfClassLoader;

    private final Map<Object, Plugin> listenerToPlugin =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public boolean isEnabled() {
        return config.packetEvents().isEnabled();
    }

    @Override
    public boolean matchesClass(String internalClassName) {
        return internalClassName.endsWith(EVENT_MANAGER_SUFFIX)
                || internalClassName.endsWith(PACKET_RECEIVE_SUFFIX)
                || internalClassName.endsWith(PACKET_SEND_SUFFIX);
    }

    @Override
    protected ClassTransform createTransform(String internalClassName, ClassModel classModel) {
        if (internalClassName.endsWith(EVENT_MANAGER_SUFFIX)) {
            return ClassTransform.transformingMethods(
                    method -> {
                        String name = method.methodName().stringValue();
                        return (name.equals("registerListenerNoRecalculation")
                                || name.equals("unregisterListenerNoRecalculation"))
                                && method.methodTypeSymbol().parameterCount() == 1;
                    },
                    (mb, element) -> {
                        if (element instanceof CodeModel codeModel) {
                            String name = codeModel.parent()
                                    .map(m -> m.methodName().stringValue())
                                    .orElse("");
                            int hookId = name.equals("registerListenerNoRecalculation")
                                    ? HookIds.NOTIFY_PACKETEVENTS_REGISTER
                                    : HookIds.NOTIFY_PACKETEVENTS_UNREGISTER;
                            mb.transformCode(
                                    codeModel,
                                    CodeTransforms.notifyAtEntry(
                                            hookId,
                                            ArgSpec.param(0),
                                            ArgSpec.nullConst()
                                    )
                            );
                        } else {
                            mb.with(element);
                        }
                    }
            );
        }

        int hookId = internalClassName.endsWith(PACKET_SEND_SUFFIX)
                ? HookIds.GUARD_PACKETEVENTS_SEND
                : HookIds.GUARD_PACKETEVENTS_RECEIVE;

        return ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString("call")
                        && method.methodTypeSymbol().parameterCount() == 1,
                CodeTransforms.guardVoidEntry(
                        hookId,
                        ArgSpec.param(0),
                        ArgSpec.thisRef(),
                        ArgSpec.nullConst()
                )
        );
    }

    public void handleListenerRegister(Object listener) {
        if (listener == null) {
            return;
        }
        Plugin owner = pluginRegistry.getPluginByClassLoader(listener.getClass().getClassLoader());
        if (owner == null || owner.getName().equalsIgnoreCase("packetevents")) {
            owner = PluginCallerResolver.resolveCallerPlugin(pluginRegistry, selfClassLoader);
        }
        if (owner != null) {
            listenerToPlugin.put(listener, owner);
        }
    }

    public void handleListenerUnregister(Object listener) {
        if (listener != null) {
            listenerToPlugin.remove(listener);
        }
    }

    public boolean handlePacketEvent(Object listener, Object event, boolean outbound) {
        if (listener == null || event == null) {
            return false;
        }
        if (IGNORED_EVENT_SIMPLE_NAMES.contains(event.getClass().getSimpleName())) {
            return false;
        }

        Plugin plugin = listenerToPlugin.get(listener);
        if (plugin == null) {
            plugin = pluginRegistry.getPluginByClassLoader(listener.getClass().getClassLoader());
        }
        if (plugin == null || !pluginRegistry.isHooked(plugin)) {
            return false;
        }

        Player bukkitPlayer = extractPlayer(event);
        if (bukkitPlayer == null) {
            return false;
        }

        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        if (hookedPlayer == null) {
            return false;
        }

        if (!hookedPlayer.isPluginAllowed(plugin)) {
            return true;
        }

        if (config.packetEvents().isCallEvent()
                && listener instanceof PacketListenerCommon commonListener
                && event instanceof PacketEvent packetEvent) {
            PacketEventsHookEvent hookEvent = new PacketEventsHookEvent(
                    plugin,
                    hookedPlayer,
                    commonListener,
                    packetEvent,
                    outbound
            );
            Bukkit.getPluginManager().callEvent(hookEvent);
            return hookEvent.isCancelled();
        }
        return false;
    }

    private static Player extractPlayer(Object event) {
        Optional<MethodHandle> mhOpt = GET_PLAYER_HANDLE.get(event.getClass());
        if (mhOpt.isEmpty()) {
            return null;
        }
        try {
            Object playerObj = mhOpt.get().invokeExact(event);
            return playerObj instanceof Player p ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
