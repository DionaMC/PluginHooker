package io.github.dionatestserver.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.injector.PacketFilterBuilder;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import io.github.dionatestserver.pluginhooker.utils.ClassUtils;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import lombok.Getter;

public class ProtocolLibInjector extends Injector {

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
        return DionaConfig.hookProtocolLibPacket;
    }

    @Override
    protected void initClassPath() {
        classPool.appendClassPath(new LoaderClassPath(PacketFilterBuilder.class.getClassLoader()));
    }

}
