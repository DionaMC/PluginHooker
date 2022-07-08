package io.github.dionatestserver.pluginhooker.hook.impl.bukkit;

import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.HookerManager;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import io.github.dionatestserver.pluginhooker.utils.ClassUtils;
import javassist.CannotCompileException;
import javassist.CtMethod;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.function.BiPredicate;

public class BukkitEventInjector extends Injector {

    @Getter
    private final BukkitCallbackHandler callbackHandler = new BukkitCallbackHandler();

    public BukkitEventInjector() {
        super("org.bukkit.plugin.RegisteredListener", Plugin.class);

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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void hookClass() throws CannotCompileException {
        CtMethod callEvent = ClassUtils.getMethodByName(targetClass.getMethods(), "callEvent");
        callEvent.insertBefore(
                "if(" + BukkitEventHooker.class.getName() + ".getInstance().onCallEvent(this.plugin,$1))return;"
        );
    }

    @Override
    public boolean canHook() {
        return DionaConfig.hookBukkitEvent;
    }

    @Override
    protected void initClassPath() {
        // empty
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
