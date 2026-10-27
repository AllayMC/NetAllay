# 更新日志

记录 NetAllay 对使用者可见的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.2] - 未发布

### 新增

- **皮肤反作弊**（`ConfirmSkinPacket` 方案）：拦截 Allay 发往网易客户端的皮肤确认包，把需要校验的
  皮肤扣下，先向 `/pe/web/skin-md5-valid-inner` 提交 `md5(base64(皮肤数据))`、`md5(geometry)` 与
  玩家的 NetEase UID，仅对通过校验的皮肤补发确认。未通过或校验失败的皮肤不再确认，客户端保持显示
  默认皮肤。
  - persona / `.NonsyncCustom` 皮肤、NPC 与假人（`trustNonPlayerSkins`）、`whitelistUids` 中的 UID
    按官方方案跳过校验。
  - 同一皮肤的并发请求按 `uuid:skinMd5:geoMd5:uid` 合并（同一皮肤向多个观看者确认只发一次请求），
    但**不缓存结果**——官方接口同时校验皮肤组件是否真的归该玩家所有，必须每次校验。
  - `geo_list` 与 `md5_list` 保持下标对齐；没有 geometry 的皮肤自动单独成批，不带该字段。
  - 新增运行时 API：`NetAllay#isSkinAntiCheatEnabled()`、`NetAllay#setSkinAntiCheatEnabled(boolean)`；
    `reloadConfig()` 会让新配置同步生效。
  - 新增配置节 `skinAntiCheat`：`enabled`、`sign`、`testServer`、`webServerUrl`、`timeoutMillis`、
    `maxBatchSize`、`checkGeometry`、`skipPersonaSkin`、`checkUnknownSkin`、`failOpenOnError`、
    `onErrorRetries`、`trustNonPlayerSkins`、`whitelistUids`、`logDeniedSkin`、`logPassedSkin`。
- 新增 `testServer` 开关，可切到永远返回"合法"的测试环境接口先验证链路。
- 构建支持环境变量 `ALLAY_SERVER_JAR`，用本地服务端 jar 参与编译。

### 变更

- 编译基线调整为已发布的 Allay `api 0.29.0` / `server 0.14.0`。`ConfirmSkinPacket` 位于
  `org.cloudburstmc.protocol.bedrock.packet`，仍使用 `org.allaymc.protocol.extension.packet`
  的旧版服务端不再兼容。
- `reloadConfig()` 的日志补充皮肤反作弊状态。

### 修复

- **引擎自带 PyRpc 包的日志刷屏**：`ClientSetMotion` 这类由客户端引擎按帧发出的包，details 数组只有
  1 个元素，原先会被判为畸形并以 WARN 级别逐包打印。tick 模式的自定义重力类客户端 Mod 每秒可产生
  20~33 条，且随在线人数线性增长，全部写在 Netty IO 线程上。
  - 收包路径改为先用 `PyRpcCodec#peekEventType` 只读 eventType：既不是 `ModEventC2S/ModEventS2C`、
    也没有注册 `engine/callback/<eventType>` 处理器的包，在解码前直接丢弃。
  - 解码器把 details 长度为 1~2 的包按 `engine/callback/<eventType>` 正常返回（payload 放进
    `rawData`）；只有 mod 事件缺少 namespace/system/event 字段时才判为畸形。
  - 所有 `[Decode]` 告警改为按 key 限流（30s 窗口，并汇报期间被抑制的条数），客户端无法再通过异常包
    刷日志、占用 IO 线程。
- 引擎回调（`StoreBuySuccServerEvent`、`UrgeShipEvent` 等）与 mod 事件路由、处理器分发行为不变。

### 文档

- README 增加「皮肤反作弊」章节：工作原理、完整配置表、运行时 API、部署要求。
- README 补充 `logPassedSkin` 说明，便于排查校验链路是否真的在工作。
- 新增本更新日志，并加入 `.gitignore`（构建产物、IDE 与本地配置）。
