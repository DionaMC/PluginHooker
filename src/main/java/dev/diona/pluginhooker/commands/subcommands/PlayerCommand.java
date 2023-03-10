package dev.diona.pluginhooker.commands.subcommands;

import com.google.common.collect.Lists;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.commands.SubCommand;
import dev.diona.pluginhooker.player.DionaPlayer;
import dev.diona.pluginhooker.utils.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static dev.diona.pluginhooker.commands.SimpleCommand.PREFIX;

public class PlayerCommand extends SubCommand {
    public PlayerCommand() {
        super("player", "Manage player's enabled plugins", "/ph player <player name> <add/remove/list> <plugin name>");
    }

    @Override
    public boolean onCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            this.sendUsage(sender);
            return false;
        }
        // get Player
        DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(Bukkit.getPlayerExact(args[0]));
        if (dionaPlayer == null) {
            sender.sendMessage(StringUtils.colorize(PREFIX + "Player " + args[0] + " not found"));
            return false;
        }
        if (args[1].equalsIgnoreCase("add")) {
            if (args.length == 2) {
                sender.sendMessage(StringUtils.colorize(PREFIX + "Usage: /ph player " + args[0] + " add <plugin name>"));
                return false;
            }
            // get Plugin
            Plugin plugin = Bukkit.getPluginManager().getPlugin(args[2]);
            if (plugin == null) {
                sender.sendMessage(StringUtils.colorize(PREFIX + "Plugin " + args[2] + " not found"));
                return false;
            }
            // is plugin hooked
            if (!PluginHooker.getPluginManager().getPluginsToHook().contains(plugin)) {
                sender.sendMessage(StringUtils.colorize(PREFIX + "Plugin " + args[2] + " is not hooked"));
                return false;
            }
            if (dionaPlayer.isPluginEnabled(plugin)) {
                sender.sendMessage(StringUtils.colorize(PREFIX + "Plugin " + args[2] + " is already enabled for " + args[0]));
                return false;
            }
            dionaPlayer.enablePlugin(plugin);
            sender.sendMessage(StringUtils.colorize(PREFIX + "Plugin " + args[2] + " has been enabled for " + args[0]));
            return false;
        } else if (args[1].equalsIgnoreCase("remove")) {
            if (args.length == 2) {
                sender.sendMessage(StringUtils.colorize(PREFIX + "Usage: /ph player " + args[0] + " remove <plugin name>"));
                return false;
            }
            // get Plugin
            Plugin plugin = Bukkit.getPluginManager().getPlugin(args[2]);
            if (plugin == null) {
                sender.sendMessage(StringUtils.colorize(PREFIX + "Plugin " + args[2] + " not found"));
                return false;
            }
            if (!dionaPlayer.isPluginEnabled(plugin)) {
                sender.sendMessage(StringUtils.colorize(PREFIX + "Plugin " + args[2] + " is not enabled for " + args[0]));
                return false;
            }
            dionaPlayer.disablePlugin(plugin);
            sender.sendMessage(StringUtils.colorize(PREFIX + "Plugin " + args[2] + " has been disabled for " + args[0]));
            return false;
        } else if (args[1].equalsIgnoreCase("list")) {
            if (dionaPlayer.getEnabledPlugins().size() == 0) {
                sender.sendMessage(StringUtils.colorize(PREFIX + "No plugins are enabled for " + args[0]));
                return false;
            }
            sender.sendMessage(StringUtils.colorize(PREFIX + "Enabled plugins for " + args[0] + ":"));
            dionaPlayer.getEnabledPlugins().forEach(plugin -> sender.sendMessage(StringUtils.colorize(PREFIX + plugin.getName())));
            return false;
        } else {
            this.sendUsage(sender);
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> sub = Lists.newArrayList("add", "remove", "list");
            if (args[1].isEmpty()) {
                return sub;
            } else {
                return sub.stream()
                        .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
        } else if (args.length == 3) {
            if (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove")) {
                List<String> plugins = Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .map(Plugin::getName)
                        .collect(Collectors.toList());
                if (args[2].isEmpty()) {
                    return plugins;
                } else {
                    return plugins.stream()
                            .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        }
        return null;
    }
}
