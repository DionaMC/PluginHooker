package dev.diona.pluginhooker.patch.impl.protocollib;

import com.comphenix.protocol.injector.collection.PacketListenerSet;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.patch.Patch;
import dev.diona.pluginhooker.utils.ClassUtils;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import org.bukkit.Bukkit;

public class OutboundPacketListenerSetPatch extends Patch {

    @ConfigPath("hook.protocollib.enabled")
    public boolean hookProtocolLibPacket;

    public OutboundPacketListenerSetPatch() {
        super("com.comphenix.protocol.injector.collection.OutboundPacketListenerSet", "com.comphenix.protocol.injector.collection.PacketListenerSet");
    }

    @Override
    public void applyPatch() throws Exception {
        CtClass targetClass = classPool.get(this.targetClassName);
        CtMethod invokeListener = ClassUtils.getMethodByName(targetClass.getMethods(), "invokeListener");
        String src = ProtocolLibCallbackHandler.class.getName() + ".getInstance().handleProtocolLibPacket($1,$2,true)";
        invokeListener.insertBefore(
                "if(" + src + ")return;"
        );
    }

    @Override
    public boolean canPatch() {
        return hookProtocolLibPacket && Bukkit.getServer().getPluginManager().getPlugin("ProtocolLib") != null;
    }

    @Override
    protected void initClassPath() {
        classPool.appendClassPath(new LoaderClassPath(PacketListenerSet.class.getClassLoader()));

    }
}
