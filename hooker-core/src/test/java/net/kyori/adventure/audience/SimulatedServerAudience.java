package net.kyori.adventure.audience;

import java.util.function.Supplier;

/**
 * Simulates server-loaded Adventure Audience / ForwardingAudience default method frames in PatchVerifier.
 */
public interface SimulatedServerAudience {

    static <T> T forwardFromAdventureDefault(Supplier<T> targetApiCall) {
        return targetApiCall.get();
    }
}
