package io.github.dionatestserver.pluginhooker.hook;

import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.utils.AgentUtils;
import org.reflections.Reflections;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class HookerManager {

    private final Logger logger = DionaPluginHooker.getInstance().getLogger();

    public HookerManager() {
        List<Injector> injectors = this.getInjectorList();

        List<Injector> definedClasses = injectors.stream()
                .filter(Injector::canHook)
                .filter(injector -> {
                    if (injector.isTargetClassDefined()) {
                        logger.info( injector.getClassNameWithoutPackage() + " is already defined! Skipping...");
                        return true;
                    }
                    try {
                        injector.predefineClass();
                        logger.info( injector.getClassNameWithoutPackage() + " is now predefined!");
                        return false;
                    } catch (Exception e) {
                        logger.severe("Error while predefining " + injector.getClassNameWithoutPackage());
                        e.printStackTrace();
                        return true;
                    }
                })
                .collect(Collectors.toList());

        if (definedClasses.size() == 0) return;


        //init instrumentation field
        try {
            String agentClass = "io.github.dionatestserver.pluginhooker.hook.PluginHookerAgent";
            File agentFile = AgentUtils.generateAgentFile(agentClass);
            AgentUtils.attachSelf(Objects.requireNonNull(agentFile));
        } catch (Exception e) {
            logger.severe("Error while attaching agent");
            e.printStackTrace();
        }

        definedClasses.forEach(injector -> {
            try {
                injector.redefineClass(PluginHookerAgent.instrumentation);
                logger.info(injector.getClassNameWithoutPackage() + " is now redefined!");
            } catch (Exception e) {
                logger.severe("Error while redefining " + injector.getClassNameWithoutPackage());
                e.printStackTrace();
            }
        });
    }

    private List<Injector> getInjectorList() {
        Reflections reflections = new Reflections("io.github.dionatestserver.pluginhooker.hook.impl");
        return reflections.getSubTypesOf(Injector.class).stream().map(injectorClass -> {
            try {
                return injectorClass.getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }
}
