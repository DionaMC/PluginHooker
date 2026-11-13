package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.event.PlayerKickHookEvent;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Shared kick/disconnect guard evaluation across Bukkit {@code Player#kick*} and NMS {@code disconnect}/{@code close}.
 */
public final class KickGuardSupport {

    private KickGuardSupport() {
    }

    /**
     * Fast pre-check before resolving the calling plugin: returns true if a kick/disconnect on the
     * given target {@link Player} requires caller inspection (either to block a disabled plugin or to
     * fire {@link PlayerKickHookEvent}).
     */
    public static boolean shouldInspectKick(
            Player bukkitPlayer,
            HookedPlayer hookedPlayer,
            HookConfig.ApiHookSettings apiConfig,
            PlayerRegistryImpl playerRegistry
    ) {
        if (hookedPlayer != null && apiConfig.isCallKickEvent()) {
            return true;
        }
        return apiConfig.isBlockDisabledKick() && playerRegistry.hasDisabledPlugins(bukkitPlayer, hookedPlayer);
    }

    /**
     * Evaluates whether a kick/disconnect initiated by {@code caller} should be blocked, firing
     * {@link PlayerKickHookEvent} when the target is a tracked {@link HookedPlayer}.
     */
    public static boolean evaluateKick(
            Plugin caller,
            Player bukkitPlayer,
            HookedPlayer hookedPlayer,
            Object reasonObj,
            HookConfig config,
            PlayerRegistryImpl playerRegistry,
            Logger logger,
            String actionDescription
    ) {
        if (caller == null) {
            return false;
        }
        HookConfig.ApiHookSettings apiConfig = config.bukkitApi();
        boolean enabled = !playerRegistry.isPluginDisabled(bukkitPlayer, hookedPlayer, caller);
        boolean defaultBlock = !enabled && apiConfig.isBlockDisabledKick();
        String reason = KickReasonSerializer.serialize(reasonObj);
        boolean shouldBlock = hookedPlayer != null
                ? BukkitEvents.fireCancellable(
                        apiConfig.isCallKickEvent(),
                        defaultBlock,
                        () -> new PlayerKickHookEvent(caller, hookedPlayer, reason, reasonObj, enabled, defaultBlock)
                )
                : defaultBlock;

        if (shouldBlock && config.isDebug()) {
            String targetName = hookedPlayer != null ? hookedPlayer.getName() : bukkitPlayer.getName();
            logger.info("[Debug] Blocked " + caller.getName()
                    + " from " + actionDescription + " player " + targetName
                    + " (reason: " + reason + ")");
        }
        return shouldBlock;
    }
}
