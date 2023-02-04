package io.github.dionatestserver.pluginhooker.utils;

import com.sun.tools.attach.VirtualMachine;
import io.github.dionatestserver.pluginhooker.PluginHooker;
import javassist.ClassPool;

import java.io.File;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class AgentUtils {

    static {
        // 高版本jdk的sun.tools.attach.HotSpotVirtualMachine
        // 拥有ALLOW_ATTACH_SELF检查 需要被设置为true
        //TODO 需要修复
    }

    public static File generateAgentFile(String agentClass) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Agent-Class"), agentClass);
        manifest.getMainAttributes().put(new Attributes.Name("Can-Redefine-Classes"), "true");

        try {
            File agentFile = new File(PluginHooker.getInstance().getDataFolder(), "agent.jar");
            if (!agentFile.exists()) {
                agentFile.createNewFile();
            }

            OutputStream outputStream = Files.newOutputStream(agentFile.toPath());
            JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest);
            jarOutputStream.putNextEntry(new JarEntry(agentClass.replace(".", "/") + ".class"));


            ClassPool pool = ClassPool.getDefault();
            jarOutputStream.write(pool.get(agentClass).toBytecode());

            jarOutputStream.finish();
            return agentFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String getPid() {
        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
        String pid = bean.getName();
        if (pid.contains("@")) {
            pid = pid.substring(0, pid.indexOf("@"));
        }
        return pid;
    }

    public static void attachSelf(File agentFile) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(getPid());
        vm.loadAgent(agentFile.getAbsolutePath());
        vm.detach();
    }
}