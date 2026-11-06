package dev.diona.pluginhooker;

import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.patch.ClassPatch;
import dev.diona.pluginhooker.patch.impl.BukkitApiPatch;
import dev.diona.pluginhooker.patch.impl.BukkitEventPatch;
import dev.diona.pluginhooker.patch.impl.NettyPipelinePatch;
import dev.diona.pluginhooker.patch.impl.PacketEventsPatch;
import dev.diona.pluginhooker.patch.impl.ProtocolLibPatch;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.util.List;
import java.util.logging.Logger;

/**
 * Verifies that all ClassPatch transformations produce valid JVM bytecode via JDK 25's ClassFile verifier
 * and tests live Instrumentation + Bootstrap HookBridge injection.
 */
public final class PatchVerifier {

    private static final List<String> TARGET_CLASSES = List.of(
            "org/bukkit/plugin/RegisteredListener",
            "org/bukkit/craftbukkit/v1_8_R3/CraftServer",
            "org/bukkit/craftbukkit/v1_8_R3/CraftWorld",
            "io/netty/channel/DefaultChannelPipeline",
            "io/netty/channel/AbstractChannelHandlerContext",
            "com/comphenix/protocol/injector/collection/InboundPacketListenerSet",
            "com/comphenix/protocol/injector/collection/OutboundPacketListenerSet",
            "com/github/retrooper/packetevents/event/EventManager",
            "com/github/retrooper/packetevents/event/PacketReceiveEvent",
            "com/github/retrooper/packetevents/event/PacketSendEvent"
    );

    public static void main(String[] args) throws Exception {
        Logger logger = Logger.getLogger("PatchVerifier");
        HookConfig config = new HookConfig();
        PluginRegistryImpl pluginRegistry = new PluginRegistryImpl(config);
        PlayerRegistryImpl playerRegistry = new PlayerRegistryImpl(pluginRegistry, config);
        ClassLoader cl = PatchVerifier.class.getClassLoader();

        // 1. Test live agent & bootstrap bridge injection
        PatchManager patchManager = new PatchManager(logger, config, pluginRegistry, playerRegistry, cl);
        patchManager.initialize();

        Class<?> bootstrapBridge = Class.forName("dev.diona.pluginhooker.bridge.HookBridge", true, null);
        if (bootstrapBridge.getClassLoader() != null) {
            throw new AssertionError("Expected HookBridge to be loaded by Bootstrap ClassLoader (null)");
        }
        logger.info("Verified HookBridge in Bootstrap ClassLoader!");

        // 2. Test transforming and verifying all 10 target classes
        List<ClassPatch> patches = List.of(
                new BukkitEventPatch(config, pluginRegistry, playerRegistry, cl),
                new BukkitApiPatch(config, pluginRegistry, playerRegistry, cl),
                new ProtocolLibPatch(config, pluginRegistry, playerRegistry),
                new PacketEventsPatch(config, pluginRegistry, playerRegistry, cl),
                new NettyPipelinePatch(config, pluginRegistry, playerRegistry, cl)
        );

        ClassHierarchyResolver resolver = ClassHierarchyResolver.ofResourceParsing(cl)
                .orElse(ClassHierarchyResolver.ofClassLoading(cl))
                .orElse(ClassHierarchyResolver.defaultResolver())
                .cached();
        ClassFile verifierCf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));

        for (String internalName : TARGET_CLASSES) {
            byte[] originalBytes;
            try (InputStream in = cl.getResourceAsStream(internalName + ".class")) {
                if (in == null) {
                    throw new IllegalStateException("Missing class resource: " + internalName);
                }
                originalBytes = in.readAllBytes();
            }

            boolean matched = false;
            for (ClassPatch patch : patches) {
                if (patch.matchesClass(internalName)) {
                    matched = true;
                    byte[] transformed = patch.transform(cl, internalName, originalBytes);
                    List<VerifyError> errors = verifierCf.verify(transformed);
                    if (!errors.isEmpty()) {
                        throw new AssertionError("Bytecode verification failed for " + internalName + ": " + errors);
                    }
                    if (transformed.length == originalBytes.length) {
                        throw new AssertionError("Class " + internalName + " was not modified by patch!");
                    }
                    logger.info("Successfully transformed & verified bytecode: " + internalName
                            + " (" + originalBytes.length + " -> " + transformed.length + " bytes)");
                }
            }
            if (!matched) {
                throw new AssertionError("No patch matched target class: " + internalName);
            }
        }

        patchManager.shutdown();
        logger.info("ALL 10 TARGET CLASSES PASSED JDK 25 BYTECODE VERIFICATION!");
    }
}
