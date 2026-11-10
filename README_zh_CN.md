# PluginHooker v2

PluginHooker 是一个面向 Bukkit/Spigot/Paper (1.8.8 ~ 26.3) 的按玩家粒度插件沙箱，基于 **Java 25 Class-File API (`java.lang.classfile`)** 与 **`byte-buddy-agent`** 构建。

[Discord](https://discord.gg/fdmkfts) | [QQ群](https://jq.qq.com/?_wv=1027&k=dhEQrZZW) | [English](README.md)

## 功能特性

* **Hook Bukkit 事件**：拦截 `RegisteredListener#callEvent`，结合无锁 `ClassValue` + `MethodHandle` 自动提取事件关联玩家。
* **Hook Bukkit 玩家与实体查询 API**：支持在 `Bukkit.getOnlinePlayers()`、`Bukkit.matchPlayer()`、`Bukkit.getPlayer(...)`、`World#getPlayers()`、`World#getPlayersSeeingChunk(...)`、`World#getEntities()`、`World#getLivingEntities()`、`World#getEntitiesByClass(...)` 及 `getNearbyEntities(...)` 中对未启用的受控插件隐藏指定玩家。
* **Hook Bukkit 与 NMS 玩家副作用 API**：阻断未启用的受控插件对玩家调用 `Player#kickPlayer` / `Player#kick(Component)`、`Bukkit.dispatchCommand`、`Player#teleport` / `Player#teleportAsync`、`Player#setVelocity`、`Player#sendMessage`、`Player.Spigot#sendMessage`、`Bukkit.broadcast` 以及直接调用 NMS `PlayerConnection#sendPacket` / `ServerCommonPacketListenerImpl#send`。
* **Hook NMS 连接生命周期**：直接插桩 `PlayerConnection.<init>` / `ServerGamePacketListenerImpl.<init>`，在服务端发出第一个 `JOIN_GAME` 包之前即完成玩家注册、Netty `Channel` 绑定并触发 `PlayerHookRegisterEvent`，通过 `channel.closeFuture()` 自动清理断线玩家，并对假人/Bot（如 `EmptyChannel`）实行 0 宽限期保护。
* **Hook Netty Pipeline 与通道关闭**：直接在 `AbstractChannelHandlerContext` 调用点拦截读写，完整支持 `addFirst`、`addLast`、`addBefore`、`addAfter`、`replace`，拦截未启用插件的底层 `ByteBuf` 直写，并拦截 `Channel#close` / `ChannelHandlerContext#close` 触发 `PlayerChannelCloseHookEvent`（防止反作弊直接关闭 Netty 连接踢人）。
* **Hook ProtocolLib**：接管同步与 `AsyncListenerHandler` 异步双向数据包监听器，并拦截未启用插件主动调用 `sendServerPacket` / `receiveClientPacket` / `sendWirePacket`。
* **Hook PacketEvents**：同时支持独立插件版与被其他插件打包（Shaded/Relocated）的 PacketEvents（涵盖 `EventManager`、`PacketReceiveEvent`、`PacketSendEvent` 及 `ProtocolManagerImpl#sendPacket` 主动发包拦截）。
* **双沙箱策略与只读进服宽限期**：支持按插件设置 `WHITELIST`（默认禁用，手动开启）或 `BLACKLIST`（默认启用，手动关闭），并提供可配置的 `join-grace-ticks` 进服初始化宽限期——在宽限期内未启用插件仅以**只读模式**观察初始化包（自动回滚 `setCancelled`、数据包修改、ProtocolLib `NetworkMarker` 计划包以及 PacketEvents `postTasks` / `tasksAfterSend` 回调任务），彻底杜绝未启用反作弊在进服阶段发送 `Transaction` 包或取消 `PacketPlayOutPosition`。

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
    compileOnly("com.github.DionaMC.PluginHooker:hooker-api:2.0.11")
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
    <version>2.0.11</version>
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
import dev.diona.pluginhooker.api.event.PlayerChannelCloseHookEvent;
import dev.diona.pluginhooker.api.event.PlayerHookRegisterEvent;
import dev.diona.pluginhooker.api.event.PlayerKickHookEvent;
import dev.diona.pluginhooker.api.event.PluginDispatchCommandEvent;
import dev.diona.pluginhooker.api.event.ProtocolLibHookEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ExampleHookListener implements Listener {

    @EventHandler
    public void onPlayerHookRegister(PlayerHookRegisterEvent event) {
        // 在 PlayerConnection.<init> / ServerGamePacketListenerImpl.<init> 末尾、JOIN_GAME 发送前触发
        // 可在此处提前启用玩家选中的插件，使其原生接收完整的进服数据包流
    }

    @EventHandler
    public void onPlayerKickHook(PlayerKickHookEvent event) {
        // 获取是哪个受控插件踢出了玩家及踢出原因（兼容 String 与 Adventure Component）
        String pluginName = event.getPlugin().getName();
        String reason = event.getReason();
    }

    @EventHandler
    public void onPlayerChannelClose(PlayerChannelCloseHookEvent event) {
        // 监听或拦截受控插件直接关闭玩家 Netty Channel（如 user.closeConnection()）
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
