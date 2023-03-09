package dev.diona.pluginhooker;

import dev.diona.pluginhooker.commands.SimpleCommand;
import dev.diona.pluginhooker.config.ConfigManager;
import dev.diona.pluginhooker.config.ConfigPath;
import dev.diona.pluginhooker.hook.HookerManager;
import dev.diona.pluginhooker.listeners.PlayerListener;
import dev.diona.pluginhooker.player.PlayerManager;
import dev.diona.pluginhooker.plugin.PluginManager;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class PluginHooker extends JavaPlugin {

    @Getter
    private static PluginHooker instance;

    @Getter
    private static HookerManager hookerManager;
    @Getter
    private static PluginManager pluginManager;
    @Getter
    private static PlayerManager playerManager;
    @Getter
    private static ConfigManager configManager;

    @ConfigPath("bstats")
    public boolean enabledBstats;

    public PluginHooker() {
        instance = this;

        configManager = new ConfigManager();
        pluginManager = new PluginManager();
        playerManager = new PlayerManager();
        this.getLogger().info("PluginHooker loaded! start hooking...");
        hookerManager = new HookerManager();
        configManager.loadConfig(this);
    }

    @Override
    public void onEnable() {
        if (enabledBstats) {
            new Metrics(this, 17654);
        }
        // register command
        Bukkit.getPluginCommand("pluginhooker").setExecutor(new SimpleCommand());
        Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
