package dev.diona.pluginhooker.agent;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;

/**
 * Obtains the JVM {@link Instrumentation} instance using ByteBuddyAgent.
 */
public final class AgentInstaller {

    private static volatile Instrumentation instrumentation;

    private AgentInstaller() {
    }

    public static synchronized Instrumentation install() {
        if (instrumentation != null) {
            return instrumentation;
        }
        try {
            instrumentation = ByteBuddyAgent.getInstrumentation();
        } catch (IllegalStateException ignored) {
            instrumentation = ByteBuddyAgent.install();
        }
        return instrumentation;
    }
}
