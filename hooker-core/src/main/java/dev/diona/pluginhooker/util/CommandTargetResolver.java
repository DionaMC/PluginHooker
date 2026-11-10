package dev.diona.pluginhooker.util;

import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Zero-regex command-line target player resolver supporting O(1) case-insensitive name lookup,
 * subcommand keyword collision disambiguation, option flag skipping, and 36-char UUID matching.
 */
public final class CommandTargetResolver {

    private static final Set<String> COMMON_SUBCOMMAND_KEYWORDS = Set.of(
            "kick", "ban", "tempban", "ipban", "warn", "mute", "tempmute", "unban", "unmute",
            "punish", "kill", "tp", "teleport", "give", "take", "add", "remove", "set", "reset",
            "clear", "alert", "alerts", "verbose", "notify", "flag", "send", "msg", "tell",
            "title", "actionbar", "effect", "smite", "freeze", "player", "user"
    );

    private CommandTargetResolver() {
    }

    public static HookedPlayer resolveTargetPlayer(String commandLine, PlayerRegistryImpl playerRegistry) {
        if (commandLine == null || commandLine.isEmpty() || playerRegistry.getPlayers().isEmpty()) {
            return null;
        }

        int len = commandLine.length();
        int idx = 0;
        // Skip leading whitespace and root command token
        while (idx < len && Character.isWhitespace(commandLine.charAt(idx))) {
            idx++;
        }
        while (idx < len && !Character.isWhitespace(commandLine.charAt(idx))) {
            idx++;
        }

        HookedPlayer deferredSubcommandMatch = null;
        int nonFlagArgIndex = 0;

        while (idx < len) {
            while (idx < len && Character.isWhitespace(commandLine.charAt(idx))) {
                idx++;
            }
            if (idx >= len) {
                break;
            }
            int start = idx;
            while (idx < len && !Character.isWhitespace(commandLine.charAt(idx))) {
                idx++;
            }
            String token = commandLine.substring(start, idx);
            if (token.charAt(0) == '-') {
                continue;
            }

            // 1. Check UUID match at any argument position
            UUID parsedUuid = tryParseUuid(token);
            if (parsedUuid != null) {
                HookedPlayer byUuid = playerRegistry.getPlayer(parsedUuid);
                if (byUuid != null) {
                    return byUuid;
                }
            }

            // 2. Check player name within the first 4 non-flag arguments (avoids matching words inside reason text)
            if (nonFlagArgIndex < 4) {
                HookedPlayer byName = playerRegistry.getPlayerByName(token);
                if (byName != null) {
                    if (nonFlagArgIndex == 0
                            && COMMON_SUBCOMMAND_KEYWORDS.contains(token.toLowerCase(Locale.ROOT))) {
                        // Hold subcommand keyword match (e.g. "ac kick Steve") in case the real target follows
                        deferredSubcommandMatch = byName;
                    } else {
                        return byName;
                    }
                }
            }
            nonFlagArgIndex++;
        }
        return deferredSubcommandMatch;
    }

    private static UUID tryParseUuid(String token) {
        if (token.length() != 36
                || token.charAt(8) != '-'
                || token.charAt(13) != '-'
                || token.charAt(18) != '-'
                || token.charAt(23) != '-') {
            return null;
        }
        for (int i = 0; i < 36; i++) {
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                continue;
            }
            if (Character.digit(token.charAt(i), 16) < 0) {
                return null;
            }
        }
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
