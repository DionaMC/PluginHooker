# PluginHooker

PluginHooker 是一个 Bukkit 插件，它能够为开发者提供一种便捷的方式来控制玩家的各种监听器。
[Discord](https://discord.gg/fdmkfts)
[QQ群](https://jq.qq.com/?_wv=1027&k=dhEQrZZW)

[English](README.md)

## 功能

* Hook Bukkit 事件
* Hook ProtocolLib 事件
* Hook PacketEvents 事件
* Hook Netty pipeline 
* 为每个玩家独立控制监听器

## 已测试环境

* Spigot: 1.8.8/1.19.4
* Netty: 4.0/4.1
* ProtocolLib: 5.3
* PacketEvents: 2.7.0

## 用法

### Maven
将以下repository添加到你的pom.xml文件内:

```xml
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
```

然后添加以下依赖

```xml
    <dependency>
        <groupId>com.github.DionaMC</groupId>
        <artifactId>PluginHooker</artifactId>
        <version>1.3.1</version>
    </dependency>
```
### Gradle
将以下repository添加到你的build.gradle文件内:
```groovy
    maven {
        url = uri('https://jitpack.io')
    }
```

然后添加以下依赖

```groovy
    compileOnly 'com.github.DionaMC:PluginHooker:1.3.1'
```

### API 用法

为玩家启用/禁用指定的插件

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

如果要拦截或在事件被执行前执行自定义的操作,请添加一个事件监听器:
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

## 特别感谢

* [Poke](https://github.com/Pokemonplatin) 提供了hook事件相关的帮助和需要hook的事件列表
