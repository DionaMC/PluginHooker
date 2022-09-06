package io.github.dionatestserver.pluginhooker.hook.impl.bukkit;

import io.github.dionatestserver.pluginhooker.config.DionaConfig;
import io.github.dionatestserver.pluginhooker.hook.Injector;
import io.github.dionatestserver.pluginhooker.utils.ClassUtils;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.util.proxy.DefineClassHelper;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.function.BiPredicate;

public class BukkitEventInjector extends Injector {

    @Getter
    private final BukkitCallbackHandler callbackHandler = new BukkitCallbackHandler();

    private static final CtClass HOOKER_CLASS;

    static {
        try {
            HOOKER_CLASS = classPool.get(BukkitEventHooker.class.getName());
            HOOKER_CLASS.replaceClassName(
                    BukkitEventHooker.class.getName(),
                    Bukkit.class.getPackage().getName() + "." + BukkitEventHooker.class.getSimpleName()
            );
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public BukkitEventInjector() {
        super("org.bukkit.plugin.RegisteredListener", Plugin.class);

        try {
            Class<?> bukkitEventHooker =
                    DefineClassHelper.toClass(
                            HOOKER_CLASS.getName(),
                            Bukkit.class,
                            Bukkit.class.getClassLoader(),
                            null,
                            HOOKER_CLASS.toBytecode()
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
                "if(" + HOOKER_CLASS.getName() + ".getInstance().onCallEvent(this.plugin,$1))return;"
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


}
