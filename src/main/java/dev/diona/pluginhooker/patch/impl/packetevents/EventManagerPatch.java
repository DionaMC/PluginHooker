package dev.diona.pluginhooker.patch.impl.packetevents;

import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.patch.Patcher;
import dev.diona.pluginhooker.utils.ClassUtils;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import org.bukkit.Bukkit;

public class EventManagerPatch extends Patcher {

    @ConfigPath("hook.packetevents.enabled")
    public boolean hookPacketEventsPacket;

    public EventManagerPatch() {
        super("com.github.retrooper.packetevents.event.EventManager", "com.github.retrooper.packetevents.event.ProtocolPacketEvent", true);
    }

    @Override
    public void applyPatch() throws Exception {
        CtClass targetClass = classPool.get(this.targetClassName);

        CtMethod registerListenerNoRecalculation = ClassUtils.getMethodByName(targetClass.getDeclaredMethods(), "registerListenerNoRecalculation");
        String src1 = EventManagerCallbackHandler.class.getName() + ".getInstance().handleListenerRegister($1)";
        registerListenerNoRecalculation.insertBefore(
                src1 + ";"
        );

        CtMethod unregisterListenerNoRecalculation = ClassUtils.getMethodByName(targetClass.getDeclaredMethods(), "unregisterListenerNoRecalculation");
        String src2 = EventManagerCallbackHandler.class.getName() + ".getInstance().handleListenerUnregister($1)";
        unregisterListenerNoRecalculation.insertBefore(
                src2 + ";"
        );
    }

    @Override
    public boolean canPatch() {
        return hookPacketEventsPacket && Bukkit.getServer().getPluginManager().getPlugin("packetevents") != null;
    }

    @Override
    protected void initClassPath() {
        classPool.appendClassPath(new LoaderClassPath(ProtocolPacketEvent.class.getClassLoader()));
    }
}
