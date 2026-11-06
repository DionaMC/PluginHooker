package dev.diona.pluginhooker.config;

import dev.diona.pluginhooker.api.plugin.HookMode;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;

/**
 * Strongly-typed configuration holder for PluginHooker v2.
 */
@Getter
public final class HookConfig {

    private boolean bstats = true;
    private int joinGraceTicks = 10;
    private HookMode defaultHookMode = HookMode.WHITELIST;

    @Accessors(fluent = true)
    private final BukkitHookSettings bukkit = new BukkitHookSettings();
    @Accessors(fluent = true)
    private final ApiHookSettings bukkitApi = new ApiHookSettings();
    @Accessors(fluent = true)
    private final PacketHookSettings protocolLib = new PacketHookSettings();
    @Accessors(fluent = true)
    private final PacketHookSettings packetEvents = new PacketHookSettings();
    @Accessors(fluent = true)
    private final PacketHookSettings netty = new PacketHookSettings();

    public void load(JavaPlugin plugin) {
        try {
            File dataFolder = plugin.getDataFolder();
            File configFile = new File(dataFolder, "config.yml");
            if (!configFile.exists()) {
                plugin.saveResource("config.yml", false);
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);

            this.bstats = yaml.getBoolean("bstats", true);
            this.joinGraceTicks = Math.max(0, yaml.getInt("sandbox.join-grace-ticks", 10));
            String modeStr = yaml.getString("sandbox.default-mode", "WHITELIST");
            try {
                this.defaultHookMode = HookMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                this.defaultHookMode = HookMode.WHITELIST;
            }

            this.bukkit.enabled = yaml.getBoolean("hook.bukkit.enabled", true);
            this.bukkit.callEvent = yaml.getBoolean("hook.bukkit.call-event", false);
            this.bukkit.useReflectionToGetPlayer = yaml.getBoolean("hook.bukkit.use-reflection-to-get-event-player", true);

            this.bukkitApi.enabled = yaml.getBoolean("hook.bukkit-api.enabled", true);
            this.bukkitApi.hideFromOnlinePlayers = yaml.getBoolean("hook.bukkit-api.hide-from-online-players", true);
            this.bukkitApi.hideFromWorldPlayers = yaml.getBoolean("hook.bukkit-api.hide-from-world-players", true);
            this.bukkitApi.hideFromGetPlayer = yaml.getBoolean("hook.bukkit-api.hide-from-get-player", true);

            this.protocolLib.enabled = yaml.getBoolean("hook.protocollib.enabled", true);
            this.protocolLib.callEvent = yaml.getBoolean("hook.protocollib.call-event", false);

            this.packetEvents.enabled = yaml.getBoolean("hook.packetevents.enabled", true);
            this.packetEvents.callEvent = yaml.getBoolean("hook.packetevents.call-event", false);

            this.netty.enabled = yaml.getBoolean("hook.netty.enabled", true);
            this.netty.callEvent = yaml.getBoolean("hook.netty.call-event", false);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load config.yml: " + e.getMessage());
        }
    }

    public long getJoinGraceMillis() {
        return joinGraceTicks * 50L;
    }

    @Getter
    public static final class BukkitHookSettings {
        private boolean enabled = true;
        private boolean callEvent = false;
        private boolean useReflectionToGetPlayer = true;
    }

    @Getter
    public static final class ApiHookSettings {
        private boolean enabled = true;
        private boolean hideFromOnlinePlayers = true;
        private boolean hideFromWorldPlayers = true;
        private boolean hideFromGetPlayer = true;
    }

    @Getter
    public static final class PacketHookSettings {
        private boolean enabled = true;
        private boolean callEvent = false;
    }
}
