# PluginHooker

PluginHooker is a Bukkit plugin that aims to provide an ultimately simple and better method to hook Bukkit events and ProtocolLib PacketEvents  
[Discord](https://discord.gg/fdmkfts)
[QQ群](https://jq.qq.com/?_wv=1027&k=dhEQrZZW)

## Localization

* [简体中文](README_zh_CN.md)

## Features

* Hook events for every player.
* Bukkit events included.
* ProtocolLib events/packets included.
* Hook Netty pipeline([PacketEvents](https://github.com/retrooper/packetevents) Supported)

## Tested environment

* Spigot: 1.8.8/1.19.4
* Netty: 4.0/4.1
* ProtocolLib: 4.8/5.0
* PacketEvents: 1.0/2.0

## Usage

### Maven
Add the following repository to your pom.xml:

```xml
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
```

Then add the following dependency

```xml
    <dependency>
        <groupId>com.github.DionaMC</groupId>
        <artifactId>PluginHooker</artifactId>
        <version>1.3</version>
    </dependency>
```
### Gradle
Add the following repository to your build.gradle:
```groovy
    maven {
        url = uri('https://jitpack.io')
    }
```

Then add the following dependency

```groovy
    compileOnly 'com.github.DionaMC:PluginHooker:1.3'
```

### API usage

Add/remove plugins that need to be hooked

```java
public void hookPlugin() {
    PluginHooker.getPluginManager().addPlugin(pluginToHook);
}

public void unHookPlugin() {
    PluginHooker.getPluginManager().removePlugin(pluginToHook);
}
```

Enable/disable the specified plugin for the player

```java
public void enablePluginForPlayer(Player player) {
    DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
    if (dionaPlayer == null) {
        return;
    }
    dionaPlayer.enablePlugin(pluginToHook);
}

public void disablePluginForPlayer(Player player) {
    DionaPlayer dionaPlayer = PluginHooker.getPlayerManager().getDionaPlayer(player);
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
