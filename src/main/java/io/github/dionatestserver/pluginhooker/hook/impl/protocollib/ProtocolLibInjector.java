package io.github.dionatestserver.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.injector.PacketFilterBuilder;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import io.github.dionatestserver.pluginhooker.utils.ClassUtils;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;

public class ProtocolLibInjector extends Injector {

    @Getter
    private static ProtocolLibCallbackHandler callbackHandler = new ProtocolLibCallbackHandler();

    public ProtocolLibInjector() {
        super("com.comphenix.protocol.injector.PacketFilterManager");
    }

    @Override
    public void predefineClass() {
        classPool.appendClassPath(new LoaderClassPath(PacketFilterBuilder.class.getClassLoader()));

        try {
            CtClass packetFilterManager = classPool.get(targetClass);

            CtMethod postPacketToListeners = ClassUtils.getMethodBySignature(packetFilterManager.getDeclaredMethods(), "(Lcom/comphenix/protocol/injector/SortedPacketListenerList;Lcom/comphenix/protocol/events/PacketEvent;Z)V");
            postPacketToListeners.insertBefore(
                    "$1=" + ProtocolLibInjector.class.getName() + ".getCallbackHandler().handleProtocolLibPacket($1,$2,$3);"
            );

            DefineClassHelper.toClass(targetClass, PacketFilterBuilder.class, PacketFilterBuilder.class.getClassLoader(), null, packetFilterManager.toBytecode());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean canHook() {
        return DionaConfig.hookProtocolLibPacket;
    }

}
