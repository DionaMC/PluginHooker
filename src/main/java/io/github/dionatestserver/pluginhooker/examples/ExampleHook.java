package io.github.dionatestserver.pluginhooker.examples;

import com.google.common.collect.Sets;
import io.github.dionatestserver.pluginhooker.DionaPluginHooker;
import io.github.dionatestserver.pluginhooker.plugin.DionaPlugin;
import org.bukkit.entity.Player;

public class ExampleHook {

    private final DionaPlugin pluginToHook = new ExamplePlugin();

    public void hookPlugin() {
        DionaPluginHooker.getPluginManager().addPlugin(pluginToHook);
    }

    public void unHookPlugin() {
        DionaPluginHooker.getPluginManager().removePlugin(pluginToHook);
    }

    public void switchPluginForPlayer(Player player) {
        DionaPluginHooker.getPluginManager().switchPlugins(player, Sets.newHashSet(pluginToHook));
    }

}
