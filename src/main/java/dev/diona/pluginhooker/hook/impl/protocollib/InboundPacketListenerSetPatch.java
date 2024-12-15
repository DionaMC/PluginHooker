package dev.diona.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.injector.collection.PacketListenerSet;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.hook.Injector;
import dev.diona.pluginhooker.utils.ClassUtils;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import org.bukkit.Bukkit;

public class InboundPacketListenerSetPatch extends Injector {
    @ConfigPath("hook.protocollib.enabled")
    public boolean hookProtocolLibPacket;

    public InboundPacketListenerSetPatch() {
        super("com.comphenix.protocol.injector.collection.InboundPacketListenerSet", "com.comphenix.protocol.injector.collection.PacketListenerSet");
    }

    @Override
    public void hookClass() throws Exception {
        CtClass targetClass = classPool.get(this.targetClassName);
        CtMethod invokeListener = ClassUtils.getMethodByName(targetClass.getMethods(), "invokeListener");
        String src = ProtocolLibCallbackHandler.class.getName() + ".getInstance().handleProtocolLibPacket($1,$2,false)";
        invokeListener.insertBefore(
                "if(" + src + ")return;"
        );
    }

    @Override
    public boolean canHook() {
        return hookProtocolLibPacket && Bukkit.getServer().getPluginManager().getPlugin("ProtocolLib") != null;
    }

    @Override
    protected void initClassPath() {
        classPool.appendClassPath(new LoaderClassPath(PacketListenerSet.class.getClassLoader()));

    }
}
