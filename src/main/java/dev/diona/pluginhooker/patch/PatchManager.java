package dev.diona.pluginhooker.patch;

import bot.inker.acj.JvmHacker;
import dev.diona.pluginhooker.PluginHooker;
import org.reflections.Reflections;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PatchManager {

    private final Logger logger = PluginHooker.getInstance().getLogger();

    public PatchManager() {
        List<Patch> patches = this.getPatcherList();

        List<Patch> FailedToPredefineClasses = patches.stream()
                .filter(Patch::canPatch)
                .filter(patcher -> {
                    if (patcher.isRedefineOnly()) {
                        return true;
                    }
                    try {
                        patcher.predefineClass();
                        logger.info(patcher.getClassNameWithoutPackage() + " is now predefined!");
                        return false;
                    } catch (Throwable e) {
                        return true;
                    }
                })
                .collect(Collectors.toList());

        if (FailedToPredefineClasses.isEmpty()) return;


        try {
            Instrumentation instrumentation = JvmHacker.instrumentation();

            FailedToPredefineClasses.forEach(patcher -> {
                try {
                    patcher.redefineClass(instrumentation);
                    logger.info(patcher.getClassNameWithoutPackage() + " is now redefined!");
                } catch (Exception e) {
                    logger.severe("Error while redefining " + patcher.getClassNameWithoutPackage());
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            logger.severe("Error while attaching agent");
            e.printStackTrace();
        }
    }

    private List<Patch> getPatcherList() {
        Reflections reflections = new Reflections("dev.diona.pluginhooker.patch.impl");
        return reflections.getSubTypesOf(Patch.class).stream().map(patcherClass -> {
            try {
                return patcherClass.getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }
}
