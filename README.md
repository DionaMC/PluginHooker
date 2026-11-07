# PluginHooker v2

PluginHooker is a per-player plugin sandbox for Bukkit/Spigot/Paper servers (1.8.8 ~ 26.3), powered by the **Java 25 Class-File API (`java.lang.classfile`)** and **`byte-buddy-agent`**.

[Discord](https://discord.gg/fdmkfts) | [QQ群](https://jq.qq.com/?_wv=1027&k=dhEQrZZW) | [简体中文](README_zh_CN.md)

## Features

* **Hook Bukkit Events**: Per-player isolation at `RegisteredListener#callEvent` with lock-free `ClassValue` `MethodHandle` player extraction.
* **Hook Bukkit Player & Entity Lookup APIs**: Hide disabled players from hooked plugins in `Bukkit.getOnlinePlayers()`, `Bukkit.matchPlayer()`, `Bukkit.getPlayer(...)`, `World#getPlayers()`, `World#getPlayersSeeingChunk(...)`, `World#getEntities()`, `World#getLivingEntities()`, `World#getEntitiesByClass(...)`, and `getNearbyEntities(...)`.
* **Hook Bukkit Player Side-Effects**: Block disabled plugins from interfering with players via `Player#kickPlayer` / `Player#kick(Component)`, `Bukkit.dispatchCommand`, `Player#teleport` / `Player#teleportAsync`, `Player#setVelocity`, `Player#sendMessage`, `Player.Spigot#sendMessage`, and `Bukkit.broadcast`.
* **Hook Netty Pipeline**: Direct context-level interception (`AbstractChannelHandlerContext`) supporting `addFirst`, `addLast`, `addBefore`, `addAfter`, and `replace` without replacing or breaking underlying handlers.
* **Hook ProtocolLib**: Inbound & outbound `PacketListener` interception.
* **Hook PacketEvents**: Supports both standalone and shaded/relocated PacketEvents instances.
* **Whitelist & Blacklist Modes**: Configure each hooked plugin as default-disabled (`WHITELIST`) or default-enabled (`BLACKLIST`), with configurable post-join grace ticks (`join-grace-ticks`) for anticheat `PlayerData` initialization.

## Requirements & Compatibility

* **Java**: 25+
* **Server**: Spigot / Paper 1.8.8 ~ 26.3
* **Netty**: 4.0 / 4.1 / 4.2
* **ProtocolLib**: 5.4.0
* **PacketEvents**: 2.14.0 (standalone or shaded)

## Commands

* `/ph plugin <add|remove|mode|list> [plugin] [WHITELIST|BLACKLIST]` - Manage hooked plugins and default sandbox mode
* `/ph player <player> <add|remove|reset|list> [plugin]` - Manage per-player plugin sandbox state
* `/ph debug` - Toggle sandbox interception debug logs

## API Usage

### Gradle (`:hooker-api`)

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.DionaMC.PluginHooker:hooker-api:2.0.0")
}
```

### Maven (`:hooker-api`)

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.DionaMC.PluginHooker</groupId>
    <artifactId>hooker-api</artifactId>
    <version>2.0.0</version>
    <scope>provided</scope>
</dependency>
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
import dev.diona.pluginhooker.api.event.PlayerKickHookEvent;
import dev.diona.pluginhooker.api.event.PluginDispatchCommandEvent;
import dev.diona.pluginhooker.api.event.ProtocolLibHookEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ExampleHookListener implements Listener {

    @EventHandler
    public void onPlayerKickHook(PlayerKickHookEvent event) {
        // Inspect which plugin kicked the player and the reason (String or Adventure Component)
        String pluginName = event.getPlugin().getName();
        String reason = event.getReason();
    }

    @EventHandler
    public void onDispatchCommand(PluginDispatchCommandEvent event) {
        // Inspect or cancel commands dispatched by hooked plugins
    }

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
