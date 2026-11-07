package dev.diona.pluginhooker.api.plugin;

import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Manages which plugins are under PluginHooker's sandbox control.
 */
public interface PluginRegistry {

    /**
     * Registers a plugin into the sandbox using the default hook mode.
     *
     * @param plugin the plugin to hook
     * @return true if newly added, false if already hooked
     */
    boolean registerPlugin(Plugin plugin);

    /**
     * Registers a plugin into the sandbox with a specific hook mode.
     *
     * @param plugin the plugin to hook
     * @param mode   the default hook mode for this plugin
     * @return true if newly added, false if already hooked (updates mode)
     */
    boolean registerPlugin(Plugin plugin, HookMode mode);

    /**
     * Unregisters a plugin from the sandbox and clears player overrides for it.
     *
     * @param plugin the plugin to unhook
     * @return true if removed, false if it was not hooked
     */
    boolean unregisterPlugin(Plugin plugin);

    /**
     * Checks whether the given plugin is currently hooked by the sandbox.
     *
     * @param plugin the plugin to check
     * @return true if hooked
     */
    boolean isHooked(Plugin plugin);

    /**
     * Gets the configured {@link HookMode} for a hooked plugin.
     *
     * @param plugin the plugin to query
     * @return the hook mode, or null if not hooked
     */
    HookMode getHookMode(Plugin plugin);

    /**
     * Updates the {@link HookMode} for a hooked plugin.
     *
     * @param plugin the hooked plugin
     * @param mode   the new mode
     */
    void setHookMode(Plugin plugin, HookMode mode);

    /**
     * Checks whether at least one plugin is currently hooked.
     *
     * @return true if one or more plugins are hooked
     */
    boolean hasHookedPlugins();

    /**
     * Returns an unmodifiable view of all currently hooked plugins.
     *
     * @return set of hooked plugins
     */
    Set<Plugin> getHookedPlugins();

    /**
     * Resolves a Bukkit {@link Plugin} from its {@link ClassLoader} in O(1) time.
     *
     * @param classLoader the class loader
     * @return the owning plugin, or null if not a plugin class loader
     */
    Plugin getPluginByClassLoader(ClassLoader classLoader);
}
