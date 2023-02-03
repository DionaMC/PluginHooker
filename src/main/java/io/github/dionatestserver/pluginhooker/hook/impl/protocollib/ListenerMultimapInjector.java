package io.github.dionatestserver.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.concurrency.PacketTypeSet;
import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import io.github.dionatestserver.pluginhooker.utils.ClassUtils;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

public class ListenerMultimapInjector extends Injector {

    public ListenerMultimapInjector() {
        super("com.comphenix.protocol.concurrency.AbstractConcurrentListenerMultimap", "com.comphenix.protocol.concurrency.PacketTypeSet");
    }

    @Override
    public void hookClass() throws Exception {
        CtClass targetClass = classPool.get(this.targetClassName);
        CtMethod addListener = ClassUtils.getMethodByName(targetClass.getMethods(), "addListener");
        CtMethod removeListener = ClassUtils.getMethodByName(targetClass.getMethods(), "removeListener");

        String src = DionaPluginHooker.class.getName() + ".getPlayerManager().removeAllPlayerCachedListener();";
        addListener.insertBefore(src);
        removeListener.insertBefore(src);
    }

    @Override
    public boolean canHook() {
        return DionaConfig.hookProtocolLibPacket;
    }

    @Override
    protected void initClassPath() {
        classPool.appendClassPath(new LoaderClassPath(PacketTypeSet.class.getClassLoader()));
    }
}
