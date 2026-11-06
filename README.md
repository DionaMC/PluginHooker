# PluginHooker v2

PluginHooker is a per-player plugin sandbox for Bukkit/Spigot/Paper servers (1.8.8 ~ 26.3), powered by the **Java 25 Class-File API (`java.lang.classfile`)** and **`byte-buddy-agent`**.

[Discord](https://discord.gg/fdmkfts) | [QQ群](https://jq.qq.com/?_wv=1027&k=dhEQrZZW) | [简体中文](README_zh_CN.md)

## Features

* **Hook Bukkit Events**: Per-player isolation at `RegisteredListener#callEvent` with lock-free `ClassValue` `MethodHandle` player extraction.
* **Hook Bukkit Player Lookup APIs**: Hide players from hooked plugins in `Bukkit.getOnlinePlayers()`, `World#getPlayers()`, and `Bukkit.getPlayer(...)`.
* **Hook Netty Pipeline**: Direct context-level interception (`AbstractChannelHandlerContext`) supporting `addFirst`, `addLast`, `addBefore`, `addAfter`, and `replace` without replacing or breaking underlying handlers.
* **Hook ProtocolLib**: Inbound & outbound `PacketListener` interception.
* **Hook PacketEvents**: Supports both standalone and shaded/relocated PacketEvents instances.
* **Whitelist & Blacklist Modes**: Configure each hooked plugin as default-disabled (`WHITELIST`) or default-enabled (`BLACKLIST`), with configurable post-join grace ticks (`join-grace-ticks`) for anticheat `PlayerData` initialization.

## Requirements & Compatibility

* **Java**: 25+
* **Server**: Spigot / Paper 1.8.8 ~ 26.3
* **Netty**: 4.0 / 4.1 / 4.2
* **ProtocolLib**: 4.x / 5.x
* **PacketEvents**: 2.x (standalone or shaded)

## API Usage

### Dependency (`:hooker-api`)

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.DionaMC.PluginHooker:hooker-api:2.0.0-SNAPSHOT")
}
```

### Registering & Controlling Hooked Plugins

```java
import dev.diona.pluginhooker.api.PluginHookerAPI;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class SandboxExample {

    public void registerAnticheat(Plugin anticheatPlugin) {
        // WHITELIST: blocked for all players by default unless explicitly enabled
        PluginHookerAPI.getPluginRegistry().registerPlugin(anticheatPlugin, HookMode.WHITELIST);
    }

    public void enableForPlayer(Player player, Plugin anticheatPlugin) {
        HookedPlayer hookedPlayer = PluginHookerAPI.getPlayer(player);
        if (hookedPlayer != null) {
            hookedPlayer.enablePlugin(anticheatPlugin);
        }
    }

    public void disableForPlayer(Player player, Plugin anticheatPlugin) {
        HookedPlayer hookedPlayer = PluginHookerAPI.getPlayer(player);
        if (hookedPlayer != null) {
            hookedPlayer.disablePlugin(anticheatPlugin);
        }
    }
}
```

### Listening to Hook Events

```java
import dev.diona.pluginhooker.api.event.BukkitHookEvent;
import dev.diona.pluginhooker.api.event.ProtocolLibHookEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ExampleHookListener implements Listener {

    @EventHandler
    public void onBukkitHook(BukkitHookEvent event) {
        // Inspect or cancel before the hooked plugin receives the Bukkit event
    }

    @EventHandler
    public void onProtocolLibHook(ProtocolLibHookEvent event) {
        // Inspect or cancel before the hooked plugin receives the packet
    }
}
```

## Special Thanks

* [Poke](https://github.com/Pokemonplatin) for his help with event hooks and event lists.
