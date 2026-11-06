package dev.diona.pluginhooker.api.plugin;

/**
 * Defines the default sandbox policy for a hooked plugin.
 */
public enum HookMode {
    /**
     * The plugin is blocked for all players by default unless explicitly enabled for a player.
     */
    WHITELIST,

    /**
     * The plugin is allowed for all players by default unless explicitly disabled for a player.
     */
    BLACKLIST
}
