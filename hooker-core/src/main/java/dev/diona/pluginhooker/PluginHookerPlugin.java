package dev.diona.pluginhooker;

import dev.diona.pluginhooker.agent.PatchManager;
import dev.diona.pluginhooker.api.PluginHookerAPI;
import dev.diona.pluginhooker.api.plugin.HookMode;
import dev.diona.pluginhooker.command.PluginHookerCommand;
import dev.diona.pluginhooker.config.HookConfig;
import dev.diona.pluginhooker.listener.PluginLifecycleListener;
import dev.diona.pluginhooker.service.PlayerRegistryImpl;
import dev.diona.pluginhooker.service.PluginRegistryImpl;
import dev.diona.pluginhooker.util.PlayerChannelResolver;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main Bukkit JavaPlugin entry point for PluginHooker v2.
 */
@Getter
public final class PluginHookerPlugin extends JavaPlugin implements PluginHookerAPI.Provider {

    @Getter
    private static PluginHookerPlugin instance;

    private final HookConfig hookConfig;
    private final PluginRegistryImpl pluginRegistry;
    private final PlayerRegistryImpl playerRegistry;
    private final PatchManager patchManager;

    public PluginHookerPlugin() {
        instance = this;

        this.hookConfig = new HookConfig();
        this.hookConfig.load(this);

        this.pluginRegistry = new PluginRegistryImpl(this.hookConfig);
        this.playerRegistry = new PlayerRegistryImpl(this.pluginRegistry, this.hookConfig);
        this.pluginRegistry.setPlayerRegistry(this.playerRegistry);

        PluginHookerAPI.setProvider(this);

        getLogger().info("Initializing PluginHooker v2 bytecode instrumentation...");
        this.patchManager = new PatchManager(
                getLogger(),
                this.hookConfig,
                this.pluginRegistry,
                this.playerRegistry,
                getClass().getClassLoader()
        );
        this.patchManager.initialize();
    }

    @Override
    public void onEnable() {
        this.pluginRegistry.refreshClassLoaderCache();
        applyConfiguredPlugins();

        // Register real network online players if reloaded
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (PlayerChannelResolver.isOpenRealNetworkChannel(PlayerChannelResolver.getChannel(online))) {
                this.playerRegistry.registerPlayer(online);
            }
        }

        if (this.hookConfig.isBstats()) {
            new Metrics(this, 17654);
        }

        PluginCommand command = getCommand("pluginhooker");
        if (command != null) {
            PluginHookerCommand executor = new PluginHookerCommand(this, this.pluginRegistry, this.playerRegistry);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        Bukkit.getPluginManager().registerEvents(
                new PluginLifecycleListener(this, this.hookConfig, this.pluginRegistry),
                this
        );
    }

    public void reloadSandboxConfig() {
        this.hookConfig.load(this);
        this.patchManager.reloadPatches();
        this.pluginRegistry.refreshClassLoaderCache();
        applyConfiguredPlugins();
    }

    public void applyConfiguredPlugins() {
        if (this.hookConfig.getConfiguredPlugins().isEmpty()) {
            return;
        }
        for (Plugin target : Bukkit.getPluginManager().getPlugins()) {
            if (target == null || target.equals(this)) {
                continue;
            }
            HookMode mode = this.hookConfig.getConfiguredPluginMode(target.getName());
            if (mode == null) {
                continue;
            }
            if (!this.pluginRegistry.isHooked(target)) {
                this.pluginRegistry.registerPlugin(target, mode);
                getLogger().info("Auto-hooked configured plugin: " + target.getName() + " [" + mode + "]");
            } else if (this.pluginRegistry.getHookMode(target) != mode) {
                this.pluginRegistry.setHookMode(target, mode);
                getLogger().info("Updated configured plugin mode: " + target.getName() + " [" + mode + "]");
            }
        }
    }

    @Override
    public boolean isAllowed(Plugin plugin, Player player) {
        return this.playerRegistry.isAllowed(plugin, player);
    }

    @Override
    public void onDisable() {
        this.patchManager.shutdown();
    }
}
