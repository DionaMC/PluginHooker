package io.github.dionatestserver.pluginhooker.hook;

import com.sun.tools.attach.VirtualMachine;
import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import javassist.ClassPool;
import org.reflections.Reflections;

import java.io.File;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class HookerManager {

    private final Logger logger = DionaPluginHooker.getInstance().getLogger();
    private static final String AGENT_CLASS = "io.github.dionatestserver.pluginhooker.hook.PluginHookerAgent";

    public HookerManager() {
        List<Injector> injectors = this.getInjectorList();

        List<Injector> definedClasses = injectors.stream()
                .filter(Injector::canHook)
                .filter(injector -> {
                    if (injector.isTargetClassDefined()) {
                        logger.info( injector.getClassNameWithoutPackage() + " is already defined! Skipping...");
                        return true;
                    }
                    try {
                        injector.predefineClass();
                        logger.info( injector.getClassNameWithoutPackage() + " is now predefined!");
                    } catch (Exception e) {
                        logger.severe("Error while predefining " + injector.getClassNameWithoutPackage());
                        e.printStackTrace();
                        return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());

        if (definedClasses.size() == 0) return;

        //init instrumentation field
        File agentFile = this.generateAgentFile();

        this.attachAgent(agentFile);

        definedClasses.forEach(injector -> {
            try {
                injector.redefineClass(PluginHookerAgent.instrumentation);
                logger.info( injector.getClassNameWithoutPackage() + " is now redefined!");
            } catch (Exception e) {
                logger.severe("Error while redefining " + injector.getClassNameWithoutPackage());
                e.printStackTrace();
            }
        });
    }

    private List<Injector> getInjectorList() {
        Reflections reflections = new Reflections("io.github.dionatestserver.pluginhooker.hook.impl");
        return reflections.getSubTypesOf(Injector.class).stream().map(injectorClass -> {
            try {
                return injectorClass.getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    private File generateAgentFile() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Agent-Class"), AGENT_CLASS);
        manifest.getMainAttributes().put(new Attributes.Name("Can-Redefine-Classes"), "true");

        try {
            File agentFile = new File(DionaPluginHooker.getInstance().getDataFolder(), "agent.jar");
            if (!agentFile.exists()) {
                agentFile.createNewFile();
            }

            OutputStream outputStream = Files.newOutputStream(agentFile.toPath());
            JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest);
            jarOutputStream.putNextEntry(new JarEntry(AGENT_CLASS.replace(".", "/") + ".class"));


            ClassPool pool = ClassPool.getDefault();
            jarOutputStream.write(pool.get(AGENT_CLASS).toBytecode());

            jarOutputStream.finish();
            return agentFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getPid() {
        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
        String pid = bean.getName();
        if (pid.contains("@")) {
            pid = pid.substring(0, pid.indexOf("@"));
        }
        return pid;
    }

    private void attachAgent(File agentFile) {
        try {
            System.loadLibrary("attach");
            VirtualMachine vm = VirtualMachine.attach(this.getPid());
            vm.loadAgent(agentFile.getAbsolutePath());
            vm.detach();
        } catch (Exception e) {
            logger.severe("Error while attaching agent");
            e.printStackTrace();
        }
    }
}
