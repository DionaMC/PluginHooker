package dev.diona.pluginhooker.hook.impl.bukkit;

import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.hook.Injector;
import dev.diona.pluginhooker.utils.ClassUtils;
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

    @ConfigPath("hook.bukkit.enabled")
    public boolean hookBukkitEvent;

    private static final CtClass CALLBACK_CLASS;

    static {
        try {
            CALLBACK_CLASS = classPool.get(BukkitEventCallback.class.getName());
            CALLBACK_CLASS.replaceClassName(
                    BukkitEventCallback.class.getName(),
                    Bukkit.class.getPackage().getName() + "." + BukkitEventCallback.class.getSimpleName()
            );
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Getter
    private final BukkitCallbackHandler callbackHandler = new BukkitCallbackHandler();

    public BukkitEventInjector() {
        super("org.bukkit.plugin.RegisteredListener", "org.bukkit.plugin.Plugin");

        try {
            Class<?> bukkitEventHooker =
                    DefineClassHelper.toClass(
                            CALLBACK_CLASS.getName(),
                            Bukkit.class,
                            Bukkit.class.getClassLoader(),
                            null,
                            CALLBACK_CLASS.toBytecode()
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
                "if(" + CALLBACK_CLASS.getName() + ".getInstance().onCallEvent(this.plugin,$1))return;"
        );
    }

    @Override
    public boolean canHook() {
        return hookBukkitEvent;
    }

    @Override
    protected void initClassPath() {
        // empty
    }
}
