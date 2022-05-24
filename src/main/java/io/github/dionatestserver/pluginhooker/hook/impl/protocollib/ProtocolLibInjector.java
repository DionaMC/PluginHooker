package io.github.dionatestserver.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.injector.PacketFilterBuilder;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.Injector;
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
        classPool.appendClassPath(new LoaderClassPath(PacketFilterBuilder.class.getClassLoader()));
    }

    @Override
    public void predefineClass() {
        if (!DionaConfig.hookProtocolLibPacket) return;

        try {
            CtClass packetFilterManager = classPool.get(targetClass);

            CtMethod postPacketToListeners = this.getMethodBySignature(packetFilterManager.getDeclaredMethods(), "(Lcom/comphenix/protocol/injector/SortedPacketListenerList;Lcom/comphenix/protocol/events/PacketEvent;Z)V");
            postPacketToListeners.insertBefore(
                    "$1=" + ProtocolLibInjector.class.getName() + ".getCallbackHandler().handleProtocolLibPacket($1,$2,$3);"
            );

            DefineClassHelper.toClass(targetClass, PacketFilterBuilder.class, PacketFilterBuilder.class.getClassLoader(), null, packetFilterManager.toBytecode());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private CtMethod getMethodBySignature(CtMethod[] methods, String signature) {
        for (CtMethod method : methods) {
            if (method.getSignature().equals(signature))
                return method;
        }
        return null;
    }
}
