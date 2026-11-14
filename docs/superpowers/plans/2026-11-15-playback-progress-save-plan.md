# H02 播放进度保存接口实施计划

> 执行者遵循 AGENTS.md，由 Codex 在本会话逐任务实施，Claude Code CLI 负责独立只读实现审核。步骤使用复选框跟踪实际结果。计划采用本会话原生执行方式，实施前按 writing-plans 完成用户计划审阅。

**目标** 实现 `PUT /api/v1/users/me/playback-progress/{deviceId}`，按当前用户与设备保存最近歌曲及整秒位置，并保护重复与乱序上报。

**架构** Controller 与严格请求读取器完成 HTTP 校验，外层 Service 在事务前消费 Redis 额度，再调用独立事务 Bean。事务内先锁定歌曲读取状态和时长，再原子创建与锁定设备行，按序号决定更新、幂等返回或冲突。MySQL 保存状态与顺序，Redis 保存账号共享额度。

**技术栈** Java 21、Spring Boot 4.1.1、Spring MVC、Validation、Security、Data JPA、Redis、Flyway、MySQL 8.4、Jackson 流式解析、JUnit 5、MockMvc、Testcontainers。版本与依赖来自当前 `build.gradle`。

**设计依据** [已确认的书面设计](../specs/2026-11-14-playback-progress-save-design.md)，用户于 2026-11-15 确认并要求开始。计划核对基线为 `main @ 4da9cf7210d349ee94e95587abdf96c618b754b6`，业务代码基线为 `0c854d96cad44c960575605bdf1033cd56dc2572`。工作区已有 `.gitignore` 增加 `docs/learning` 忽略规则的改动，送审材料披露该来源。当前阶段为计划待审阅，以下实施项均待执行。

## 全局约束

- 唯一接口为 `PUT /api/v1/users/me/playback-progress/{deviceId}`，请求三个字段为 `trackId`、`positionSeconds`、`sequence`。
- 用户身份使用服务端 `AuthenticatedUserPrincipal` 的 Integer userId 与 loginRole，歌曲 ID 保持 Long。
- USER 仅可保存 PUBLISHED，ADMIN 可保存全部现存状态，管理员以 USER 登录按 USER 处理，不存在或隐藏均为 404 `TRACK_NOT_FOUND`。
- deviceId 接收标准 36 字符 UUID，大小写十六进制均可，规范化为小写。数据主键为 `(user_id, device_id)`。
- trackId 为 1 至 9223372036854775807，positionSeconds 为 0 至 9223372036854775807，sequence 为 1 至 9007199254740991。
- JSON 字段顺序自由，三个数字均要求整数 token，重复、未知、缺失、null、小数、科学计数法、字符串、尾随 JSON 和溢出均为 400 `VALIDATION_FAILED`。
- 媒体类型为 application/json，允许媒体参数，其他类型与缺少类型为 415 `UNSUPPORTED_MEDIA_TYPE`。读取器使用 JSON 字节流解析方式处理正文。
- durationMs 大于 0 时，positionSeconds 上限为 `durationMs / 1000` 向下取整，时长为 null 或 0 时允许非负整数秒。
- 首次、更新及当前同号同内容请求均为 200。同号不同内容为 409 `PLAYBACK_PROGRESS_SEQUENCE_CONFLICT`，旧号为 409 `PLAYBACK_PROGRESS_STALE_SEQUENCE`，均含 currentSequence。
- 成功响应字段为 deviceId、trackId、positionSeconds、sequence、updatedAt，使用 UTC Instant。当前事件重试保留原时间与审计字段。
- 客户端正常播放每 5 秒产生保存事件，暂停、拖动完成、切歌、自然结束立即产生。自然结束保存同歌 0 秒。设备身份与序号持久化并在重启后接续，多页面原子分配共享序号。
- 新迁移使用 V11，实施前再次核对最大版本号。进度的 user_id 外键为 ON DELETE CASCADE，track_id 可空且为 ON DELETE SET NULL，保留设备最后序号。
- Redis 使用首次请求建立的固定 60 秒窗口，账号所有设备共享默认 120 次，属性为 `app.playback-progress.rate-limit-per-minute`，环境变量为 `PLAYBACK_PROGRESS_RATE_LIMIT_PER_MINUTE`。
- 通过请求基础校验的尝试消费额度，包含幂等、冲突、不可见、时长超限及数据库失败。额度依赖失败为 503 `PLAYBACK_PROGRESS_RATE_LIMIT_UNAVAILABLE`，事务失败为 503 `PLAYBACK_PROGRESS_STORE_UNAVAILABLE`。
- 新业务异常遵循 ProblemDetail、application/problem+json、稳定 code、urn:problem:music:*，成功与新增业务异常响应设置 no-store，日志保留 traceId。
- 分组测试用于开发定位，完整验收执行带 `MINIO_INTEGRATION_TESTS=true` 的 `.\gradlew.bat check`，按 XML 报告真实数量。
- 中文文档、提交说明及审核材料遵循项目协议，独立审核跟进至 accepted 或协议规定的 escalated。

## 审查重点

1. 转义后的重复字段、小数 1.0 与科学计数法在数值上接近整数，任务 1 固定拒绝这些 token，避免截断后写入。
2. 歌曲物理删除后 track_id 为空而序号仍存在，任务 2 与任务 4 固定旧号保存其他歌曲仍为 409，以及原歌曲重试为 404。
3. 并发同号请求内容可能不同，任务 2 与任务 4 固定胜者内容、冲突码及同内容重试的完整审计字段。
4. 相同 UUID 的大小写与跨账号复用，任务 4 固定同账号归并和不同账号隔离。
5. 数据库提交成功但调用方收到异常，任务 4 固定 503、实际已保存状态及原事件重试的幂等行为。

## 文件与接口结构

表中路径相对项目根目录。类位于 `org.example.music.playback` 对应子包，现有代码模式由所列参考文件提供。

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 新建 | src/main/java/org/example/music/playback/dto/SavePlaybackProgressRequest.java | 三个 Long 字段及 Bean Validation |
| 新建 | src/main/java/org/example/music/playback/dto/SavePlaybackProgressResponse.java | 五字段成功 DTO 与 Schema |
| 新建 | src/main/java/org/example/music/playback/controller/PlaybackProgressRequestReader.java | 媒体类型、流式严格整数 JSON 及 DTO 校验 |
| 新建 | src/main/java/org/example/music/playback/controller/PlaybackProgressController.java | UUID 校验、认证主体绑定、唯一 PUT 与 OpenAPI |
| 新建 | src/main/java/org/example/music/playback/repository/PlaybackProgressRepository.java | 参数化锁定、创建、更新及实际行读取 |
| 新建 | src/main/java/org/example/music/playback/repository/PlaybackProgressTrackRow.java | TrackStatus 与可空 Long durationMs |
| 新建 | src/main/java/org/example/music/playback/repository/PlaybackProgressRow.java | 设备、可空歌曲、位置、序号及 UTC Instant |
| 新建 | src/main/java/org/example/music/playback/service/PlaybackProgressTransactionService.java | 歌曲规则、序号比较和事务 |
| 新建 | src/main/java/org/example/music/playback/service/PlaybackProgressService.java | 方法认证、限流、事务代理调用与故障转换 |
| 新建 | src/main/java/org/example/music/playback/service/PlaybackProgressRateLimiter.java | 账号固定窗口原子额度 |
| 新建 | src/main/java/org/example/music/playback/config/PlaybackProgressProperties.java | 默认 120 的正整数配置 record |
| 新建 | src/main/java/org/example/music/playback/config/PlaybackProgressConfig.java | 注册配置类型 |
| 新建 | src/main/java/org/example/music/playback/exception/PlaybackProgressValidationException.java | 400 |
| 新建 | src/main/java/org/example/music/playback/exception/PlaybackProgressUnsupportedMediaTypeException.java | 415 |
| 新建 | src/main/java/org/example/music/playback/exception/PlaybackProgressStaleSequenceException.java | 旧序号 409 与 currentSequence |
| 新建 | src/main/java/org/example/music/playback/exception/PlaybackProgressSequenceConflictException.java | 同号异内容 409 与 currentSequence |
| 新建 | src/main/java/org/example/music/playback/exception/PlaybackProgressRateLimitedException.java | 429 与 retryAfterSeconds |
| 新建 | src/main/java/org/example/music/playback/exception/PlaybackProgressRateLimitUnavailableException.java | 额度依赖 503 |
| 新建 | src/main/java/org/example/music/playback/exception/PlaybackProgressStoreUnavailableException.java | 数据库及事务 503 |
| 修改 | src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java | 本接口新增异常的映射 |
| 新建 | src/main/resources/db/migration/V11__create_user_playback_progress.sql | 表、主键、外键、索引及秒数/序号 CHECK |
| 修改 | src/main/resources/application.properties、.env.example | 限流配置与环境变量 |
| 新建 | src/test/java/org/example/music/playback/controller/PlaybackProgressRequestReaderTest.java | 严格请求解析 |
| 新建 | src/test/java/org/example/music/playback/controller/PlaybackProgressControllerTest.java | MockMvc HTTP 与错误格式 |
| 新建 | src/test/java/org/example/music/playback/service/PlaybackProgressTransactionServiceTest.java | 权限、时长与序号业务 |
| 新建 | src/test/java/org/example/music/playback/service/PlaybackProgressServiceTest.java | 编排和错误转换 |
| 新建 | src/test/java/org/example/music/playback/service/PlaybackProgressProxyTest.java | 真实方法安全与事务代理 |
| 新建 | src/test/java/org/example/music/playback/service/PlaybackProgressRateLimiterTest.java | 脚本结果与故障转换 |
| 新建 | src/test/java/org/example/music/playback/service/PlaybackProgressRateLimiterIntegrationTest.java | 真实 Redis 窗口、并发及异常状态 |
| 新建 | src/test/java/org/example/music/playback/config/PlaybackProgressConfigTest.java | 默认值、覆盖和非法配置 |
| 新建 | src/test/java/org/example/music/playback/PlaybackProgressIntegrationSupport.java | 本包 MySQL/Redis、JWT、种子数据与请求辅助 |
| 新建 | src/test/java/org/example/music/playback/PlaybackProgressRepositoryIntegrationTest.java | 迁移、原生映射、UTC 与生命周期 |
| 新建 | src/test/java/org/example/music/playback/PlaybackProgressIntegrationTest.java | 完整 JWT、角色、隔离、额度与故障 |
| 新建 | src/test/java/org/example/music/playback/PlaybackProgressConcurrencyIntegrationTest.java | 实际锁等待与并发结果 |
| 新建 | docs/api/playback/save-playback-progress.md | 中文正式 API 与 curl |
| 新建 | docs/reviews/2026-11-15-playback-progress-save-review-request.md | 单接口完整送审材料 |
| 新建 | docs/reviews/2026-11-15-playback-progress-save-review.md | 原始报告来源、结论与反馈处置 |
| 更新 | 本计划、对应设计、docs/tasks/backend-roadmap.md、PROJECT_STATE.md | 实际执行状态与 accepted 后的完成登记 |

## 任务 1 严格请求与响应契约

**文件** 新建两个 DTO、RequestReader、ValidationException、UnsupportedMediaTypeException 和 RequestReaderTest。

**输入接口** 现有 `jakarta.validation.Validator`、`HttpServletRequest` 与 `tools.jackson.core.json.JsonFactory`。参考 `user/controller/EmailChangeConfirmationRequestReader.java` 的媒体检查和整数 token 解析可用的流式结构。

**输出接口**

```java
record SavePlaybackProgressRequest(Long trackId, Long positionSeconds, Long sequence) {}
record SavePlaybackProgressResponse(String deviceId, Long trackId,
        long positionSeconds, long sequence, Instant updatedAt) {}
public SavePlaybackProgressRequest read(HttpServletRequest request);
```

RequestReader 构造器注入 Validator。请求 record 分别使用 NotNull/Positive、NotNull/PositiveOrZero、NotNull/Min(1)/Max(9007199254740991L)。两个异常提供无参构造器和安全固定消息。测试辅助 `private SavePlaybackProgressRequest read(String body)` 构造 UTF-8 application/json 的 MockHttpServletRequest，调用真实 Reader。

- [x] **步骤 1** 编写 `should_read_three_integer_fields_in_any_order`、`should_reject_decoded_duplicate_fields_and_non_integer_tokens`，固定下列断言。

```java
assertEquals(new SavePlaybackProgressRequest(123L, 83L, 42L),
        read("{\"sequence\":42,\"trackId\":123,\"positionSeconds\":83}"));
assertThrows(PlaybackProgressValidationException.class,
        () -> read("{\"trackId\":123,\"positionSeconds\":1.0,\"sequence\":42}"));
assertThrows(PlaybackProgressValidationException.class,
        () -> read("{\"trackId\":123,\"positionSeconds\":1e0,\"sequence\":42}"));
```

- [x] **步骤 2** 执行 `.\gradlew.bat test --tests "org.example.music.playback.controller.PlaybackProgressRequestReaderTest" --console=plain`，记录新类型缺失导致的编译失败。
- [x] **步骤 3** 实现 DTO 与 Reader。按解码后的字段名检查白名单与重复，数字要求 VALUE_NUMBER_INT，使用 Long 读取并捕获溢出。检查完整单个对象、恰好三个字段和流结束，再执行 Validator。媒体类型按 application/json 匹配并接受参数，解析正文使用 JsonFactory 字节流入口。
- [x] **步骤 4** 增加参数化用例，覆盖转义同名键、额外对象、数组、未知字段、缺失/null、负数、sequence 为 0/9007199254740992、Long 溢出、数字字符串、缺少/非法 Content-Type、application/json;charset=UTF-8。断言合法 positionSeconds=Long.MAX_VALUE、sequence=9007199254740991 可通过基础解析。读取异常返回固定校验异常，字符数据由真实解析器验证。
- [x] **步骤 5** 重跑步骤 2 命令，按 XML 核对该组全部通过后提交本任务文件，提交说明为 `feat: 定义播放进度严格请求契约`。

## 任务 2 数据模型、行锁与序号事务

**文件** 新建 V11、Repository、两个 Row、TransactionService、两个序号异常、TransactionServiceTest、IntegrationSupport 和 RepositoryIntegrationTest。

**输入接口** 任务 1 的 DTO，现有 TrackStatus、TrackNotFoundException、AuthenticatedUserPrincipal，注入 `@Qualifier("authenticationClock") Clock`。参考 FavoriteRepository 的 UTC 文本绑定、FavoriteListRepository 的 DATE_FORMAT 读取和 FavoriteIntegrationSupport 的容器配置。

**输出接口**

```java
record PlaybackProgressTrackRow(TrackStatus status, Long durationMs) {}
record PlaybackProgressRow(String deviceId, Long trackId,
        long positionSeconds, long sequence, Instant updatedAt) {}
Optional<PlaybackProgressTrackRow> lockTrackForShare(Long trackId);
void insertIfAbsent(Integer userId, String deviceId, Long trackId,
        long positionSeconds, long sequence, LocalDateTime recordedAt);
PlaybackProgressRow lockProgress(Integer userId, String deviceId);
int updateIfSequenceOlder(Integer userId, String deviceId, Long trackId,
        long positionSeconds, long sequence, LocalDateTime recordedAt);
public SavePlaybackProgressResponse save(String deviceId,
        SavePlaybackProgressRequest input, AuthenticatedUserPrincipal actor);
```

最后一个方法属于独立的 `PlaybackProgressTransactionService`，使用 `@Transactional`。两个冲突异常以 `(long currentSequence)` 构造，并提供 `getCurrentSequence()`。

IntegrationSupport 位于 playback 测试包，使用 MySQL 8.4、Redis 7.4、真实 JwtTokenService、JdbcTemplate 与 TransactionTemplate。提供 `createUser(String username, String role)`、`createTrack(String status, Long durationMs)`、`bearer(int userId, UserRole role)`、`transaction()`、`progress(int userId, String deviceId)`，最后一个返回该行 Map。每个用例清理进度表、关系表和种子数据，清理本账号的保存额度键。

- [x] **步骤 1** 编写事务单测 `should_preserve_saved_event_for_identical_sequence`、`should_reject_stale_and_conflicting_sequence`、`should_allow_backward_seek_and_end_reset_with_newer_sequence`。固定 actor 为用户 7、设备 UUID 固定、歌曲 123，最后序号 42。断言 41 抛 Stale 且 currentSequence=42，42 不同位置抛 Conflict，42 相同内容无更新，43 可从 83 保存为 0。
- [x] **步骤 2** 执行 `.\gradlew.bat test --tests "org.example.music.playback.service.PlaybackProgressTransactionServiceTest" --console=plain`，记录缺失仓库与事务类型的失败。
- [x] **步骤 3** 实现 V11 及 Repository。表列按设计第 6 节，device_id 为 CHAR(36) ASCII 二进制比较，track_id 可空。CHECK 使用 position_seconds 与 last_sequence。歌曲查询 FOR SHARE，完整合法事件 INSERT ON DUPLICATE KEY UPDATE 自赋 user_id，再按联合主键 FOR UPDATE 当前读。更新条件包含 `last_sequence < :sequence`，更新审计字段后回读实际状态。
- [x] **步骤 4** 实现事务 Service。先检查当前角色可见性和 `durationMs / 1000` 上限，再按步骤 3 建立设备行锁并比较序号。同号同内容返回原行。新事件时间由 Clock 截到微秒，更大序号更新预期影响一行。原生数字按 Number 转换，可空 track_id/duration_ms 保留 null，时间写入 UTC 文本 CAST，读取 DATE_FORMAT 后按 UTC 转 Instant。
- [x] **步骤 5** 增加权限与时长参数化单测，覆盖 USER/ADMIN 登录角色、三种状态、缺失歌曲、durationMs 为 null/0/900/1000/215430，断言 215 合法而 216 超限，Long.MAX_VALUE 在已知时长下拒绝且计算保持安全。核对可见性和时长失败先于设备行操作。
- [x] **步骤 6** 新建真实 MySQL 迁移与仓库测试，固定 V11 成功、主键顺序、索引、外键动作、两个 CHECK、微秒往返、UTC/Pacific-Honolulu 时区、重复事件完整审计字段及回滚。删除歌曲后断言 track_id=null、last_sequence=42、位置及时间保留，随后旧号保存其他可见歌曲被拒绝，新号 43 可保存。测试结束恢复 JVM 默认时区。
- [x] **步骤 7** 执行 `.\gradlew.bat test --tests "org.example.music.playback.service.PlaybackProgressTransactionServiceTest" --tests "org.example.music.playback.PlaybackProgressRepositoryIntegrationTest" --console=plain`，核对真实 MySQL 已运行及 XML 数量后提交，说明为 `feat: 实现设备播放进度持久化与序号保护`。

## 任务 3 账号共享 Redis 限流

**文件** 新建 Properties、Config、RateLimiter、两个限流异常、RateLimiterTest、RateLimiterIntegrationTest、ConfigTest，修改 application.properties 与 .env.example。

**输入接口** StringRedisTemplate 与项目现有 TrackSearchRateLimiter 的 Lua 原子计数模式。

**输出接口** `PlaybackProgressProperties(int rateLimitPerMinute)`，默认 120 且 Min(1)，配置前缀 `app.playback-progress`。`PlaybackProgressRateLimiter.consumeOrThrow(Integer userId)`。RateLimitedException 构造器接收 long 并提供 `getRetryAfterSeconds()`，RateLimitUnavailableException 构造器接收 Throwable。

- [x] **步骤 1** 编写单测 `should_use_per_user_key_and_configured_limit`、`should_map_corrupt_script_result_to_unavailable` 及配置测试，断言键 `music:playback-progress:user:7`、参数 120，默认值 120，覆盖值生效，0 和负配置启动校验失败。
- [x] **步骤 2** 执行 `.\gradlew.bat test --tests "org.example.music.playback.service.PlaybackProgressRateLimiterTest" --tests "org.example.music.playback.config.PlaybackProgressConfigTest" --console=plain`，记录组件缺失失败。
- [x] **步骤 3** 实现原子固定窗口脚本与结果解码。首次 SET PX 60000，获准后 INCR，超限以剩余毫秒向上取整得到 Retry-After。错误类型、无 TTL、越界计数、错误返回值及 Redis 异常映射专用 503。配置通过 EnableConfigurationProperties 注册，环境变量默认 120。
- [x] **步骤 4** 增加真实 Redis 用例 `should_admit_exactly_120_concurrent_attempts`，同时发起 140 次并断言恰好 120 获准。验证用户键隔离、窗口首请求 TTL、拒绝请求 TTL 继续递减、临近到期 Retry-After 至少 1、到期恢复、配置额度降低、损坏类型及计数、短暂不可用。使用独立容器和精确键清理。
- [x] **步骤 5** 执行 `.\gradlew.bat test --tests "org.example.music.playback.service.PlaybackProgressRateLimiterTest" --tests "org.example.music.playback.service.PlaybackProgressRateLimiterIntegrationTest" --tests "org.example.music.playback.config.PlaybackProgressConfigTest" --console=plain`，核对真实 Redis 结果后提交，说明为 `feat: 增加播放进度账号共享限流`。

## 任务 4 HTTP 接入、故障与并发验证

**文件** 新建 Controller、外层 Service、StoreUnavailableException、ControllerTest、ServiceTest、ProxyTest、IntegrationTest、ConcurrencyIntegrationTest，修改 GlobalExceptionHandler。

**输入接口** 任务 1 的读取器与 DTO、任务 2 的事务 save、任务 3 的 consumeOrThrow。现有 SecurityConfig、JWT 授权状态过滤器与 TrackNotFoundException。

**输出接口**

```java
// PlaybackProgressService，使用 @PreAuthorize("isAuthenticated()")
public SavePlaybackProgressResponse save(String deviceId,
        SavePlaybackProgressRequest input, AuthenticatedUserPrincipal actor);
// PlaybackProgressController，映射唯一 PUT 路径
public ResponseEntity<SavePlaybackProgressResponse> save(String deviceId,
        HttpServletRequest request, AuthenticatedUserPrincipal actor);
```

Controller 的 deviceId 使用 PathVariable 与完整 UUID Pattern 校验，actor 使用 AuthenticationPrincipal。成功返回 ResponseEntity.ok().cacheControl(noStore)。Service 在调用事务代理前消费额度，捕获 DataAccessException 与 TransactionException 并包装 StoreUnavailableException(Throwable cause)。

- [ ] **步骤 1** 编写 ServiceTest 与 ProxyTest，断言认证、额度、事务开始、仓库操作、事务提交的顺序。未认证时无额度或数据库调用，额度故障时无事务调用，事务开始与提交异常均转换存储 503，同号业务异常保持原类型。
- [ ] **步骤 2** 执行 `.\gradlew.bat test --tests "org.example.music.playback.service.PlaybackProgressServiceTest" --tests "org.example.music.playback.service.PlaybackProgressProxyTest" --console=plain`，记录缺失编排组件的失败。
- [ ] **步骤 3** 实现 Service 与 Controller，Controller 调用 Reader 后将 UUID 转小写并调用 Service。映射使用现有请求读取器模式。补齐 Tag、Operation、请求体 Schema、路径参数、全部 ApiResponse、Retry-After 和 currentSequence 描述。
- [ ] **步骤 4** 在 GlobalExceptionHandler 增加本接口异常映射，固定 400/415、两个 409、429 与两类 503，新增业务异常响应设置 no-store。409 从异常取得 currentSequence，429 从异常取得 Retry-After。503 日志记录 traceId 和异常类型，返回安全固定消息。
- [ ] **步骤 5** 编写 MockMvc 测试，固定首次/更新/重试的 200 五字段及 no-store，非法 UUID、严格正文、401、404、409 属性、415、429 头、503 及 application/problem+json 类型。真实无认证请求优先于非法正文，基础校验失败时验证 Service 未被调用。
- [ ] **步骤 6** 编写真实 JWT 集成测试，使用 support 签发 USER 与 ADMIN Token，覆盖管理员以 USER 登录、角色/版本撤销、账号授权状态不可用。保存相同 UUID 的两个用户各自记录，保存同用户两个 UUID 各自记录，大写/小写 UUID 对同账号落在同一行。验证所有设备与登录角色共用额度，以及重复、冲突、隐藏歌曲、超限时长和数据库故障均消费额度。
- [ ] **步骤 7** 编写 `should_keep_one_row_and_timestamp_for_concurrent_identical_first_events` 和 `should_reject_conflicting_first_event_with_same_sequence`，断言同内容并发全部 200、同一完整行与时间，不同内容并发一个 200 另一个 409，currentSequence 与胜者内容一致。
- [ ] **步骤 8** 编写 `should_keep_largest_sequence_for_both_lock_acquisition_orders` 和 `should_allow_independent_devices_to_share_track_read_lock`。使用事务锁与 latch 控制两种序号顺序，最终行对应较大号。第一设备仍持有同曲共享锁时，第二设备保存能完成。通过目标表的真实锁等待证据控制先后。
- [ ] **步骤 9** 编写归档/删除与保存的两个先后顺序测试，固定先归档/删除时 USER 保存 404，先保存时删除等待提交并最终保留设备序号。删除后原歌曲重试 404，旧号保存其他可见歌曲仍为 409，新号成功。补充账号在认证后被删除、真实锁超时与事务回滚的 503。
- [ ] **步骤 10** 编写 `should_preserve_saved_event_after_commit_ack_failure_and_retry_idempotently`。在真实提交完成后由测试 spy 抛 TransactionSystemException，断言 HTTP 503 而数据库已保存。恢复 spy，原事件重试为 200 且完整行和 updatedAt 相同。补充提交前失败回滚后可按原事件重试的用例。
- [ ] **步骤 11** 执行 `.\gradlew.bat test --tests "org.example.music.playback.*" --console=plain`，核对全部本模块单元、HTTP、MySQL 与 Redis 用例数量，通过后提交本任务文件，说明为 `feat: 接入播放进度保存接口并验证并发故障语义`。

## 任务 5 中文 API、完整验证与独立审核闭环

**文件** 新建正式 API、review-request 与 review，更新本计划、设计、任务书和 PROJECT_STATE。CLI 原始 JSON 与诊断日志保存到 build/reports。

**输入接口** 前四任务的唯一 PUT 实现与真实分组验证结果，AGENTS.md 的送审材料、Claude CLI 调用及三轮意见复核协议。

**输出接口** 可核验的中文 API、完整 check 测试数量、独立审核来源与结论、全部意见处置及 accepted 后的完成登记。

- [ ] **步骤 1** 编写 `docs/api/playback/save-playback-progress.md`，逐项记录 Method、完整路径、用途、认证、角色与归属、三个字段类型和上限、UUID 规则、时长、序号/重试/删除语义、成功响应、错误码、限流与可执行 curl。客户端接入部分说明设备身份和序号生命周期、每 5 秒及关键事件保存、自然结束归零、多页面协调。
- [ ] **步骤 2** 对照实际 OpenAPI `/v3/api-docs` 验证路径、唯一 PUT、请求 DTO、200 五字段及所有错误响应。检查 curl 仅用环境变量 Token、示意 UUID 与歌曲 ID。将执行后的任务复选框及设计状态按事实更新。
- [ ] **步骤 3** 执行完整命令并保存控制台日志。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat check --console=plain
```

按 `build/test-results/test/TEST-*.xml` 汇总套件、通过、失败、错误、跳过数量。若增量执行导致缺少本次实际验证证据，执行同环境下的 `cleanTest check`。必要 MySQL、Redis、MinIO 测试均实跑后准备送审。

- [ ] **步骤 4** 核对完整 Git diff、untracked、暂存文件和新迁移，并运行 git diff --check。审核请求披露既有 `.gitignore` 改动及其来源，写明实施基线、当前提交、唯一接口、权限、全部变更、API/迁移、实际命令和数量、默认项及首次反馈记录“不涉及”。提交本轮实现及材料，记录精确 SHA。
- [ ] **步骤 5** 在项目根目录调用 Claude Code CLI，只读工具为 Read,Glob,Grep,Bash，禁用 Write,Edit,NotebookEdit。提示先读 AGENTS.md、CLAUDE.md、当前请求及历史记录，独立完整验证并从 XML 计数。保存 `build/reports/playback-progress-save-review-round1.json` 与对应 stderr 日志，持续监控到退出，核对退出码、JSON、is_error、result、permission_denials、session_id 与 modelUsage。

```powershell
$progressReviewRequest = "docs/reviews/2026-11-15-playback-progress-save-review-request.md"
$progressReviewResult = "build/reports/playback-progress-save-review-round1.json"
$progressReviewError = "build/reports/playback-progress-save-review-round1.stderr.log"
New-Item -ItemType Directory -Force -Path "build/reports" | Out-Null
$progressReviewPrompt = "请读取 AGENTS.md、CLAUDE.md 和 $progressReviewRequest，核对 Git 范围、基线和历史意见，执行唯一 PUT 接口的独立只读审核。按 CLAUDE.md 设置 MINIO_INTEGRATION_TESTS=true，独立运行完整 check，必要时 cleanTest check，按 XML 记录通过、失败、错误和跳过数量。只读源码与文档，Bash 仅用于读取、Git 只读检查与 Gradle/Docker 验证，输出标准中文审核报告。"
claude --print $progressReviewPrompt `
    --tools "Read,Glob,Grep,Bash" `
    --allowedTools "Read" "Glob" "Grep" "Bash" `
    --disallowedTools "Write" "Edit" "NotebookEdit" `
    --disable-slash-commands `
    --output-format json `
    > $progressReviewResult 2> $progressReviewError
$progressReviewExit = $LASTEXITCODE
if ($progressReviewExit -ne 0) { throw "Claude 审核进程退出码 $progressReviewExit" }
$progressReviewResponse = Get-Content -LiteralPath $progressReviewResult -Raw -Encoding UTF8 |
    ConvertFrom-Json -ErrorAction Stop
if ($progressReviewResponse.is_error) { throw "Claude 返回执行错误，请检查原始输出" }
$progressReviewResponse.result
```
- [ ] **步骤 6** 将原始 result 保存到正式 review，并记录其来源。对 needs_revision 按原编号核实问题、分级与方向，完成范围内修复或补证、验证及材料更新。复审使用原 session_id 的 --resume，按轮次分别保存输出。三轮协议及 escalated 处理按 AGENTS.md 执行。
- [ ] **步骤 7** 收到有效 accepted 后登记所有非阻断项处置，将 H02 保存接口移入已完成区，并保留 H02 读取接口的待设计记录，更新 PROJECT_STATE 和设计/计划实际结果，提交收尾文档。
- [ ] **步骤 8** 向用户汇报唯一接口、实际测试数量、独立审核结论与轮次、提交号、API/迁移路径，以及实际未决事项或默认项。

## 计划自查记录

- 设计第 3 至第 5 节由任务 1、2、4 及任务 5 的客户端契约文档覆盖。
- 设计第 6、7 节由任务 2 与任务 4 覆盖，原子创建使用完整合法事件，时间使用现有 UTC 文本往返方式。
- 设计第 8、9 节由任务 3、4 覆盖，认证阶段 503 与业务额度阶段 503 分别保留现有代码语义。
- 设计第 10、11 节由文件表、各任务验证及任务 5 覆盖，审查重点的五类输入均定位到具体测试步骤。
- 前后任务共享 DTO、Row、仓库与服务签名一致。测试命令使用实际 Gradle Wrapper，完整验证及正式审核步骤包含真实依赖与 XML 计数。

计划待用户审阅。执行方式沿用项目约定，由 Codex 在本会话实施并提交 Claude Code CLI 独立审核。
