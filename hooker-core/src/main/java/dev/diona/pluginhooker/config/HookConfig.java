package dev.diona.pluginhooker.config;

import dev.diona.pluginhooker.api.plugin.HookMode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strongly-typed configuration holder for PluginHooker v2.
 */
@Getter
public final class HookConfig {

    private volatile boolean bstats = true;
    @Setter
    private volatile boolean debug = false;
    private volatile int joinGraceTicks = 5;
    private volatile HookMode defaultHookMode = HookMode.WHITELIST;
    private volatile Map<String, HookMode> configuredPlugins = Collections.emptyMap();

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
            loadFromYaml(YamlConfiguration.loadConfiguration(configFile));
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load config.yml: " + e.getMessage());
        }
    }

    public void loadFromYaml(YamlConfiguration yaml) {
        this.bstats = yaml.getBoolean("bstats", true);
        this.debug = yaml.getBoolean("sandbox.debug", false);
        this.joinGraceTicks = Math.max(0, yaml.getInt("sandbox.join-grace-ticks", 5));
        this.defaultHookMode = parseModeOrDefault(yaml.getString("sandbox.default-mode", "WHITELIST"), HookMode.WHITELIST);
        this.configuredPlugins = parseConfiguredPlugins(yaml, this.defaultHookMode);

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
        this.bukkitApi.blockDisabledCloseInventory = yaml.getBoolean("hook.bukkit-api.block-disabled-close-inventory", true);

        this.protocolLib.enabled = yaml.getBoolean("hook.protocollib.enabled", true);
        this.protocolLib.callEvent = yaml.getBoolean("hook.protocollib.call-event", false);

        this.packetEvents.enabled = yaml.getBoolean("hook.packetevents.enabled", true);
        this.packetEvents.callEvent = yaml.getBoolean("hook.packetevents.call-event", false);

        this.netty.enabled = yaml.getBoolean("hook.netty.enabled", true);
        this.netty.callEvent = yaml.getBoolean("hook.netty.call-event", false);
    }

    public HookMode getConfiguredPluginMode(String pluginName) {
        if (pluginName == null || pluginName.isEmpty()) {
            return null;
        }
        return configuredPlugins.get(pluginName.toLowerCase(Locale.ROOT));
    }

    private static Map<String, HookMode> parseConfiguredPlugins(YamlConfiguration yaml, HookMode fallbackMode) {
        Map<String, HookMode> parsed = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("sandbox.plugins");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                HookMode mode = parseModeOrDefault(section.getString(key), fallbackMode);
                parsed.put(key.trim().toLowerCase(Locale.ROOT), mode);
            }
            return Collections.unmodifiableMap(parsed);
        }

        if (yaml.isList("sandbox.plugins")) {
            List<String> items = yaml.getStringList("sandbox.plugins");
            for (String entry : items) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    String name = entry.substring(0, colon).trim();
                    String modeStr = entry.substring(colon + 1).trim();
                    if (!name.isEmpty()) {
                        parsed.put(name.toLowerCase(Locale.ROOT), parseModeOrDefault(modeStr, fallbackMode));
                    }
                } else {
                    parsed.put(entry.trim().toLowerCase(Locale.ROOT), fallbackMode);
                }
            }
        }
        return parsed.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(parsed);
    }

    private static HookMode parseModeOrDefault(String rawMode, HookMode fallback) {
        if (rawMode == null || rawMode.isBlank()) {
            return fallback;
        }
        try {
            return HookMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public long getJoinGraceMillis() {
        return joinGraceTicks * 50L;
    }

    @Getter
    @Setter
    public static final class BukkitHookSettings {
        private volatile boolean enabled = true;
        private volatile boolean callEvent = false;
        private volatile boolean useReflectionToGetPlayer = true;
    }

    @Getter
    @Setter
    public static final class ApiHookSettings {
        private volatile boolean enabled = true;
        private volatile boolean hideFromOnlinePlayers = false;
        private volatile boolean hideFromWorldPlayers = false;
        private volatile boolean hideFromGetPlayer = false;
        private volatile boolean hideFromEntityLists = false;
        private volatile boolean blockDisabledKick = true;
        private volatile boolean callKickEvent = true;
        private volatile boolean blockDisabledDispatchCommand = true;
        private volatile boolean callDispatchCommandEvent = true;
        private volatile boolean blockDisabledTeleport = true;
        private volatile boolean blockDisabledVelocity = true;
        private volatile boolean blockDisabledSendMessage = true;
        private volatile boolean blockDisabledCloseInventory = true;
    }

    @Getter
    @Setter
    public static final class PacketHookSettings {
        private volatile boolean enabled = true;
        private volatile boolean callEvent = false;
    }
}
