# PluginHooker

PluginHooker is a Bukkit plugin that aims to provide an ultimately simple and better method to hook Bukkit events and ProtocolLib PacketEvents  
[Discord](https://discord.gg/fdmkfts)

## Localization

* [简体中文](README_zh_CN.md)

## Features

* Hook events for every player.
* Bukkit events included.
* ProtocolLib events/packets included.

## Tested Versions

* [Spigot 1.8.8](https://hub.spigotmc.org/stash/projects/SPIGOT/repos/spigot/browse?at=refs%2Fheads%2Fversion%2F1.8.8)
  with Latest ProtocolLib

## Usage

Add the following repository to your pom.xml:

```xml

<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
    ...
</repositories>
```

Then add the following dependency

```xml
<dependencies>
    <dependency>
        <groupId>com.github.Diona-testserver</groupId>
        <artifactId>PluginHooker</artifactId>
        <version>1.0.2</version>
    </dependency>
    ...
</dependencies>
```

Add/remove plugins that need to be hooked

```java
public void hookPlugin() {
    DionaPluginHooker.getPluginManager().addPlugin(pluginToHook);
}

public void unHookPlugin() {
    DionaPluginHooker.getPluginManager().removePlugin(pluginToHook);
}
```

Enable/disable the specified plugin for the player

```java
public void enablePluginForPlayer(Player player) {
    DionaPlayer dionaPlayer = DionaPluginHooker.getPlayerManager().getDionaPlayer(player);
    if (dionaPlayer == null) {
        return;
    }
    dionaPlayer.enablePlugin(pluginToHook);
}

public void disablePluginForPlayer(Player player) {
    DionaPlayer dionaPlayer = DionaPluginHooker.getPlayerManager().getDionaPlayer(player);
    if (dionaPlayer == null) {
        return;
    }
    dionaPlayer.disablePlugin(pluginToHook);
}
```

To intercept or perform a custom action when an event is executed, add an event listener

```java
public class ExampleListener implements Listener {

    @EventHandler
    public void onBukkitEvent(BukkitListenerEvent event) {
        // do something
    }

    @EventHandler
    public void onProtocolLibEvent(ProtocolLibPacketEvent event) {
        // do something
    }
}
```

## Special Thanks

* [Poke](https://github.com/Pokemonplatin) for his help with event hook and event list.
