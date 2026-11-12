package dev.diona.pluginhooker.agent;

import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.HookIds;
import dev.diona.pluginhooker.patch.impl.BukkitApiPatch;
import dev.diona.pluginhooker.patch.impl.BukkitEventPatch;
import dev.diona.pluginhooker.patch.impl.BukkitLookupPatch;
import dev.diona.pluginhooker.patch.impl.NettyPipelinePatch;
import dev.diona.pluginhooker.patch.impl.NmsConnectionPatch;
import dev.diona.pluginhooker.patch.impl.PacketEventsPatch;
import dev.diona.pluginhooker.patch.impl.ProtocolLibPatch;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.LoaderClassSet;
import lombok.Getter;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates the JVM instrumentation, Bootstrap bridge binding, and {@link ClassFileTransformer} lifecycle.
 */
public final class PatchManager {

    private final Logger logger;
    private final BukkitEventPatch bukkitEventPatch;
    private final BukkitLookupPatch bukkitLookupPatch;
    private final BukkitApiPatch bukkitApiPatch;
    private final NmsConnectionPatch nmsConnectionPatch;
    private final ProtocolLibPatch protocolLibPatch;
    private final PacketEventsPatch packetEventsPatch;
    private final NettyPipelinePatch nettyPipelinePatch;
    @Getter
    private final List<ClassPatch> patches;
    private final LoaderClassSet transformedClasses = new LoaderClassSet();

    private Instrumentation instrumentation;
    private ClassFileTransformer activeTransformer;

    public PatchManager(
            Logger logger,
            HookConfig config,
            PluginRegistryImpl pluginRegistry,
            PlayerRegistryImpl playerRegistry,
            ClassLoader selfClassLoader
    ) {
        this.logger = logger;
        this.bukkitEventPatch = new BukkitEventPatch(config, pluginRegistry, playerRegistry, selfClassLoader);
        this.bukkitLookupPatch = new BukkitLookupPatch(config, pluginRegistry, playerRegistry, selfClassLoader);
        this.bukkitApiPatch = new BukkitApiPatch(logger, config, pluginRegistry, playerRegistry, selfClassLoader);
        this.nmsConnectionPatch = new NmsConnectionPatch(logger, config, pluginRegistry, playerRegistry, selfClassLoader);
        this.protocolLibPatch = new ProtocolLibPatch(logger, config, pluginRegistry, playerRegistry, selfClassLoader);
        this.packetEventsPatch = new PacketEventsPatch(logger, config, pluginRegistry, playerRegistry, selfClassLoader);
        this.nettyPipelinePatch = new NettyPipelinePatch(logger, config, pluginRegistry, playerRegistry, selfClassLoader);

        this.patches = List.of(
                bukkitEventPatch,
                bukkitLookupPatch,
                bukkitApiPatch,
                nmsConnectionPatch,
                protocolLibPatch,
                packetEventsPatch,
                nettyPipelinePatch
        );
    }

    public void initialize() {
        try {
            this.instrumentation = AgentInstaller.install();

            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle guardMh = lookup.findVirtual(
                    PatchManager.class,
                    "dispatchGuard",
                    MethodType.methodType(boolean.class, int.class, Object.class, Object.class, Object.class)
            ).bindTo(this);

            MethodHandle filterMh = lookup.findVirtual(
                    PatchManager.class,
                    "dispatchFilter",
                    MethodType.methodType(Object.class, Object.class, int.class, Object.class)
            ).bindTo(this);

            MethodHandle notifyMh = lookup.findVirtual(
                    PatchManager.class,
                    "dispatchNotify",
                    MethodType.methodType(void.class, int.class, Object.class, Object.class)
            ).bindTo(this);

            MethodHandle nettyMh = lookup.findVirtual(
                    PatchManager.class,
                    "dispatchNetty",
                    MethodType.methodType(void.class, int.class, Object.class, Object.class, Object.class, Object.class)
            ).bindTo(this);

            BridgeInjector.injectAndBind(instrumentation, guardMh, filterMh, notifyMh, nettyMh);

            this.activeTransformer = new HookClassFileTransformer();
            instrumentation.addTransformer(activeTransformer, true);

            retransformAlreadyLoadedClasses();
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Failed to initialize bytecode instrumentation", t);
        }
    }

    public void shutdown() {
        if (instrumentation != null && activeTransformer != null) {
            instrumentation.removeTransformer(activeTransformer);
            activeTransformer = null;
            for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                if (!instrumentation.isModifiableClass(loadedClass)) {
                    continue;
                }
                String internalName = loadedClass.getName().replace('.', '/');
                if (transformedClasses.remove(loadedClass.getClassLoader(), internalName)) {
                    try {
                        instrumentation.retransformClasses(loadedClass);
                    } catch (Throwable t) {
                        logger.log(Level.WARNING, "Failed to revert transformed class " + loadedClass.getName(), t);
                    }
                }
            }
            transformedClasses.clear();
            patches.forEach(ClassPatch::reset);
        }
        BridgeInjector.unbind();
    }

    public void reloadPatches() {
        if (instrumentation != null && activeTransformer != null) {
            retransformAlreadyLoadedClasses();
        }
    }

    public boolean isClassTransformed(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return transformedClasses.contains(clazz.getClassLoader(), clazz.getName().replace('.', '/'));
    }

    private void retransformAlreadyLoadedClasses() {
        List<Class<?>> toRetransform = new ArrayList<>();
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (!instrumentation.isModifiableClass(loadedClass)) {
                continue;
            }
            String internalName = loadedClass.getName().replace('.', '/');
            if (transformedClasses.contains(loadedClass.getClassLoader(), internalName)) {
                continue;
            }
            for (ClassPatch patch : patches) {
                if (patch.isEnabled() && (patch.matchesClass(internalName) || patch.matchesLoadedClass(loadedClass))) {
                    toRetransform.add(loadedClass);
                    break;
                }
            }
        }

        for (Class<?> clazz : toRetransform) {
            try {
                instrumentation.retransformClasses(clazz);
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Failed to retransform loaded class " + clazz.getName(), t);
            }
        }
    }

    public boolean dispatchGuard(int hookId, Object arg0, Object arg1, Object arg2) {
        return switch (hookId) {
            case HookIds.GUARD_BUKKIT_EVENT -> bukkitEventPatch.handleCallEvent(arg0, arg1);
            case HookIds.GUARD_PROTOCOLLIB_INBOUND -> protocolLibPatch.handlePacket(arg0, arg1, false);
            case HookIds.GUARD_PROTOCOLLIB_OUTBOUND -> protocolLibPatch.handlePacket(arg0, arg1, true);
            case HookIds.GUARD_PACKETEVENTS_RECEIVE -> packetEventsPatch.handlePacketEvent(arg0, arg1, false);
            case HookIds.GUARD_PACKETEVENTS_SEND -> packetEventsPatch.handlePacketEvent(arg0, arg1, true);
            case HookIds.GUARD_PLAYER_KICK -> bukkitApiPatch.handlePlayerKick(arg0, arg1);
            case HookIds.GUARD_DISPATCH_COMMAND -> bukkitApiPatch.handleDispatchCommand(arg0, arg1);
            case HookIds.GUARD_PLAYER_TELEPORT -> bukkitApiPatch.handlePlayerTeleport(arg0, arg1, arg2);
            case HookIds.GUARD_ENTITY_VELOCITY -> bukkitApiPatch.handleEntityVelocity(arg0, arg1);
            case HookIds.GUARD_PLAYER_SEND_MESSAGE -> bukkitApiPatch.handlePlayerSendMessage(arg0, arg1);
            case HookIds.GUARD_PLAYER_CLOSE_INVENTORY -> bukkitApiPatch.handlePlayerCloseInventory(arg0);
            case HookIds.GUARD_NMS_SEND_PACKET -> nmsConnectionPatch.handleSendPacket(arg0, arg1);
            case HookIds.GUARD_NMS_DISCONNECT -> nmsConnectionPatch.handleDisconnect(arg0, arg1);
            case HookIds.GUARD_PROTOCOLLIB_SEND_PACKET -> protocolLibPatch.handleSendOrReceivePacket(arg0, arg1);
            case HookIds.GUARD_PACKETEVENTS_SEND_PACKET -> packetEventsPatch.handleSendPacket(arg0, arg1, arg2);
            case HookIds.GUARD_NETTY_WRITE_BYTEBUF -> nettyPipelinePatch.handleWriteByteBuf(arg0, arg1, arg2, false);
            case HookIds.GUARD_NETTY_WRITE_BYTEBUF_TAIL -> nettyPipelinePatch.handleWriteByteBuf(arg0, arg1, arg2, true);
            case HookIds.GUARD_NETTY_CLOSE -> nettyPipelinePatch.handleChannelClose(arg0, arg1);
            default -> false;
        };
    }

    public Object dispatchFilter(Object returnValue, int hookId, Object receiver) {
        return switch (hookId) {
            case HookIds.FILTER_ONLINE_PLAYERS -> bukkitLookupPatch.filterOnlinePlayers(returnValue);
            case HookIds.FILTER_WORLD_PLAYERS -> bukkitLookupPatch.filterWorldPlayers(returnValue);
            case HookIds.FILTER_GET_PLAYER -> bukkitLookupPatch.filterGetPlayer(returnValue);
            case HookIds.FILTER_ENTITY_COLLECTION -> bukkitLookupPatch.filterEntityCollection(returnValue);
            default -> returnValue;
        };
    }

    public void dispatchNotify(int hookId, Object arg0, Object arg1) {
        switch (hookId) {
            case HookIds.NOTIFY_NETTY_HANDLER_ADD -> nettyPipelinePatch.handlePipelineAdd(arg0);
            case HookIds.NOTIFY_PACKETEVENTS_REGISTER -> packetEventsPatch.handleListenerRegister(arg0);
            case HookIds.NOTIFY_PACKETEVENTS_UNREGISTER -> packetEventsPatch.handleListenerUnregister(arg0);
            case HookIds.NOTIFY_PACKETEVENTS_UNREGISTER_ALL -> packetEventsPatch.handleUnregisterAllListeners(arg0);
            case HookIds.NOTIFY_NMS_PLAYER_CONNECTION_INIT -> nmsConnectionPatch.handleConnectionInit(arg0);
            default -> {
            }
        }
    }

    public void dispatchNetty(int isOutbound, Object handler, Object ctx, Object msg, Object promise) throws Throwable {
        nettyPipelinePatch.handleNettyDispatch(isOutbound, handler, ctx, msg, promise);
    }

    private final class HookClassFileTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer
        ) {
            if (className == null || classfileBuffer == null) {
                return null;
            }
            byte[] currentBytes = classfileBuffer;
            boolean anyMatched = false;
            for (ClassPatch patch : patches) {
                if (!patch.isEnabled()) {
                    continue;
                }
                boolean matched;
                try {
                    matched = patch.matchesClass(className)
                            || patch.matchesClassFile(loader, className, currentBytes);
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "Error inspecting class " + className + " for "
                            + patch.getClass().getSimpleName(), t);
                    continue;
                }
                if (!matched) {
                    continue;
                }
                if (!anyMatched) {
                    if (!transformedClasses.add(loader, className) && classBeingRedefined == null) {
                        return null;
                    }
                    anyMatched = true;
                }
                try {
                    currentBytes = patch.transform(loader, className, currentBytes);
                } catch (Throwable t) {
                    transformedClasses.remove(loader, className);
                    logger.log(Level.SEVERE, "Error transforming class " + className, t);
                    return null;
                }
            }
            if (anyMatched) {
                logger.info("Hooked class: " + className.replace('/', '.'));
                return currentBytes;
            }
            return null;
        }
    }
}
