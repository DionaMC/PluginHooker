package io.github.dionatestserver.pluginhooker.hook.impl.bukkit;

import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.HookerManager;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.function.BiPredicate;

public class BukkitEventInjector extends Injector {

    @Getter
    private BukkitCallbackHandler callbackHandler = new BukkitCallbackHandler();

    public BukkitEventInjector() {
        super("org.bukkit.plugin.RegisteredListener");
    }

    @Override
    public void predefineClass() {
        if (!DionaConfig.hookBukkitEvent) return;

        try {
            Class<?> bukkitEventHooker =
                    DefineClassHelper.toClass(
                            BukkitEventHooker.class.getName(),
                            HookerManager.class,
                            Bukkit.class.getClassLoader(),
                            null,
                            classPool.get(BukkitEventHooker.class.getName()).toBytecode()
                    );

            BiPredicate<Plugin, Event> callback = this.callbackHandler::handleBukkitEvent;
            bukkitEventHooker.getConstructor(BiPredicate.class).newInstance(callback);

            CtClass registeredListener = classPool.get(targetClass);
            CtMethod callEvent = this.getMethodByName(registeredListener.getMethods(), "callEvent");
            callEvent.insertBefore(
                    "if(" + BukkitEventHooker.class.getName() + ".getInstance().onCallEvent(this.plugin,$1))return;"
            );

            DefineClassHelper.toClass(targetClass, Plugin.class, Plugin.class.getClassLoader(), null, registeredListener.toBytecode());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private CtMethod getMethodByName(CtMethod[] methods, String targetName) {
        for (CtMethod method : methods) {
            if (method.getName().equals(targetName))
                return method;
        }
        return null;
    }

    public static class BukkitEventHooker {

        @Getter
        private static BukkitEventHooker instance;

        private final BiPredicate<Plugin, Event> callback;

        public BukkitEventHooker(BiPredicate<Plugin, Event> callback) {
            instance = this;
            this.callback = callback;
        }

        public boolean onCallEvent(Plugin plugin, Event event) {
            return callback.test(plugin, event);
        }
    }
}
