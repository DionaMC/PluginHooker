package dev.diona.pluginhooker.support;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared test fixtures for offline Bukkit/Netty/NMS sandbox unit tests.
 */
public final class SandboxTestFixtures {

    private static volatile Class<?> stubPlayerClass;

    private SandboxTestFixtures() {
    }

    public static Plugin createMockPlugin(ClassLoader definingLoader, String name) {
        return (Plugin) Proxy.newProxyInstance(
                definingLoader,
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> null;
                }
        );
    }

    public static HookedPlayer createDisabledHookedPlayerProxy(ClassLoader loader, String name) {
        return (HookedPlayer) Proxy.newProxyInstance(
                loader,
                new Class<?>[]{HookedPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isPluginEnabled" -> false;
                    case "hasAnyDisabledPlugin" -> true;
                    case "isInJoinGracePeriod" -> false;
                    default -> null;
                }
        );
    }

    public static Player createStubPlayer(UUID uuid, String name) throws Exception {
        return createStubPlayerWithEntity(uuid, name, null);
    }

    public static Player createStubPlayerWithEntity(
            UUID uuid,
            String name,
            net.minecraft.server.test.PlayerConnection.SimulatedEntityPlayer handle
    ) throws Exception {
        Class<?> clazz = stubPlayerClass;
        if (clazz == null) {
            ClassDesc stubDesc = ClassDesc.of("dev.diona.pluginhooker.support.GeneratedStubPlayer");
            ClassDesc uuidDesc = ClassDesc.of("java.util.UUID");
            ClassDesc epDesc = ClassDesc.of(
                    net.minecraft.server.test.PlayerConnection.SimulatedEntityPlayer.class.getName()
            );
            byte[] bytes = ClassFile.of().build(stubDesc, clb -> {
                clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
                clb.withInterfaceSymbols(ClassDesc.of("org.bukkit.entity.Player"));
                clb.withField("uuid", uuidDesc, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                clb.withField("name", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                clb.withField("handle", epDesc, ClassFile.ACC_PUBLIC);
                clb.withMethodBody(
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, uuidDesc, ConstantDescs.CD_String, epDesc),
                        ClassFile.ACC_PUBLIC,
                        cb -> {
                            cb.aload(0);
                            cb.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                            cb.aload(0);
                            cb.aload(1);
                            cb.putfield(stubDesc, "uuid", uuidDesc);
                            cb.aload(0);
                            cb.aload(2);
                            cb.putfield(stubDesc, "name", ConstantDescs.CD_String);
                            cb.aload(0);
                            cb.aload(3);
                            cb.putfield(stubDesc, "handle", epDesc);
                            cb.return_();
                        }
                );
                clb.withMethodBody("getUniqueId", MethodTypeDesc.of(uuidDesc), ClassFile.ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.getfield(stubDesc, "uuid", uuidDesc);
                    cb.areturn();
                });
                clb.withMethodBody("getName", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.getfield(stubDesc, "name", ConstantDescs.CD_String);
                    cb.areturn();
                });
                clb.withMethodBody("getHandle", MethodTypeDesc.of(epDesc), ClassFile.ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.getfield(stubDesc, "handle", epDesc);
                    cb.areturn();
                });
            });
            clazz = MethodHandles.lookup().defineClass(bytes);
            stubPlayerClass = clazz;
        }
        return (Player) clazz
                .getConstructor(
                        UUID.class,
                        String.class,
                        net.minecraft.server.test.PlayerConnection.SimulatedEntityPlayer.class
                )
                .newInstance(uuid, name, handle);
    }

    public static final class ChildFirstActorLoader extends URLClassLoader {
        private final String childFirstPrefix;
        @SuppressWarnings("unused")
        private Plugin plugin;

        public ChildFirstActorLoader(ClassLoader parent) {
            this(parent, "testplugin.");
        }

        public ChildFirstActorLoader(ClassLoader parent, String childFirstPrefix) {
            super(new URL[]{SandboxTestFixtures.class.getProtectionDomain().getCodeSource().getLocation()}, parent);
            this.childFirstPrefix = childFirstPrefix;
        }

        public void setPlugin(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(childFirstPrefix)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    /**
     * Simulates encrypted/packed anti-cheat {@link ClassLoader}s that define classes from memory and expose no
     * {@code .class} resources via {@link #getResourceAsStream(String)}.
     */
    public static final class InMemoryNoResourceLoader extends ClassLoader {
        public InMemoryNoResourceLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return null;
        }

        public Class<?> defineInMemoryClass(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    /**
     * Generates a {@code RegisteredListener} subclass whose {@code callEvent} merges {@code this} with a
     * {@code RegisteredListener} field across a branch so {@code StackMapGenerator} must resolve {@code thisClass}
     * mid-{@code defineClass}.
     */
    public static byte[] buildSelfMergingListenerBytes(String binaryClassName) {
        ClassDesc selfDesc = ClassDesc.of(binaryClassName);
        ClassDesc rlDesc = ClassDesc.of("org.bukkit.plugin.RegisteredListener");
        ClassDesc listenerDesc = ClassDesc.of("org.bukkit.event.Listener");
        ClassDesc executorDesc = ClassDesc.of("org.bukkit.plugin.EventExecutor");
        ClassDesc priorityDesc = ClassDesc.of("org.bukkit.event.EventPriority");
        ClassDesc pluginDesc = ClassDesc.of("org.bukkit.plugin.Plugin");
        ClassDesc eventDesc = ClassDesc.of("org.bukkit.event.Event");
        ClassDesc atomicIntDesc = ClassDesc.of("java.util.concurrent.atomic.AtomicInteger");

        ClassHierarchyResolver builderResolver = ClassHierarchyResolver.of(List.of(), Map.of(selfDesc, rlDesc))
                .orElse(ClassHierarchyResolver.defaultResolver());
        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(builderResolver)).build(selfDesc, clb -> {
            clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            clb.withSuperclass(rlDesc);
            clb.withField("fallback", rlDesc, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            clb.withField("calls", atomicIntDesc, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);

            clb.withMethodBody(
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, pluginDesc, rlDesc, atomicIntDesc),
                    ClassFile.ACC_PUBLIC,
                    cb -> {
                        cb.aload(0);
                        cb.aconst_null();
                        cb.aconst_null();
                        cb.getstatic(priorityDesc, "NORMAL", priorityDesc);
                        cb.aload(1);
                        cb.iconst_0();
                        cb.invokespecial(
                                rlDesc,
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(
                                        ConstantDescs.CD_void,
                                        listenerDesc,
                                        executorDesc,
                                        priorityDesc,
                                        pluginDesc,
                                        ConstantDescs.CD_boolean
                                )
                        );
                        cb.aload(0);
                        cb.aload(2);
                        cb.putfield(selfDesc, "fallback", rlDesc);
                        cb.aload(0);
                        cb.aload(3);
                        cb.putfield(selfDesc, "calls", atomicIntDesc);
                        cb.return_();
                    }
            );

            clb.withMethodBody(
                    "callEvent",
                    MethodTypeDesc.of(ConstantDescs.CD_void, eventDesc),
                    ClassFile.ACC_PUBLIC,
                    cb -> {
                        Label elseLabel = cb.newLabel();
                        Label mergeLabel = cb.newLabel();
                        cb.aload(1);
                        cb.invokevirtual(eventDesc, "isAsynchronous", MethodTypeDesc.of(ConstantDescs.CD_boolean));
                        cb.ifeq(elseLabel);
                        cb.aload(0);
                        cb.goto_(mergeLabel);
                        cb.labelBinding(elseLabel);
                        cb.aload(0);
                        cb.getfield(selfDesc, "fallback", rlDesc);
                        cb.labelBinding(mergeLabel);
                        cb.pop();
                        cb.aload(0);
                        cb.getfield(selfDesc, "calls", atomicIntDesc);
                        cb.invokevirtual(atomicIntDesc, "incrementAndGet", MethodTypeDesc.of(ConstantDescs.CD_int));
                        cb.pop();
                        cb.return_();
                    }
            );
        });
    }
}
