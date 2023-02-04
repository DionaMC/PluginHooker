package io.github.dionatestserver.pluginhooker.config;

import io.github.dionatestserver.pluginhooker.PluginHooker;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.Field;

public class ConfigManager {

    private final YamlConfiguration config = new YamlConfiguration();

    public ConfigManager() {
        try {
            File configFile = new File(PluginHooker.getInstance().getDataFolder(), "config.yml");
            if (!configFile.exists()) {
                PluginHooker.getInstance().saveResource("config.yml", false);
            }

            config.load(configFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadConfig(Class<?> targetClass) {
        for (Field field : targetClass.getFields()) {

            ConfigPath annotation = field.getAnnotation(ConfigPath.class);
            if (annotation == null) continue;

            Class<?> type = field.getType();
            try {
                if (type == int.class || type == Integer.class) {
                    field.set(null, config.getInt(annotation.value()));
                } else if (type == long.class || type == Long.class) {
                    field.set(null, config.getLong(annotation.value()));
                } else if (type == double.class || type == Double.class) {
                    field.set(null, config.getDouble(annotation.value()));
                } else if (type == boolean.class || type == Boolean.class) {
                    field.set(null, config.getBoolean(annotation.value()));
                } else if (type == String.class) {
                    field.set(null, config.getString(annotation.value()));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}
