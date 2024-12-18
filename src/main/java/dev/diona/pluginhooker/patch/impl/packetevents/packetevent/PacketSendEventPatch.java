package dev.diona.pluginhooker.patch.impl.packetevents.packetevent;

import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.patch.Patcher;
import dev.diona.pluginhooker.patch.impl.packetevents.PacketEventsCallbackHandler;
import dev.diona.pluginhooker.utils.ClassUtils;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import org.bukkit.Bukkit;

public class PacketSendEventPatch extends Patcher {

    @ConfigPath("hook.packetevents.enabled")
    public boolean hookPacketEventsPacket;

    public PacketSendEventPatch() {
        super("com.github.retrooper.packetevents.event.PacketSendEvent", "com.github.retrooper.packetevents.event.ProtocolPacketEvent");
    }

    @Override
    public void applyPatch() throws Exception {
        CtClass targetClass = classPool.get(this.targetClassName);
        CtMethod call = ClassUtils.getMethodByName(targetClass.getMethods(), "call");
        String src = PacketEventsCallbackHandler.class.getName() + ".getInstance().handlePacketEvent($1,this)";
        call.insertBefore(
                "if(" + src + ")return;"
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
