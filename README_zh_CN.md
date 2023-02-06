![!ntro.jpg](https://s2.loli.net/2022/05/21/2Ds3fyzdZVGvFrK.jpg)

# PluginHooker

PluginHooker 是一个提供简单的方式来 hook 事件的 Bukkit 插件

[English](README.md)

## 功能

* 分玩家 hook 事件
* Hook Bukkit 事件
* Hook ProtocolLib 事件
* Hook Netty pipeline

## 已测试版本

* Spigot 1.8.8 + 最新版ProtocolLib

## 用法

将PluginHooker作为Maven依赖项 ([Jitpack](https://jitpack.io/#Diona-testserver/PluginHooker))
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.DionaMC</groupId>
        <artifactId>PluginHooker</artifactId>
        <version>1.1.0</version>
    </dependency>
</dependencies>
```


添加/移除需要被hook的插件
```java
public void hookPlugin() {
    PluginHooker.getPluginManager().addPlugin(pluginToHook);
}

public void unHookPlugin() {
    PluginHooker.getPluginManager().removePlugin(pluginToHook);
}
```

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

如果要拦截或在事件被执行时执行自定义的操作,请添加一个事件监听器:
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
