# PluginHooker

PluginHooker is a Bukkit plugin that aims to provide an ultimately simple and better method to hook Bukkit events and ProtocolLib PacketEvents  
[Discord](https://discord.gg/fdmkfts)
[QQ群](https://jq.qq.com/?_wv=1027&k=dhEQrZZW)

## Localization

* [简体中文](README_zh_CN.md)

## Features

* Hook Bukkit events
* Hook ProtocolLib events/packets
* Hook PacketEvents events
* Hook Netty pipeline

## Tested environment

* Spigot: 1.8.8/1.21.11
* Netty: 4.0/4.1/4.2
* ProtocolLib: 5.4.0
* PacketEvents: 2.11.1

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
        <version>1.4.1</version>
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
    compileOnly 'com.github.DionaMC:PluginHooker:1.4'
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
