package dev.diona.pluginhooker.patch.impl.packetevents;

import com.github.retrooper.packetevents.event.PacketListenerCommon;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.utils.BukkitUtils;
import lombok.Getter;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@Getter
public class EventManagerCallbackHandler {

    private static EventManagerCallbackHandler instance;

    private final Map<Integer, Plugin> listenerToPluginMap = new HashMap<>();

    public EventManagerCallbackHandler() {
        PluginHooker.getConfigManager().loadConfig(this);
    }

    public void handleListenerRegister(PacketListenerCommon listener) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (int i = 4; i < stackTraceElements.length; i++) {
            if (stackTraceElements[i].getClassName().startsWith("io.github.retrooper.packetevents")
                    || stackTraceElements[i].getClassName().startsWith("com.github.retrooper.packetevents")) {
                continue;
            }

            try {
                Class<?> aClass = Class.forName(stackTraceElements[i].getClassName());

                // WTF
                if (aClass.getClassLoader() == null || aClass.getClassLoader() == PluginHooker.class.getClassLoader()) {
                    continue;
                }

                // check if the class is loaded by the plugin classloader
                if (!aClass.getClassLoader().getClass().getSimpleName().equals("PluginClassLoader")) {
                    continue;
                }

                List<Plugin> pluginList = BukkitUtils.getServerPlugins();

                for (Plugin plugin : pluginList) {
                    // check if the plugin is loaded by the same classloader
                    if (plugin.getClass().getClassLoader() != aClass.getClassLoader()) {
                        continue;
                    }
                    // System.out.println("Plugin: " + plugin.getName() +"  registered a " + listener.getClass().getSimpleName() + " Listener!");
                    listenerToPluginMap.put(listener.hashCode(), plugin);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            break;
        }
    }

    public void handleListenerUnregister(PacketListenerCommon listener) {
        this.listenerToPluginMap.remove(listener.hashCode());
    }

    @Nullable
    public Plugin getPlugin(PacketListenerCommon listener) {
        return this.listenerToPluginMap.get(listener.hashCode());
    }

    public static EventManagerCallbackHandler getInstance() {
        return instance != null ? instance : (instance = new EventManagerCallbackHandler());
    }
}
