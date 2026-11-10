package dev.diona.pluginhooker.support;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
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
        Class<?> clazz = stubPlayerClass;
        if (clazz == null) {
            ClassDesc stubDesc = ClassDesc.of("dev.diona.pluginhooker.support.GeneratedStubPlayer");
            ClassDesc uuidDesc = ClassDesc.of("java.util.UUID");
            byte[] bytes = ClassFile.of().build(stubDesc, clb -> {
                clb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
                clb.withInterfaceSymbols(ClassDesc.of("org.bukkit.entity.Player"));
                clb.withField("uuid", uuidDesc, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                clb.withField("name", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                clb.withMethodBody(
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, uuidDesc, ConstantDescs.CD_String),
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
            });
            clazz = MethodHandles.lookup().defineClass(bytes);
            stubPlayerClass = clazz;
        }
        return (Player) clazz.getConstructor(UUID.class, String.class).newInstance(uuid, name);
    }

    public static final class ChildFirstActorLoader extends URLClassLoader {
        public ChildFirstActorLoader(ClassLoader parent) {
            super(new URL[]{SandboxTestFixtures.class.getProtectionDomain().getCodeSource().getLocation()}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("testplugin.")) {
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
}
