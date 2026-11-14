# H02 播放进度保存接口设计

日期为 2026-11-14，时区为 Asia/Shanghai。

设计核对基线为 `main @ 0c854d96cad44c960575605bdf1033cd56dc2572`，工作目录为 `D:\code\Java-code\projects\music`。设计开始时存在一项既有工作区改动，`.gitignore` 增加 `docs/learning` 忽略规则。

用户已确认设备独立保存、整数秒精度、播放权限、客户端持久 UUID、过期请求保护、自然结束归零、上报时机、未知时长处理、方案 A，以及接口契约、乱序与重试、持久化与事务、限流与验证四部分设计。用户于 2026-11-15 确认书面设计并要求开始，当前形成[实施计划](../plans/2026-11-15-playback-progress-save-plan.md)，待审阅后逐任务实施。

## 1. 目标与交付范围

设计一个接口 `PUT /api/v1/users/me/playback-progress/{deviceId}`，保存当前登录用户在指定逻辑设备上的最近播放歌曲、整秒位置和最后上报序号。

数据粒度为每个用户、每个设备一份最近播放状态。用户最初选择每个账号一份状态，随后明确要求设备进程结束再打开时恢复本设备状态，并确认调整为用户与设备联合维度。

本轮交付为保存接口的后端代码、数据库迁移、测试、OpenAPI、中文 API 文档及独立审核材料。任务书 H02 的读取接口另行设计与实施。相关资料为[后端任务书](../../tasks/backend-roadmap.md)、[现有播放接口](../../api/track/stream-track.md)及[收藏接口事务实现](../../../src/main/java/org/example/music/favorite/service/FavoriteTrackTransactionService.java)。

## 2. 方案比较与选择

| 方案 | 顺序依据 | 客户端责任与工程成本 | 决定 |
| --- | --- | --- | --- |
| A，客户端持久递增序号 | 同一用户与设备按 `sequence` 比较事件先后 | 持久保存序号，重启接续，多页面原子分配，重试复用原内容与序号 | 用户选定 |
| B，服务端版本校验 | 请求携带已知服务端版本，冲突时重新同步 | 客户端处理版本同步，并判断并发事件中哪份内容应继续保存 | 比较方案 |
| C，播放会话加序号 | 服务端会话代际与会话内序号 | 定义会话建立、接管、失效和会话内上报规则 | 比较方案 |

采用方案 A，保存事件的先后由客户端共享分配的序号确定。主动向后拖动、切歌和自然结束均可提交更大序号，位置可以减小。

浏览器可通过 IndexedDB 读写事务协调多个标签页的序号分配。其重叠作用域读写事务受事务调度规则保护，适合将序号分配与事件内容持久化组合为一个本地事务。[IndexedDB 事务调度](https://w3c.github.io/IndexedDB/#transaction-scheduling)

## 3. 设备与客户端接入约定

### 3.1 设备身份

- 客户端首次启动时生成随机 UUID 并持久保存，后续进程重启和账号重新登录继续使用。
- 浏览器中的逻辑设备对应本站点的持久化存储环境。不同浏览器、不同用户配置文件和独立应用安装分别生成 UUID。
- 清除应用数据或重新安装后生成新的 UUID，并为新设备建立序号。
- UUID 使用标准的 36 字符连字符格式，接收大小写十六进制并规范化为小写。
- 数据归属始终使用服务端认证主体中的用户 ID。相同 UUID 在不同账号下分别保存。
- 设备 UUID 是账号内的逻辑分组标识。客户端使用当前账号认证，为该账号指定 UUID 对应的记录提交保存。

### 3.2 序号与事件

- 客户端按当前账号与设备持久保存序号，从 1 开始，每产生一次保存事件分配更大序号，允许跳号。
- 发送前完成序号及对应事件内容的本地持久化。重新登录、进程重启和切歌均接续该设备在当前账号下的序号。
- 同一账号、同一设备标识下的多个标签页协调分配序号，最终保存最大的已成功处理序号对应的事件。
- 网络重试复用原事件的歌曲、位置和序号。
- 收到过期或冲突响应后，丢弃对应旧事件。客户端分配器以本地序号与 `currentSequence` 的较大值为基础，后续为当前有效状态分配新序号。
- 本地设备身份与序号作为同一生命周期的数据管理。设备数据整体清除后，使用新的 UUID 和初始序号。
- 序号达到协议上限时，客户端生成新设备 UUID，并从序号 1 建立新的逻辑设备记录。

### 3.3 保存时机与秒级精度

- 正常播放期间目标为每 5 秒保存一次。
- 暂停、拖动完成、切歌和自然播放结束时立即产生保存事件。
- 客户端将非负播放位置向下取整为秒。例如 83.9 秒上报 83。
- 切歌事件保存新歌曲及其当前整秒位置，通常为 0。
- 自然结束事件保留该歌曲，使用更大序号保存 0 秒。接近结尾时主动暂停或拖动，保存实际整秒位置。
- 后台调度、进程终止及网络状况影响实际送达时间，服务端持久化的状态由最后成功处理的有效事件决定。

## 4. HTTP 契约

### 4.1 方法、路径与认证

```text
PUT /api/v1/users/me/playback-progress/{deviceId}
Authorization: Bearer ${ACCESS_TOKEN}
Content-Type: application/json
```

需要有效 JWT，沿用签名、过期时间、issuer、audience、token version 和登录角色校验。用户 ID 来自 `AuthenticatedUserPrincipal.userId()`，角色来自 `loginRole()`。

Service 使用方法级认证保护。当前认证主体提供用户和登录角色，设备 UUID 由请求路径提供。当前登录策略允许同账号多个有效 Token 并存。

| 当前登录角色 | 可保存的歌曲状态 |
| --- | --- |
| USER，含歌曲上传者 | PUBLISHED |
| ADMIN | DRAFT、PUBLISHED、ARCHIVED |
| 管理员以 USER 登录 | PUBLISHED |

歌曲不存在或当前角色不可见时，统一返回 404 `TRACK_NOT_FOUND`。歌曲校验使用数据库中的歌曲状态与时长。

### 4.2 请求体

```json
{
  "trackId": 123,
  "positionSeconds": 83,
  "sequence": 42
}
```

| 字段 | Java 表达 | 必填 | 校验 |
| --- | --- | --- | --- |
| 路径 deviceId | 规范化 UUID 字符串 | 是 | 标准 8-4-4-4-12 十六进制格式，接收后转小写 |
| trackId | Long | 是 | JSON 整数，1 至 9223372036854775807 |
| positionSeconds | Long | 是 | JSON 整数，0 至 9223372036854775807，并执行有效时长上限校验 |
| sequence | Long | 是 | JSON 整数，1 至 9007199254740991 |

请求体为恰好包含三个业务字段的 JSON 对象，字段顺序自由。字段缺失、null、重复、未知字段、数字字符串、小数、科学计数法、错误 JSON 结构和数值溢出均返回 400 `VALIDATION_FAILED`。请求字段使用 Bean Validation，HTTP 解析同时保证整数 token 语义。

支持 `application/json` 及其字符集参数。其他媒体类型返回 415 `UNSUPPORTED_MEDIA_TYPE`。

### 4.3 时长边界

当 `durationMs > 0` 时，要求 `positionSeconds <= durationMs / 1000`，采用整数除法。比较通过除法完成，避免将请求秒数乘以 1000 引起溢出。

| 数据库 durationMs | 合法 positionSeconds |
| --- | --- |
| 215430 | 0 至 215 |
| 1000 | 0 至 1 |
| 900 | 0 |
| null 或 0 | 请求类型范围内的非负整数 |

当前数据库允许历史歌曲时长为空或 0，用户已确认这两类数据允许保存非负整数秒。

### 4.4 成功响应

首次保存、更新及当前相同事件的重复请求均返回 200。响应为 DTO，时间由服务端生成，使用 UTC、ISO-8601。

```json
{
  "deviceId": "bcf04f9c-3876-4a77-8f6d-632ebd78db2e",
  "trackId": 123,
  "positionSeconds": 83,
  "sequence": 42,
  "updatedAt": "2026-11-14T08:30:00.123456Z"
}
```

成功响应设置 `Cache-Control: no-store`。`updatedAt` 表示最近一次真正保存新事件的服务器时间，精度与数据库微秒列一致。

## 5. 顺序、幂等与冲突

在用户与设备联合维度比较请求序号。

| 当前记录与请求关系 | 结果 |
| --- | --- |
| 首次保存 | 创建记录，返回 200 |
| 请求序号大于最后序号 | 保存歌曲、位置、新序号及更新时间，返回 200 |
| 请求序号等于最后序号，歌曲及位置相同 | 返回已保存结果，保留原审计字段与更新时间 |
| 请求序号等于最后序号，歌曲或位置不同 | 409 `PLAYBACK_PROGRESS_SEQUENCE_CONFLICT` |
| 请求序号小于最后序号 | 409 `PLAYBACK_PROGRESS_STALE_SEQUENCE` |

两种 409 均在 `ProblemDetail` 中包含 `currentSequence`，值为本次事务持锁比较时的最后序号。成功响应同样反映本次事务处理的事件，后续并发事件可继续推进记录。

重试仍执行当前歌曲可见性与时长校验。当原歌曲已经删除或对当前登录角色隐藏时，原请求返回 404。已有更大序号保存成功后，较小序号的旧重试按过期规则返回 409。

例如事件 20 保存歌曲 123 的 100 秒，事件 21 保存同一歌曲的 30 秒。两者以任意网络到达顺序处理，事件 21 成功后，最终位置为 30 秒。

首次并发创建时，同序号同内容可均返回 200，同序号不同内容由一个请求保存成功、另一个返回 409。对已成功保存的事件，最终保留最大序号对应的状态。事务失败的事件可按原内容和序号重试。

## 6. 数据模型与生命周期

新增 `user_playback_progress` 表。设计时已有迁移为 V1 至 V10，新文件预计为 `V11__create_user_playback_progress.sql`，实施前核对实际最大版本号。

| 列 | 数据库类型 | 约束与用途 |
| --- | --- | --- |
| user_id | INT | 非空，用户 ID，与 device_id 组成主键 |
| device_id | CHAR(36) | 非空，小写 UUID，ASCII 二进制排序规则 |
| track_id | BIGINT | 可空，当前歌曲外键 |
| position_seconds | BIGINT | 非空，非负，最后保存的整秒位置 |
| last_sequence | BIGINT | 非空，1 至 9007199254740991 |
| created_at | DATETIME(6) | 非空，UTC，首次保存时间 |
| updated_at | DATETIME(6) | 非空，UTC，最近新事件保存时间 |
| created_by | INT | 非空，创建记录的当前用户 |
| updated_by | INT | 非空，最近新事件保存的当前用户 |

联合主键为 `(user_id, device_id)`，另建 `track_id` 索引支撑外键与歌曲删除处理。非负秒数与合法序号范围设置数据库检查约束。

数据库 CHECK 表达式使用 `position_seconds` 与 `last_sequence`，关联完整性由 `user_id` 和 `track_id` 外键保证。[MySQL CHECK 约束](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)

用户外键使用 `ON DELETE CASCADE`。歌曲外键使用 `ON DELETE SET NULL`，MySQL 支持将被删除父行对应的可空外键置空。[MySQL 外键动作](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)

歌曲物理删除后保留设备行、最后序号、最后位置和保存时间，`track_id IS NULL` 表示当前缺少可恢复歌曲。后续有效的新事件覆盖该设备状态。设备行继续保护序号，避免删除记录后延迟旧请求重新成为首次写入。

`position_seconds` 的非负约束独立于 `track_id` 的可空状态，歌曲外键置空后保持可执行。归档歌曲保留关联记录，后续保存按当前角色的歌曲可见性判断。

## 7. 分层与数据流

在 `org.example.music.playback` 下组织本接口的 controller、dto、service、repository、exception 和 config，按现有项目风格编写。

- Controller 解析设备标识、严格请求体与 Bean Validation，调用 Service 并构造 200 DTO 响应。
- 外层 Service 执行方法级认证、调用 Redis 限流及事务服务，统一转换事务开始、执行和提交阶段的数据访问异常。
- 事务 Service 负责歌曲可见性、时长、序号规则和数据库事务。
- Repository 以参数化 SQL 访问当前用户、设备及歌曲对应记录。
- 全局异常处理器为本接口新增业务异常映射 `ProblemDetail`，保持稳定错误码及 `urn:problem:music:*` 类型。

处理顺序为认证、路径与请求体基础校验、消费保存额度、数据库事务、成功响应。有效时长上限校验依赖数据库，位于消费额度后的事务内。

事务内执行以下步骤。

1. 按歌曲主键以共享锁读取状态和时长，检查当前登录角色可见性与有效时长上限。
2. 使用联合主键配合原子创建或等价的数据库操作建立并锁定设备行，已有记录保持当前内容，随后在持锁状态读取最后序号。
3. 根据序号与内容判定更新、幂等成功或冲突。新事件写入后读取实际持久化值，构造响应。
4. 提交成功后返回。校验及冲突异常触发回滚。

歌曲共享锁允许多个进度保存事务共同读取同一歌曲，并与歌曲状态修改和物理删除协调。设备行写锁串行化同一用户设备的状态更新。[MySQL 锁定读取](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)

原子创建可沿用收藏仓库的 `INSERT ... ON DUPLICATE KEY UPDATE` 思路。业务结果根据持锁后的记录内容与序号判断，受影响行数具有驱动相关语义，需要结合实际记录判定。[MySQL 重复键更新](https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html)

时间通过注入的 `Clock` 生成并截取到微秒，UTC 转换沿用现有 DATETIME 绑定约定。数据库死锁、锁超时、连接及提交异常由外层服务映射存储不可用，客户端可按原事件重试。

## 8. 限流与失败语义

使用现有 Redis 运行依赖和原子 Lua 计数模式。保存额度按用户共享，键为 `music:playback-progress:user:{userId}`，所有设备及当前登录角色共用。

配置项为 `app.playback-progress.rate-limit-per-minute`，环境变量为 `PLAYBACK_PROGRESS_RATE_LIMIT_PER_MINUTE`，默认 120，合法范围为正整数。

采用首次请求建立的固定 60 秒窗口。允许请求增加计数，窗口内拒绝请求保持窗口到期时间。限流返回 429，`Retry-After` 为窗口剩余秒数向上取整，最小为 1。

通过认证及请求基础校验的每次保存尝试消费额度，包括幂等重试、过期序号、内容冲突、歌曲不可见和时长超限请求。数据库处理失败时已消费额度保留。

Redis 连接、脚本或计数状态异常返回 503 `PLAYBACK_PROGRESS_RATE_LIMIT_UNAVAILABLE`。额度检查成功后进入数据库事务，数据库访问与事务异常返回 503 `PLAYBACK_PROGRESS_STORE_UNAVAILABLE`。

## 9. 错误响应与可观测性

错误响应使用 `application/problem+json`，携带 `code`，`type` 沿用 `urn:problem:music:` 加错误码小写格式。当前接口新增业务异常响应设置 `Cache-Control: no-store`。

| HTTP 状态 | 稳定错误码 | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 路径、请求体、数值范围或有效时长上限校验失败 |
| 401 | UNAUTHORIZED、INVALID_TOKEN、TOKEN_REVOKED | 未认证、Token 无效、账号版本或登录角色失效，沿用认证体系 |
| 403 | FORBIDDEN | 沿用现有方法授权检查 |
| 404 | TRACK_NOT_FOUND | 歌曲不存在或当前登录角色不可见 |
| 409 | PLAYBACK_PROGRESS_STALE_SEQUENCE | 请求序号较旧 |
| 409 | PLAYBACK_PROGRESS_SEQUENCE_CONFLICT | 相同序号对应不同歌曲或位置 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 请求媒体类型不支持 |
| 429 | PLAYBACK_PROGRESS_RATE_LIMITED | 账号保存额度耗尽，携带 Retry-After |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证阶段无法读取账号授权状态 |
| 503 | PLAYBACK_PROGRESS_RATE_LIMIT_UNAVAILABLE | 保存额度无法可靠检查 |
| 503 | PLAYBACK_PROGRESS_STORE_UNAVAILABLE | 数据库操作或事务失败 |
| 500 | INTERNAL_ERROR | 沿用现有未预期异常响应 |

未认证请求优先返回认证错误。账号授权状态检查发生在保存额度检查之前。通过请求基础校验后，保存额度检查先于业务数据库事务。事务中，歌曲可见性及有效时长校验先于序号判定。

409 示例为本接口新增错误格式。

```json
{
  "type": "urn:problem:music:playback_progress_stale_sequence",
  "title": "播放进度上报已过期",
  "status": 409,
  "detail": "该设备已保存更新的播放状态",
  "instance": "/api/v1/users/me/playback-progress/bcf04f9c-3876-4a77-8f6d-632ebd78db2e",
  "code": "PLAYBACK_PROGRESS_STALE_SEQUENCE",
  "currentSequence": 43
}
```

关键错误日志使用 SLF4J，包含 `traceId` 和异常类型。请求内容与认证凭据按现有敏感信息约定保护。

## 10. 实施涉及的文件与依赖

- `src/main/java/org/example/music/playback/` 下新增本接口所需的 Controller、请求与响应 DTO、严格解析组件、Service、事务 Service、Repository、配置与业务异常。
- `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java` 增加本接口异常映射。
- `src/main/resources/db/migration/V11__create_user_playback_progress.sql` 新建进度表，版本号以实施时核对为准。
- `src/main/resources/application.properties` 和 `.env.example` 增加限流额度配置。
- `src/test/java/org/example/music/playback/` 下新增 HTTP、业务、仓库与真实依赖集成测试，必要时补充现有异常响应回归用例。
- `docs/api/playback/save-playback-progress.md` 记录完整契约、权限、请求响应、错误码与可执行 curl。
- `docs/superpowers/plans/` 形成单接口实施计划，`docs/reviews/` 保存正式审核请求、报告和意见处置。
- 实施收尾按项目协议登记任务书与 `PROJECT_STATE.md`。

运行依赖使用项目当前 Java 21、Spring Web、Validation、Security、Data JPA、Redis、Flyway 和 MySQL 8.4。接口元数据使用现有 springdoc。

## 11. 验证与完成条件

### 11.1 HTTP 与业务验证

- 首次创建、更新、重复请求，以及更新时间和审计字段语义。
- 无认证、无效或撤销 Token、当前登录角色变化、管理员以 USER 登录。
- 已发布共享歌曲、普通用户访问草稿或归档歌曲、管理员全部状态及歌曲不存在。
- 两个用户使用相同设备 UUID 的隔离，同一用户两个设备保存不同歌曲和位置的隔离。
- UUID 大小写规范化、格式错误、字段缺失、null、重复、未知字段、数组、额外 JSON、小数、科学计数法、数字字符串及溢出。
- 0 秒、Long 类型边界、序号 1 与上限、向后拖动、切歌、自然结束归零。
- 正常时长、小于 1 秒、整秒与非整秒时长、时长为空或 0、恰好边界与超限。
- 旧序号、同号同内容、同号不同内容、跳号，以及跨切歌的乱序事件。

### 11.2 真实 MySQL 与 Redis 验证

- Flyway 在 MySQL 8.4 执行新迁移，核对联合主键、外键、检查约束与审计字段。
- 并发首次创建，同号相同及不同内容，不同序号反向到达，已有记录并发更新。
- 歌曲状态变更、物理删除与保存并发，验证已删除关联为空、最后序号保留以及延迟旧请求被拒绝。
- 删除歌曲后的原请求重试、删除用户后的级联清理、事务失败回滚和提交阶段异常。
- 不同时区及微秒时间往返，幂等重试保留首次成功的更新时间。
- Redis 真实窗口、所有设备共用账号额度、跨账号隔离、边界额度、到期恢复、并发计数、TTL 与状态异常。
- Redis 或数据库不可用时的 503 契约及安全日志行为。

### 11.3 完整验证与独立审核

实施完成后执行完整项目验证。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat check
```

记录实际通过、失败、错误、跳过数量，核对 MySQL、Redis、MinIO 必要集成测试执行情况。完成 OpenAPI 与中文 API 文档后，按 `AGENTS.md` 向本机 Claude Code CLI 提交独立只读审核并持续跟进。

正式审核保留范围、基线、实际命令、XML 数量、session_id、modelUsage、每轮原文和反馈处置证据。接口完成依据为项目完成定义和有效审核结论 `accepted`。

## 12. 书面审阅时需核对的具体项

业务选择与四部分方案已由用户逐项确认。书面版本进一步明确了 UUID 大小写规范化、Long 数值范围、严格 JSON 解析、序号耗尽后的设备身份更新、媒体类型 415、固定窗口细节、数据库锁定顺序及歌曲删除后的空关联表达。这些技术细节随本文交由用户核对。

用户于 2026-11-15 确认书面设计，已按 writing-plans 技能形成本接口实施计划。
