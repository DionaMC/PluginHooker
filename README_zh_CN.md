# PluginHooker v2

PluginHooker 是一个面向 Bukkit/Spigot/Paper (1.8.8 ~ 26.3) 的按玩家粒度插件沙箱，基于 **Java 25 Class-File API (`java.lang.classfile`)** 与 **`byte-buddy-agent`** 构建。

[Discord](https://discord.gg/fdmkfts) | [QQ群](https://jq.qq.com/?_wv=1027&k=dhEQrZZW) | [English](README.md)

## 功能特性

* **Hook Bukkit 事件**：拦截 `RegisteredListener#callEvent`，结合无锁 `ClassValue` + `MethodHandle` 自动提取事件关联玩家。
* **Hook Bukkit 玩家与实体查询 API**：支持在 `Bukkit.getOnlinePlayers()`、`Bukkit.matchPlayer()`、`Bukkit.getPlayer(...)`、`World#getPlayers()`、`World#getPlayersSeeingChunk(...)`、`World#getEntities()`、`World#getLivingEntities()`、`World#getEntitiesByClass(...)` 及 `getNearbyEntities(...)` 中对未启用的受控插件隐藏指定玩家。
* **Hook Bukkit 玩家副作用 API**：阻断未启用的受控插件对玩家调用 `Player#kickPlayer` / `Player#kick(Component)`、`Bukkit.dispatchCommand`、`Player#teleport` / `Player#teleportAsync`、`Player#setVelocity`、`Player#sendMessage`、`Player.Spigot#sendMessage` 以及 `Bukkit.broadcast`。
* **Hook Netty Pipeline**：直接在 `AbstractChannelHandlerContext` 调用点拦截读写，完整支持 `addFirst`、`addLast`、`addBefore`、`addAfter`、`replace`，无需替换原 `ChannelHandler`，保留全部生命周期与卸载兼容性。
* **Hook ProtocolLib**：接管双向数据包监听器。
* **Hook PacketEvents**：同时支持独立插件版与被其他插件打包（Shaded/Relocated）的 PacketEvents。
* **双沙箱策略与进服宽限期**：支持按插件设置 `WHITELIST`（默认禁用，手动开启）或 `BLACKLIST`（默认启用，手动关闭），并提供可配置的 `join-grace-ticks` 进服初始化宽限期，防止反作弊因缺失初始化包报错。

## 运行环境要求

* **Java**: 25+
* **服务端**: Spigot / Paper 1.8.8 ~ 26.3
* **Netty**: 4.0 / 4.1 / 4.2
* **ProtocolLib**: 5.4.0
* **PacketEvents**: 2.14.0（独立安装或 Shaded）

## 指令列表

* `/ph plugin <add|remove|mode|list> [plugin] [WHITELIST|BLACKLIST]` - 管理受控插件与默认沙箱策略
* `/ph player <player> <add|remove|reset|list> [plugin]` - 管理单个玩家的插件启用/禁用状态
* `/ph debug` - 在线开关沙箱拦截调试日志输出

## API 用法

### Gradle 依赖 (`:hooker-api`)

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.DionaMC.PluginHooker:hooker-api:2.0.3")
}
```

### Maven 依赖 (`:hooker-api`)

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.DionaMC.PluginHooker</groupId>
    <artifactId>hooker-api</artifactId>
    <version>2.0.3</version>
    <scope>provided</scope>
</dependency>
```

### 控制插件与玩家状态

```java
import dev.diona.pluginhooker.api.PluginHookerAPI;
import dev.diona.pluginhooker.api.player.HookedPlayer;
import dev.diona.pluginhooker.api.plugin.HookMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class SandboxExample {

    public void registerAnticheat(Plugin anticheatPlugin) {
        // WHITELIST 模式：默认对所有玩家禁用该插件，需按玩家开启
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

### 监听沙箱拦截事件

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
        // 获取是哪个受控插件踢出了玩家及踢出原因（兼容 String 与 Adventure Component）
        String pluginName = event.getPlugin().getName();
        String reason = event.getReason();
    }

    @EventHandler
    public void onDispatchCommand(PluginDispatchCommandEvent event) {
        // 监听或拦截受控插件执行的后台命令
    }

    @EventHandler
    public void onBukkitHook(BukkitHookEvent event) {
        // 在受控插件接收到 Bukkit 事件前自定义处理或取消
    }

    @EventHandler
    public void onProtocolLibHook(ProtocolLibHookEvent event) {
        // 在受控插件接收到 ProtocolLib 数据包前自定义处理或取消
    }
}
```

## 特别感谢

* [Poke](https://github.com/Pokemonplatin) 提供了 Hook 事件相关的帮助和需要 Hook 的事件列表。
