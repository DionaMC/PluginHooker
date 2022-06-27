package io.github.dionatestserver.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.concurrency.PacketTypeSet;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import io.github.dionatestserver.pluginhooker.utils.ClassUtils;
import javassist.CtClass;
import javassist.CtMethod;

public class ListenerMultimapInjector extends Injector {

    public ListenerMultimapInjector() {
        super("com.comphenix.protocol.concurrency.AbstractConcurrentListenerMultimap", PacketTypeSet.class);
    }

    @Override
    public CtClass generateHookedClass() {
        try {
            CtClass targetClass = classPool.get(this.targetClass);
            CtMethod addListener = ClassUtils.getMethodByName(targetClass.getMethods(), "addListener");
            CtMethod removeListener = ClassUtils.getMethodByName(targetClass.getMethods(), "removeListener");

            String src = ProtocolLibInjector.class.getName() + ".getCallbackHandler().removeListenersCache();";
            addListener.insertBefore(src);
            removeListener.insertBefore(src);

            return targetClass;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean canHook() {
        return DionaConfig.hookProtocolLibPacket;
    }
}
