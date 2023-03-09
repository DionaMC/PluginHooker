package dev.diona.pluginhooker.commands;

import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.commands.subcommands.PlayerCommand;
import dev.diona.pluginhooker.commands.subcommands.PluginCommand;
import dev.diona.pluginhooker.utils.StringUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SimpleCommand implements TabExecutor {

    public static String PREFIX = "&b[PH] &f> &b";

    private final PluginHooker pluginHooker = PluginHooker.getInstance();

    private final Set<SubCommand> commands = new LinkedHashSet<>();

    public SimpleCommand() {
        this.commands.add(new PluginCommand());
        this.commands.add(new PlayerCommand());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        // check permission first
        if (!sender.hasPermission("pluginhooker.admin")) {
            sender.sendMessage(StringUtils.colorize(PREFIX + "PluginHooker " + pluginHooker.getDescription().getVersion() + ": Plugin rekker (~DionaMC)"));
            return false;
        } else if (args.length == 0) {
            showHelp(sender);
            return false;
        }
        for (SubCommand subCommand : commands) {
            if (subCommand.getName().equalsIgnoreCase(args[0])) {
                // remove the first arg
                String[] newArgs = new String[args.length - 1];
                System.arraycopy(args, 1, newArgs, 0, args.length - 1);
                return subCommand.onCommand(sender, newArgs);
            }
        }
        showHelp(sender);
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("pluginhooker.admin")) {
            return null;
        }
        if (args.length == 1) {
            if (args[0].isEmpty()) {
                return commands.stream().map(SubCommand::getName)
                        .collect(Collectors.toList());
            } else {
                return commands.stream().map(SubCommand::getName)
                        .filter(name -> name.startsWith(args[0]))
                        .collect(Collectors.toList());
            }
        }
        for (SubCommand subCommand : commands) {
            if (subCommand.getName().equalsIgnoreCase(args[0])) {
                String[] newArgs = new String[args.length - 1];
                System.arraycopy(args, 1, newArgs, 0, args.length - 1);
                return subCommand.onTabComplete(sender, newArgs);
            }
        }
        return null;
    }

    public void showHelp(CommandSender sender) {
        sender.sendMessage(StringUtils.colorize(PREFIX + "PluginHooker " + pluginHooker.getDescription().getVersion() + ": Plugin rekker (~DionaMC)"));
        sender.sendMessage(StringUtils.colorize(PREFIX + "Commands:"));
        for (SubCommand subCommand : commands) {
            sender.sendMessage(StringUtils.colorize(PREFIX + "/ph " + subCommand.getName() + " &f- &b" + subCommand.getDescription()));
        }
    }
}
