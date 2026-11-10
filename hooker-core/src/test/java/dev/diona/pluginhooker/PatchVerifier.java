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

import java.io.File;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ConstantDescs;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that all ClassPatch transformations produce valid JVM bytecode via JDK 25's ClassFile verifier
 * across both Spigot 1.8.8 (Netty 4.0) and Paper 26.3 (Netty 4.2) classes.
 * Automatically downloads and unpacks Paper 26.3 via PaperMC's Fill v3 API + {@code -Dpaperclip.patchonly=true}
 * into the Gradle cache when not already cached.
 */
public final class PatchVerifier {

    private static final String PAPER_VERSION = "26.3";
    private static final String PAPER_FILL_API =
            "https://fill.papermc.io/v3/projects/paper/versions/" + PAPER_VERSION + "/builds/latest";
    private static final Pattern DOWNLOAD_URL_PATTERN =
            Pattern.compile("\"url\"\\s*:\\s*\"(https://fill-data\\.papermc\\.io/[^\"]+\\.jar)\"");

    private static final List<String> SPIGOT_1_8_TARGETS = List.of(
            "org/bukkit/plugin/RegisteredListener",
            "org/bukkit/craftbukkit/v1_8_R3/CraftServer",
            "org/bukkit/craftbukkit/v1_8_R3/CraftWorld",
            "org/bukkit/craftbukkit/v1_8_R3/entity/CraftPlayer",
            "org/bukkit/craftbukkit/v1_8_R3/entity/CraftPlayer$1",
            "org/bukkit/craftbukkit/v1_8_R3/entity/CraftEntity",
            "net/minecraft/server/v1_8_R3/PlayerConnection",
            "io/netty/channel/DefaultChannelPipeline",
            "io/netty/channel/AbstractChannelHandlerContext",
            "com/comphenix/protocol/injector/collection/InboundPacketListenerSet",
            "com/comphenix/protocol/injector/collection/OutboundPacketListenerSet",
            "com/comphenix/protocol/async/AsyncListenerHandler",
            "com/comphenix/protocol/injector/PacketFilterManager",
            "com/github/retrooper/packetevents/event/EventManager",
            "com/github/retrooper/packetevents/event/PacketReceiveEvent",
            "com/github/retrooper/packetevents/event/PacketSendEvent",
            "io/github/retrooper/packetevents/manager/protocol/ProtocolManagerImpl"
    );

    private static final List<String> PAPER_26_3_TARGETS = List.of(
            "org/bukkit/plugin/RegisteredListener",
            "org/bukkit/craftbukkit/CraftServer",
            "org/bukkit/craftbukkit/CraftWorld",
            "org/bukkit/craftbukkit/CraftRegionAccessor",
            "org/bukkit/craftbukkit/entity/CraftPlayer",
            "org/bukkit/craftbukkit/entity/CraftPlayer$2",
            "org/bukkit/craftbukkit/entity/CraftEntity",
            "net/minecraft/server/network/ServerCommonPacketListenerImpl",
            "net/minecraft/server/network/ServerGamePacketListenerImpl",
            "io/netty/channel/DefaultChannelPipeline",
            "io/netty/channel/AbstractChannelHandlerContext"
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

        List<ClassPatch> patches = List.of(
                new BukkitEventPatch(config, pluginRegistry, playerRegistry, cl),
                new BukkitApiPatch(logger, config, pluginRegistry, playerRegistry, cl),
                new ProtocolLibPatch(logger, config, pluginRegistry, playerRegistry, cl),
                new PacketEventsPatch(logger, config, pluginRegistry, playerRegistry, cl),
                new NettyPipelinePatch(logger, config, pluginRegistry, cl)
        );

        // 2. Verify Spigot 1.8.8 + ProtocolLib 5.4.0 + PacketEvents 2.14.0
        verifyTargets(logger, "Spigot 1.8.8", cl, patches, SPIGOT_1_8_TARGETS);

        // 3. Verify Paper 26.3 (downloaded & unpacked via PaperMC Fill v3 API + paperclip.patchonly)
        Path paperCacheDir = Path.of(System.getProperty("paper.cache.dir", "build/paper-cache/" + PAPER_VERSION));
        Path patchedPaperJar = ensurePaperUnpacked(logger, paperCacheDir);
        Path librariesDir = paperCacheDir.resolve("libraries");

        List<URL> urls = new ArrayList<>();
        urls.add(patchedPaperJar.toUri().toURL());
        try (var stream = Files.walk(librariesDir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(p -> {
                        try {
                            urls.add(p.toUri().toURL());
                        } catch (Exception ignored) {
                        }
                    });
        }
        try (URLClassLoader paperLoader = new URLClassLoader(urls.toArray(URL[]::new), null)) {
            verifyTargets(logger, "Paper " + PAPER_VERSION, paperLoader, patches, PAPER_26_3_TARGETS);
        }

        patchManager.shutdown();
        logger.info("ALL TARGET CLASSES PASSED JDK 25 BYTECODE VERIFICATION!");
    }

    private static Path ensurePaperUnpacked(Logger logger, Path cacheDir) throws Exception {
        Path patchedJar = cacheDir.resolve("versions").resolve(PAPER_VERSION).resolve("paper-" + PAPER_VERSION + ".jar");
        if (Files.exists(patchedJar) && Files.exists(cacheDir.resolve("libraries"))) {
            return patchedJar;
        }

        Files.createDirectories(cacheDir);
        Path paperclipJar = cacheDir.resolve("paperclip-" + PAPER_VERSION + ".jar");

        if (!Files.exists(paperclipJar)) {
            logger.info("Querying PaperMC Fill v3 API for Paper " + PAPER_VERSION + "...");
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest metaReq = HttpRequest.newBuilder(URI.create(PAPER_FILL_API))
                    .header("User-Agent", "PluginHooker/2.0 (https://github.com/DionaMC/PluginHooker)")
                    .GET()
                    .build();
            String body = client.send(metaReq, HttpResponse.BodyHandlers.ofString()).body();
            Matcher matcher = DOWNLOAD_URL_PATTERN.matcher(body);
            if (!matcher.find()) {
                throw new IllegalStateException("Could not find download URL in PaperMC Fill API response");
            }
            String downloadUrl = matcher.group(1);
            logger.info("Downloading Paper " + PAPER_VERSION + " from " + downloadUrl + "...");
            HttpRequest dlReq = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .header("User-Agent", "PluginHooker/2.0 (https://github.com/DionaMC/PluginHooker)")
                    .GET()
                    .build();
            try (InputStream in = client.send(dlReq, HttpResponse.BodyHandlers.ofInputStream()).body()) {
                Files.copy(in, paperclipJar, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        logger.info("Running paperclip patchonly in " + cacheDir + "...");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                javaBin,
                "-Dpaperclip.patchonly=true",
                "-jar",
                paperclipJar.toAbsolutePath().toString()
        ).directory(cacheDir.toFile()).inheritIO().start();

        int exitCode = process.waitFor();
        if (exitCode != 0 || !Files.exists(patchedJar)) {
            throw new IllegalStateException("Paperclip patchonly failed with exit code " + exitCode);
        }
        return patchedJar;
    }

    private static void verifyTargets(
            Logger logger,
            String envName,
            ClassLoader loader,
            List<ClassPatch> patches,
            List<String> targetClasses
    ) throws Exception {
        ClassHierarchyResolver resolver = ClassHierarchyResolver.ofResourceParsing(loader)
                .orElse(ClassHierarchyResolver.ofClassLoading(loader))
                .orElse(ClassHierarchyResolver.defaultResolver())
                .orElse(desc -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object))
                .cached();
        ClassFile verifierCf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));

        for (String internalName : targetClasses) {
            byte[] originalBytes;
            try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
                if (in == null) {
                    throw new IllegalStateException("[" + envName + "] Missing class resource: " + internalName);
                }
                originalBytes = in.readAllBytes();
            }

            boolean matched = false;
            for (ClassPatch patch : patches) {
                if (patch.matchesClass(internalName)) {
                    matched = true;
                    byte[] transformed = patch.transform(loader, internalName, originalBytes);
                    List<VerifyError> errors = verifierCf.verify(transformed);
                    if (!errors.isEmpty()) {
                        throw new AssertionError("[" + envName + "] Bytecode verification failed for " + internalName + ": " + errors);
                    }
                    if (transformed.length == originalBytes.length) {
                        throw new AssertionError("[" + envName + "] Class " + internalName + " was not modified by patch!");
                    }
                    logger.info("[" + envName + "] Verified bytecode: " + internalName
                            + " (" + originalBytes.length + " -> " + transformed.length + " bytes)");
                }
            }
            if (!matched) {
                throw new AssertionError("[" + envName + "] No patch matched target class: " + internalName);
            }
        }
    }
}
