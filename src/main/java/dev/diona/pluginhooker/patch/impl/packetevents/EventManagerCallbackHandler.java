package dev.diona.pluginhooker.patch.impl.packetevents;

import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import dev.diona.pluginhooker.PluginHooker;
import dev.diona.pluginhooker.config.ConfigManager;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EventManagerCallbackHandler {

    private static EventManagerCallbackHandler instance;

    private Map<Plugin, Set<PacketEvent>> pluginEventMap = new ConcurrentHashMap<>();

    public EventManagerCallbackHandler() {
        PluginHooker.getConfigManager().loadConfig(this);
    }

    public void handleListenerRegister(PacketListenerCommon event) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stackTraceElements.length; i++) {
            System.out.println(stackTraceElements[i]);
        }
    }

    public void handleListenerUnregister(PacketListenerCommon event) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stackTraceElements.length; i++) {
            System.out.println(stackTraceElements[i]);
        }
    }

    public static EventManagerCallbackHandler getInstance() {
        return instance != null ? instance : (instance = new EventManagerCallbackHandler());
    }
}
