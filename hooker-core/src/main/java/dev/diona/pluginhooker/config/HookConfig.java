package dev.diona.pluginhooker.config;

import dev.diona.pluginhooker.api.plugin.HookMode;
import lombok.Getter;
import lombok.Setter;
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
    @Setter
    private volatile boolean debug = false;
    private int joinGraceTicks = 5;
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
            this.debug = yaml.getBoolean("sandbox.debug", false);
            this.joinGraceTicks = Math.max(0, yaml.getInt("sandbox.join-grace-ticks", 5));
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
            this.bukkitApi.hideFromOnlinePlayers = yaml.getBoolean("hook.bukkit-api.hide-from-online-players", false);
            this.bukkitApi.hideFromWorldPlayers = yaml.getBoolean("hook.bukkit-api.hide-from-world-players", false);
            this.bukkitApi.hideFromGetPlayer = yaml.getBoolean("hook.bukkit-api.hide-from-get-player", false);
            this.bukkitApi.hideFromEntityLists = yaml.getBoolean("hook.bukkit-api.hide-from-entity-lists", false);
            this.bukkitApi.blockDisabledKick = yaml.getBoolean("hook.bukkit-api.block-disabled-kick", true);
            this.bukkitApi.callKickEvent = yaml.getBoolean("hook.bukkit-api.call-kick-event", true);
            this.bukkitApi.blockDisabledDispatchCommand = yaml.getBoolean("hook.bukkit-api.block-disabled-dispatch-command", true);
            this.bukkitApi.callDispatchCommandEvent = yaml.getBoolean("hook.bukkit-api.call-dispatch-command-event", true);
            this.bukkitApi.blockDisabledTeleport = yaml.getBoolean("hook.bukkit-api.block-disabled-teleport", true);
            this.bukkitApi.blockDisabledVelocity = yaml.getBoolean("hook.bukkit-api.block-disabled-velocity", true);
            this.bukkitApi.blockDisabledSendMessage = yaml.getBoolean("hook.bukkit-api.block-disabled-send-message", true);

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
        private boolean hideFromOnlinePlayers = false;
        private boolean hideFromWorldPlayers = false;
        private boolean hideFromGetPlayer = false;
        private boolean hideFromEntityLists = false;
        private boolean blockDisabledKick = true;
        private boolean callKickEvent = true;
        private boolean blockDisabledDispatchCommand = true;
        private boolean callDispatchCommandEvent = true;
        private boolean blockDisabledTeleport = true;
        private boolean blockDisabledVelocity = true;
        private boolean blockDisabledSendMessage = true;
    }

    @Getter
    public static final class PacketHookSettings {
        private boolean enabled = true;
        private boolean callEvent = false;
    }
}
