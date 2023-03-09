package dev.diona.pluginhooker.commands;

import dev.diona.pluginhooker.utils.StringUtils;
import lombok.Getter;
import org.bukkit.command.CommandSender;

import java.util.List;

import static dev.diona.pluginhooker.commands.SimpleCommand.PREFIX;

public abstract class SubCommand {

    @Getter
    private final String name;
    @Getter
    private final String description;
    @Getter
    private final String usage;

    public SubCommand(String name, String description, String usage) {
        this.name = name;
        this.description = description;
        this.usage = usage;
    }

    public abstract boolean onCommand(CommandSender sender, String[] args);

    public abstract List<String> onTabComplete(CommandSender sender, String[] args);

    public void sendUsage(CommandSender sender) {
        sender.sendMessage(StringUtils.colorize(PREFIX + "Usage: " + this.getUsage()));
    }
}
