package dev.diona.pluginhooker.command;

import dev.diona.pluginhooker.PluginHookerPlugin;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.player.PlayerRegistry;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.api.plugin.PluginRegistry;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Unified command and tab-completer for {@code /pluginhooker} ({@code /ph}).
 */
@RequiredArgsConstructor
public final class PluginHookerCommand implements TabExecutor {

    private static final String PREFIX = color("&b[PH] &f> &b");
    private static final String PERMISSION = "pluginhooker.admin";

    private final PluginHookerPlugin selfPlugin;
    private final PluginRegistry pluginRegistry;
    private final PlayerRegistry playerRegistry;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sendMsg(sender, "PluginHooker v" + selfPlugin.getDescription().getVersion() + " (~DionaMC)");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "plugin" -> handlePluginCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "player" -> handlePlayerCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handlePluginCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendMsg(sender, "Usage: /ph plugin <add|remove|mode|list> [plugin] [WHITELIST|BLACKLIST]");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            Set<Plugin> hooked = pluginRegistry.getHookedPlugins();
            if (hooked.isEmpty()) {
                sendMsg(sender, "No plugins are currently hooked.");
                return true;
            }
            sendMsg(sender, "Hooked plugins (" + hooked.size() + "):");
            for (Plugin p : hooked) {
                HookMode mode = pluginRegistry.getHookMode(p);
                sendMsg(sender, " - &f" + p.getName() + " &7[" + mode + "]");
            }
            return true;
        }

        if (args.length < 2) {
            sendMsg(sender, "Usage: /ph plugin " + action + " <plugin> [WHITELIST|BLACKLIST]");
            return true;
        }

        Plugin target = Bukkit.getPluginManager().getPlugin(args[1]);
        if (target == null) {
            sendMsg(sender, "&cPlugin '" + args[1] + "' not found.");
            return true;
        }
        if (target.equals(selfPlugin)) {
            sendMsg(sender, "&cPluginHooker cannot hook itself.");
            return true;
        }

        switch (action) {
            case "add" -> {
                HookMode mode = null;
                if (args.length >= 3) {
                    mode = parseMode(args[2]);
                    if (mode == null) {
                        sendMsg(sender, "&cInvalid mode '" + args[2] + "'. Use WHITELIST or BLACKLIST.");
                        return true;
                    }
                }
                if (pluginRegistry.isHooked(target)) {
                    sendMsg(sender, "&ePlugin '" + target.getName() + "' is already hooked.");
                    return true;
                }
                if (mode != null) {
                    pluginRegistry.registerPlugin(target, mode);
                } else {
                    pluginRegistry.registerPlugin(target);
                }
                sendMsg(sender, "Hooked plugin &f" + target.getName() + " &b[" + pluginRegistry.getHookMode(target) + "].");
            }
            case "remove" -> {
                if (!pluginRegistry.unregisterPlugin(target)) {
                    sendMsg(sender, "&ePlugin '" + target.getName() + "' is not hooked.");
                    return true;
                }
                sendMsg(sender, "Unhooked plugin &f" + target.getName() + "&b.");
            }
            case "mode" -> {
                if (!pluginRegistry.isHooked(target)) {
                    sendMsg(sender, "&ePlugin '" + target.getName() + "' is not hooked.");
                    return true;
                }
                if (args.length < 3) {
                    sendMsg(sender, "Usage: /ph plugin mode " + target.getName() + " <WHITELIST|BLACKLIST>");
                    return true;
                }
                HookMode mode = parseMode(args[2]);
                if (mode == null) {
                    sendMsg(sender, "&cInvalid mode '" + args[2] + "'. Use WHITELIST or BLACKLIST.");
                    return true;
                }
                pluginRegistry.setHookMode(target, mode);
                sendMsg(sender, "Updated mode for &f" + target.getName() + " &bto &f" + mode + "&b.");
            }
            default -> sendMsg(sender, "Usage: /ph plugin <add|remove|mode|list> [plugin] [WHITELIST|BLACKLIST]");
        }
        return true;
    }

    private boolean handlePlayerCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMsg(sender, "Usage: /ph player <player> <add|remove|reset|list> [plugin]");
            return true;
        }

        Player bukkitPlayer = Bukkit.getPlayerExact(args[0]);
        HookedPlayer hookedPlayer = playerRegistry.getPlayer(bukkitPlayer);
        if (hookedPlayer == null) {
            sendMsg(sender, "&cPlayer '" + args[0] + "' not found.");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            Set<Plugin> enabled = hookedPlayer.getEnabledPlugins();
            if (enabled.isEmpty()) {
                sendMsg(sender, "No hooked plugins are enabled for &f" + bukkitPlayer.getName() + "&b.");
                return true;
            }
            sendMsg(sender, "Enabled hooked plugins for &f" + bukkitPlayer.getName() + "&b:");
            for (Plugin p : enabled) {
                sendMsg(sender, " - &f" + p.getName());
            }
            return true;
        }

        if (args.length < 3) {
            sendMsg(sender, "Usage: /ph player " + bukkitPlayer.getName() + " " + action + " <plugin>");
            return true;
        }

        Plugin target = Bukkit.getPluginManager().getPlugin(args[2]);
        if (target == null) {
            sendMsg(sender, "&cPlugin '" + args[2] + "' not found.");
            return true;
        }
        if (!pluginRegistry.isHooked(target)) {
            sendMsg(sender, "&ePlugin '" + target.getName() + "' is not in the hooked plugin list.");
            return true;
        }

        switch (action) {
            case "add", "enable" -> {
                hookedPlayer.enablePlugin(target);
                sendMsg(sender, "Enabled &f" + target.getName() + " &bfor &f" + bukkitPlayer.getName() + "&b.");
            }
            case "remove", "disable" -> {
                hookedPlayer.disablePlugin(target);
                sendMsg(sender, "Disabled &f" + target.getName() + " &bfor &f" + bukkitPlayer.getName() + "&b.");
            }
            case "reset" -> {
                hookedPlayer.resetPlugin(target);
                sendMsg(sender, "Reset &f" + target.getName() + " &bto default mode for &f" + bukkitPlayer.getName() + "&b.");
            }
            default -> sendMsg(sender, "Usage: /ph player <player> <add|remove|reset|list> [plugin]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterPrefix(List.of("plugin", "player"), args[0]);
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("plugin")) {
            if (args.length == 2) {
                return filterPrefix(List.of("add", "remove", "mode", "list"), args[1]);
            }
            if (args.length == 3) {
                String sub = args[1].toLowerCase(Locale.ROOT);
                if (sub.equals("add")) {
                    return filterPrefix(
                            Arrays.stream(Bukkit.getPluginManager().getPlugins())
                                    .filter(p -> !p.equals(selfPlugin) && !pluginRegistry.isHooked(p))
                                    .map(Plugin::getName)
                                    .toList(),
                            args[2]
                    );
                }
                if (sub.equals("remove") || sub.equals("mode")) {
                    return filterPrefix(
                            pluginRegistry.getHookedPlugins().stream().map(Plugin::getName).toList(),
                            args[2]
                    );
                }
            }
            if (args.length == 4) {
                String sub = args[1].toLowerCase(Locale.ROOT);
                if (sub.equals("add") || sub.equals("mode")) {
                    return filterPrefix(List.of("WHITELIST", "BLACKLIST"), args[3]);
                }
            }
        } else if (root.equals("player")) {
            if (args.length == 2) {
                return filterPrefix(
                        Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                        args[1]
                );
            }
            if (args.length == 3) {
                return filterPrefix(List.of("add", "remove", "reset", "list"), args[2]);
            }
            if (args.length == 4 && !args[2].equalsIgnoreCase("list")) {
                return filterPrefix(
                        pluginRegistry.getHookedPlugins().stream().map(Plugin::getName).toList(),
                        args[3]
                );
            }
        }
        return Collections.emptyList();
    }

    private void sendHelp(CommandSender sender) {
        sendMsg(sender, "PluginHooker v" + selfPlugin.getDescription().getVersion() + " (~DionaMC)");
        sendMsg(sender, "/ph plugin <add|remove|mode|list> &f- &bManage hooked plugins");
        sendMsg(sender, "/ph player <player> <add|remove|reset|list> &f- &bManage player sandbox");
    }

    private static HookMode parseMode(String input) {
        try {
            return HookMode.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> filterPrefix(Collection<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private static void sendMsg(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + color(message));
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
