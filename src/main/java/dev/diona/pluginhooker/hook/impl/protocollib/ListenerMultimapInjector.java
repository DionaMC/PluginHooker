package dev.diona.pluginhooker.hook.impl.protocollib;

import com.comphenix.protocol.concurrency.PacketTypeSet;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.hook.Injector;
import dev.diona.pluginhooker.utils.ClassUtils;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import org.bukkit.Bukkit;

public class ListenerMultimapInjector extends Injector {

    @ConfigPath("hook.protocollib.enabled")
    public boolean hookProtocolLibPacket;

    public ListenerMultimapInjector() {
        super("com.comphenix.protocol.concurrency.AbstractConcurrentListenerMultimap", "com.comphenix.protocol.concurrency.PacketTypeSet");
    }

    @Override
    public void hookClass() throws Exception {
        CtClass targetClass = classPool.get(this.targetClassName);
        CtMethod addListener = ClassUtils.getMethodByName(targetClass.getMethods(), "addListener");
        CtMethod removeListener = ClassUtils.getMethodByName(targetClass.getMethods(), "removeListener");

        String src = PluginHooker.class.getName() + ".getPlayerManager().checkAndRemoveCachedListener($0);";
        addListener.insertBefore(src);
        removeListener.insertBefore(src);
    }

    @Override
    public boolean canHook() {
        return hookProtocolLibPacket;
    }

    @Override
    protected void initClassPath() {
        classPool.appendClassPath(new LoaderClassPath(PacketTypeSet.class.getClassLoader()));
    }
}
