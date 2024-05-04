package dev.diona.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.injector.PacketFilterBuilder;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.hook.Injector;
import dev.diona.pluginhooker.utils.ClassUtils;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import lombok.Getter;
import org.bukkit.Bukkit;

public class ProtocolLibInjector extends Injector {

    @ConfigPath("hook.protocollib.enabled")
    public boolean hookProtocolLibPacket;

    @Getter
    private static final ProtocolLibCallbackHandler callbackHandler = new ProtocolLibCallbackHandler();

    public ProtocolLibInjector() {
        super("com.comphenix.protocol.injector.PacketFilterManager", "com.comphenix.protocol.injector.PacketFilterBuilder");
    }

    @Override
    public void hookClass() throws Exception {
        CtMethod postPacketToListeners = ClassUtils.getMethodBySignature(targetClass.getDeclaredMethods(), "(Lcom/comphenix/protocol/injector/SortedPacketListenerList;Lcom/comphenix/protocol/events/PacketEvent;Z)V");
        postPacketToListeners.insertBefore(
                "$1=" + ProtocolLibInjector.class.getName() + ".getCallbackHandler().handleProtocolLibPacket($1,$2,$3);"
        );
    }

    @Override
    public boolean canHook() {
        return hookProtocolLibPacket && Bukkit.getServer().getPluginManager().getPlugin("ProtocolLib") != null;
    }

    @Override
    protected void initClassPath() {
        classPool.appendClassPath(new LoaderClassPath(PacketFilterBuilder.class.getClassLoader()));
    }

}
