package dev.diona.pluginhooker.hook;

import bot.inker.acj.JvmHacker;
import dev.diona.pluginhooker.PluginHooker;
import org.reflections.Reflections;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class HookerManager {

    private final Logger logger = PluginHooker.getInstance().getLogger();

    public HookerManager() {
        List<Injector> injectors = this.getInjectorList();

        List<Injector> definedClasses = injectors.stream()
                .filter(Injector::canHook)
                .filter(injector -> {
                    try {
                        injector.predefineClass();
                        logger.info(injector.getClassNameWithoutPackage() + " is now predefined!");
                        return false;
                    } catch (Throwable e) {
                        return true;
                    }
                })
                .collect(Collectors.toList());

        if (definedClasses.size() == 0) return;


        //init instrumentation field
        try {
            Instrumentation instrumentation = JvmHacker.instrumentation();

            definedClasses.forEach(injector -> {
                try {
                    injector.redefineClass(instrumentation);
                    logger.info(injector.getClassNameWithoutPackage() + " is now redefined!");
                } catch (Exception e) {
                    logger.severe("Error while redefining " + injector.getClassNameWithoutPackage());
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            logger.severe("Error while attaching agent");
            e.printStackTrace();
        }
    }

    private List<Injector> getInjectorList() {
        Reflections reflections = new Reflections("dev.diona.pluginhooker.hook.impl");
        return reflections.getSubTypesOf(Injector.class).stream().map(injectorClass -> {
            try {
                return injectorClass.getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }
}
