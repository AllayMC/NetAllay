# NetAllay

![Build](https://github.com/AllayMC/NetAllay/actions/workflows/gradle.yml/badge.svg)
![Maven Central Version](https://img.shields.io/maven-central/v/org.allaymc/net-allay?label=net-allay)

NetAllay 是一个用于 AllayMC 服务器的网易 PyRpc 通信 API 插件，提供类似于 NukkitMaster 的通信接口，让插件开发者能够轻松地与网易版 Minecraft 客户端进行双向通信。

## 功能特性

- 监听客户端发送的 PyRpc 事件
- 向单个/多个玩家发送服务端事件
- 支持按位置范围广播事件
- 支持按世界/维度广播事件
- 自动处理 MsgPack 编解码
- 线程安全的事件注册机制

## 安装

1. 从 GitHub Releases 下载 `NetAllay-x.x.x.jar`
2. 将 jar 文件放入服务器的 `plugins` 目录
3. 重启服务器

## 作为前置依赖

如果你的插件需要使用 NetAllay，可以直接通过 Maven Central 引入：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.allaymc:net-allay:<version>")
}

allay {
    plugin {
        dependency("NetAllay")
    }
}
```

## API 使用

### 获取实例

```java
import org.allaymc.netallay.NetAllay;

// 方式一：直接获取静态实例
NetAllay netAllay = NetAllay.getInstance();

// 方式二：通过插件管理器获取
NetAllay netAllay = (NetAllay) Server.getInstance()
    .getPluginManager()
    .getPlugin("NetAllay");
```

### 监听客户端事件

使用 `listenForEvent` 方法注册事件监听器，当客户端发送匹配的事件时，回调函数会被调用。

```java
netAllay.listenForEvent(
    "MyMod",           // namespace - 客户端系统的命名空间
    "MySystemCS",      // systemName - 客户端系统名称
    "OnButtonClick",   // eventName - 事件名称
    (player, data) -> {
        // player - 发送事件的玩家
        // data - 事件数据 (Map<String, Object>)

        String buttonId = (String) data.get("buttonId");
        pluginLogger.info("玩家 {} 点击了按钮: {}", player.getOriginName(), buttonId);
    }
);
```

### 取消监听

```java
// 取消特定处理器
netAllay.unlistenForEvent(namespace, systemName, eventName, handler);

// 取消某个事件的所有处理器
netAllay.unlistenAllForEvent(namespace, systemName, eventName);
```

### 发送事件给单个玩家

```java
Map<String, Object> data = new LinkedHashMap<>();
data.put("message", "Hello from server!");
data.put("timestamp", System.currentTimeMillis());

boolean success = netAllay.notifyToClient(
    player,            // 目标玩家
    "MyMod",           // namespace - 客户端监听的命名空间
    "MySystemSS",      // systemName - 服务端系统名称
    "ServerMessage",   // eventName - 事件名称
    data               // 事件数据
);
```

#### 立即发送（不缓冲）

```java
netAllay.notifyToClientImmediately(player, namespace, systemName, eventName, data);
```

### 发送事件给多个玩家

```java
List<Player> players = Arrays.asList(player1, player2, player3);

int successCount = netAllay.notifyToMultiClients(
    players,
    "MyMod",
    "MySystemSS",
    "Announcement",
    Map.of("content", "服务器公告内容")
);
```

### 发送事件给附近玩家

```java
Location3dc center = player.getControlledEntity().getLocation();

int count = netAllay.notifyToClientsNearby(
    null,              // except - 排除的玩家，可为 null
    center,            // 中心位置
    50.0,              // 半径（方块）
    "MyMod",
    "MySystemSS",
    "NearbyEvent",
    Map.of("type", "explosion")
);
```

### 广播事件

#### 广播到指定世界（所有维度）

```java
World world = Server.getInstance().getWorldPool().getWorld("world");

netAllay.broadcastToAllClient(
    null,              // except - 排除的玩家
    world,             // 目标世界
    "MyMod",
    "MySystemSS",
    "WorldEvent",
    data
);
```

#### 广播到指定维度

```java
Dimension dimension = world.getOverWorld();

netAllay.broadcastToAllClient(
    exceptPlayer,      // 排除的玩家
    dimension,         // 目标维度
    "MyMod",
    "MySystemSS",
    "DimensionEvent",
    data
);
```

#### 广播到整个服务器

```java
netAllay.broadcastToAllClient(
    null,              // except - 排除的玩家
    "MyMod",
    "MySystemSS",
    "GlobalEvent",
    data
);
```

## 数据类型支持

事件数据 `Map<String, Object>` 支持以下值类型：

| Java 类型 | 说明 |
|-----------|------|
| `null` | 空值 |
| `Boolean` | 布尔值 |
| `Integer` / `Long` | 整数 |
| `Float` / `Double` | 浮点数 |
| `String` | 字符串 |
| `byte[]` | 二进制数据 |
| `Map<String, Object>` | 嵌套对象 |
| `List<?>` / `Iterable<?>` | 数组 |

## 特殊常量

### 本地玩家 Entity ID

```java
// 使用 -2 表示接收消息的玩家自己的实体
NetAllay.LOCAL_PLAYER_ENTITY_ID  // 值为 -2
```

**注意：** 只能在 `notifyToClient` 中使用 -2，不要在广播方法中使用，因为 -2 对每个玩家代表不同的实体。

## 完整示例

```java
package com.example.myplugin;

import org.allaymc.api.eventbus.EventHandler;
import org.allaymc.api.eventbus.event.server.PlayerJoinEvent;
import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.server.Server;
import org.allaymc.netallay.NetAllay;

import java.util.LinkedHashMap;
import java.util.Map;

public class MyPlugin extends Plugin {

    @Override
    public void onEnable() {
        Server.getInstance().getEventBus().registerListener(this);

        // 注册 PyRpc 事件监听
        registerPyRpcHandlers();

        pluginLogger.info("MyPlugin enabled!");
    }

    private void registerPyRpcHandlers() {
        NetAllay netAllay = NetAllay.getInstance();

        // 监听客户端请求
        netAllay.listenForEvent("MyMod", "MySystemCS", "RequestData", (player, data) -> {
            pluginLogger.info("收到来自 {} 的数据请求", player.getOriginName());

            // 构建响应数据
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("playerName", player.getOriginName());
            response.put("serverTime", System.currentTimeMillis());

            // 发送响应
            netAllay.notifyToClient(
                player,
                "MyMod",
                "MySystemSS",
                "ResponseData",
                response
            );
        });

        // 监听客户端操作
        netAllay.listenForEvent("MyMod", "MySystemCS", "PerformAction", (player, data) -> {
            String action = (String) data.get("action");

            switch (action) {
                case "jump" -> player.getControlledEntity().setMotion(0, 1, 0);
                case "heal" -> player.getControlledEntity().setHealth(20);
                default -> pluginLogger.warn("未知操作: {}", action);
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();

        if (player.isNetEasePlayer()) {
            // 向网易玩家发送欢迎消息
            NetAllay.getInstance().notifyToClient(
                player,
                "MyMod",
                "MySystemSS",
                "Welcome",
                Map.of(
                    "message", "欢迎来到服务器！",
                    "entityId", NetAllay.LOCAL_PLAYER_ENTITY_ID
                )
            );
        }
    }

    @Override
    public void onDisable() {
        // 清理事件监听
        NetAllay netAllay = NetAllay.getInstance();
        if (netAllay != null) {
            netAllay.unlistenAllForEvent("MyMod", "MySystemCS", "RequestData");
            netAllay.unlistenAllForEvent("MyMod", "MySystemCS", "PerformAction");
        }
    }
}
```

## 客户端对接

在网易客户端 Mod 中，使用以下方式与服务端通信：

### 监听服务端事件

```python
# Python 客户端示例
import mod.client.extraClientApi as clientApi

ClientSystem = clientApi.GetClientSystemCls()

class MyClientSystem(ClientSystem):
    def __init__(self, namespace, systemName):
        ClientSystem.__init__(self, namespace, systemName)
        self.ListenForEvent("MyMod", "MySystemSS", "ServerMessage", self, self.OnServerMessage)

    def OnServerMessage(self, args):
        message = args.get("message")
        print("收到服务端消息:", message)
```

### 发送事件到服务端

```python
def SendToServer(self, eventName, data):
    self.NotifyToServer("MyMod", "MySystemSS", eventName, data)

# 使用
self.SendToServer("RequestData", {"type": "player_info"})
```

## 注意事项

1. **线程安全**：NetAllay 的 API 是线程安全的，可以在任何线程调用
2. **玩家检查**：发送前会自动检查玩家是否为网易客户端，非网易玩家会被跳过
3. **广播限制**：在广播方法中不要使用 `-2` 作为 entityId
4. **生命周期**：在插件禁用时记得取消注册的事件监听器
5. **数据大小**：避免发送过大的数据包，建议单次数据不超过 64KB

## 协议说明

NetAllay 使用 MsgPack 格式编码数据，遵循网易 PyRpc 协议：

- 服务端到客户端事件类型：`ModEventS2C`
- 客户端到服务端事件类型：`ModEventC2S`
- 字符串编码：UTF-8 Binary 格式

## License

MIT License
