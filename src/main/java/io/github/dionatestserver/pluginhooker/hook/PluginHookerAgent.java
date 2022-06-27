package io.github.dionatestserver.pluginhooker.hook;

import java.lang.instrument.Instrumentation;

public class PluginHookerAgent {

    public static Instrumentation instrumentation;

    public static void agentmain(String stringArguments, Instrumentation inst) {
        instrumentation = inst;
    }
}
