# H02 播放进度保存接口独立审核与反馈处置

当前结论为 accepted，第 1/3 轮，零阻断项。NOTE-001 为既有队列测试一次偶发失败的非阻断意见，已排入 O10 独立调查，具体根因待核实。

日期为 2026-11-15，时区 Asia/Shanghai。唯一接口为 `PUT /api/v1/users/me/playback-progress/{deviceId}`，实施基线为 `c546a1a998c83b94cc5db3ef5691b0cd5cc1918f`，完整送审提交为 `763c7a7e322e929bdc733fb3799374415e476b05`，业务与测试快照为 `7020cfc54932f8e38413769a8bfb6d4e50748a60`。

## 审核来源与执行状态

审核使用本机 Claude Code CLI，模型沿用本机配置。会话为 `8090ebaa-46b1-43af-a5ab-2fc93fe348d3`，实际 modelUsage 模型为 `mimo-v2.6-pro`。CLI 退出码为 0，JSON 可解析，is_error=false，permission_denials 为空。进程总耗时 3478.51 秒，包括范围检查、独立全量首跑、专项复跑、第二次独立全量与审查。

原始输出为 `build/reports/playback-progress-save-review-round1.json`，诊断日志为同目录 `playback-progress-save-review-round1.stderr.log`，精确提示为 `playback-progress-save-review-round1.prompt.txt`，进程结果为 `playback-progress-save-review-round1-process.json`。只读工具配置使用 Read、Glob、Grep、Bash，禁用 Write、Edit、NotebookEdit。

stderr记录claude-code对本机配置模型名的unrecognized_model识别提示，实际modelUsage为mimo-v2.6-pro，CLI执行及审核结果均已按上述字段核对。

实际 modelUsage 保留如下。

```json
{
  "mimo-v2.6-pro": {
    "inputTokens": 135780,
    "outputTokens": 52040,
    "cacheReadInputTokens": 3626624,
    "cacheCreationInputTokens": 0,
    "webSearchRequests": 0,
    "costUSD": 3.7932119999999987,
    "contextWindow": 1000000,
    "maxOutputTokens": 32000,
    "thinkingTokens": 0,
    "cacheCreationEphemeral5mInputTokens": 0,
    "cacheCreationEphemeral1hInputTokens": 0,
    "canonicalModel": "mimo-v2.6-pro",
    "provider": "firstParty",
    "costBasis": "unknown"
  }
}
```

## 验证过程与证据

四项数字依次为通过、失败、错误、跳过。

| 执行 | 套件与结果 | 耗时 | 日志及 XML |
| --- | --- | --- | --- |
| Codex 完整自验 | 210 套件，2541/0/0/0 | 1422.85 秒 | build/playback-progress-save-self-check.log，build/reports/playback-progress-save-self-check-xml |
| Claude 独立完整首跑 | 210 套件，2540/1/0/0 | Gradle 23 分 12 秒 | build/playback-progress-save-independent-check.log，build/reports/playback-progress-save-independent-check-xml |
| Claude 既有队列专项复跑 | 1 套件，18/0/0/0 | Gradle 8 秒 | build/playback-progress-save-independent-retry-queue.log，数量由审核方核对专项 XML |
| Claude 最终独立完整复跑 | 210 套件，2541/0/0/0 | Gradle 23 分 22 秒 | build/playback-progress-save-independent-check-rerun.log，build/reports/playback-progress-save-independent-check-rerun-xml |

完整命令均设置 MINIO_INTEGRATION_TESTS=true 并执行 cleanTest check，最终 MySQL、Redis、MinIO 必要测试均实跑，跳过清单为空。本接口 11 套件 165/0/0/0，在两次独立全量中均通过。

Codex 另行保留首跑 XML 与日志至 `build/reports/playback-progress-save-independent-first-attempt`，最终 XML 至 `build/reports/playback-progress-save-independent-final-check-xml`。两次全量记录分别保存，首跑失败保持可核对。

## 第 1 轮审核原文

以下内容逐字取自本轮 JSON 的 result，Codex 的意见复核另列于后文。

第 2 次完整独立验证全绿（BUILD SUCCESSFUL，210 套件 2541/0/0/0，跳过清单为空）。两次证据齐备，输出最终审核报告。

---

## Verdict: accepted

审核任务：
- `PUT /api/v1/users/me/playback-progress/{deviceId}`（H02 播放进度保存接口，第 1/3 轮实现审核）

审核轮次：
- 第 1/3 轮

Git 基线：
- 实施基线 `main @ c546a1a998c83b94cc5db3ef5691b0cd5cc1918f`，完整送审 HEAD `763c7a7e322e929bdc733fb3799374415e476b05`，已验证业务快照 `7020cfc54932f8e38413769a8bfb6d4e50748a60`，工作区含既有 `.gitignore` 改动（已披露）

## 范围检查
- 结果：通过
- 预期范围：唯一接口 `PUT /api/v1/users/me/playback-progress/{deviceId}` 及其迁移、配置、测试、文档与审核材料，共披露 40 项（含既有 `.gitignore`）
- 实际变更文件：`git diff --name-status c546a1a 763c7a7` 为 39 项（34 源码/配置/迁移 + 5 文档），工作区 `M .gitignore`，合计 40 项，与送审清单**逐项一致**。无 untracked 文件（`git ls-files --others --exclude-standard` 为空），暂存区为空，`git diff --check` 干净
- 越界改动：无。`.gitignore` 仅增加 `docs/learning` 忽略规则，来源在设计（第 5 行）与请求中登记，属既有用户改动；`GlobalExceptionHandler`、`application.properties`、`.env.example` 的改动均仅为本接口新增映射与配置，未触碰既有逻辑；`V1`–`V10` 迁移零改动；`SecurityConfig`、`build.gradle` 零改动；仅新增 `playback` 测试文件，未修改任何既有测试
- 单接口确认：`org.example.music.playback` 包内仅 `@PutMapping("/{deviceId}")` 一个 HTTP 操作（Grep 核实），无第二接口混入

## 独立验证

**第 1 次完整执行**（按要求设置 MinIO 环境变量并强制重跑）：
- 执行命令：`MINIO_INTEGRATION_TESTS=true ./gradlew cleanTest check --console=plain`（Git Bash）
- 结果：**失败**（Gradle 退出码 1，BUILD FAILED in 23m 12s）
- 按 XML 汇总：210 套件，通过 2540 / 失败 1 / 错误 0 / 跳过 0
- 失败摘要：
  - `org.example.music.user.PasswordResetQueueIntegrationTest#should_fail_closed_on_damaged_task_without_clearing_it[4] damage="short-ttl"` — `PasswordResetRequestUnavailableException`（`PasswordResetRequestStore.enqueue:42` 失败关闭，堆栈无底层 cause），**位于既有 `user` 模块，不在本次 40 项变更范围内**（密码重置队列存储与 Lua 脚本零改动，与 playback 模块无代码交集）
- 本审核对象 playback 模块：11 套件 165/0/0/0 全部通过

**偶发性核实**（判定该失败为与本接口无关的既有偶发失败，依据如下）：
1. 该测试在 Codex 自验全量 XML 归档中 18/0/0/0 通过（我方独立解析归档核对：210 套件 2541/0/0/0，与送审声明一致）；
2. 隔离复跑 `./gradlew test --tests "org.example.music.user.PasswordResetQueueIntegrationTest"` 实跑 18/0/0/0 通过（日志 `build/playback-progress-save-independent-retry-queue.log`，XML 核实非缓存跳过）；
3. 失败点为 `enqueue.lua` 的状态无关时间校验（`first > now` 类失败关闭）在重负载运行下的一次性触发，与本变更引入的表、Redis 键（`music:playback-progress:*`）及代码路径无关。

**第 2 次完整执行**（最终独立验证）：
- 执行命令：`MINIO_INTEGRATION_TESTS=true ./gradlew cleanTest check --console=plain`
- 结果：**通过**（Gradle 退出码 0，BUILD SUCCESSFUL in 23m 22s）
- 通过测试数：2541；失败测试数：0；错误测试数：0；跳过测试数：0
- 跳过测试：无（跳过清单为空，`@EnabledIfDockerAvailable` 未触发任何 skip，MySQL/Redis/MinIO Testcontainers 全部实跑）
- 失败摘要：无
- 本模块与关键套件（第 2 次 XML 实测）：PlaybackProgressIntegrationTest 12/0/0/0、PlaybackProgressConcurrencyIntegrationTest 12/0/0/0、S3AudioStorageIntegrationTest 5/0/0/0、S3AudioStreamStorageHttpTest 8/0/0/0、TrackCoverIntegrationTest 18/0/0/0、TrackStreamIntegrationTest 16/0/0/0、PasswordResetQueueIntegrationTest 18/0/0/0

**证据留存**：第 1 次日志 `build/playback-progress-save-independent-check.log`、XML 归档 `build/reports/playback-progress-save-independent-check-xml`（含上述失败详情）；第 2 次日志 `build/playback-progress-save-independent-check-rerun.log`、XML 归档 `build/reports/playback-progress-save-independent-check-rerun-xml`；隔离复跑日志 `build/playback-progress-save-independent-retry-queue.log`。自验 XML 归档交叉核对为 210 套件 2541/0/0/0、本模块 11 套件 165，与送审声明一致。

## 审查清单

### A. 范围与契约
- A1：通过（唯一 PUT 接口，单任务）
- A2：通过（Method/Path 与审核请求、设计 4.1 节一致）
- A3：通过（40 项均与本接口相关，清单逐项核对）
- A4：通过（无顺手重构/格式化/公共组件调整；共享文件仅新增本接口映射与配置行）
- A5：通过（Bearer JWT；USER 仅 PUBLISHED、ADMIN 按登录角色全状态、管理员以 USER 登录按 USER 判定——`PlaybackProgressTransactionService.java:40`，集成测试 `should_enforce_current_login_role_for_all_track_states` 覆盖；归属一律取 `AuthenticatedUserPrincipal.userId()`，同 UUID 跨账号隔离已测）
- A6：通过（三整数字段，严格拒绝重复/未知/缺失/null/字符串/小数/科学计数法/溢出——`PlaybackProgressRequestReader` 逐 token 校验 + 35 个非法用例；trackId 正 Long、positionSeconds 非负 Long、sequence 1–9007199254740991，DB CHECK 一致）
- A7：通过（200 返回 deviceId/trackId/positionSeconds/sequence/updatedAt 五字段 DTO，`Cache-Control: no-store`，updatedAt 为 UTC Instant 微秒——时区往返与微秒精度集成测试覆盖）
- A8：通过（幂等同号同内容 200 保留原审计行；409 两分支含 currentSequence；60 秒固定窗口每账号 120 次、全设备/角色共享，基础校验后消费额度；事务边界在事务 Service。分页/排序/过滤不适用——本接口无列表，已有明确判断）

### B. 代码分层
- B1：通过（Controller 仅解析路径、调用 Reader/Service、构造响应）
- B2：通过（外层 Service：认证、限流、异常转换；事务 Service：可见性、时长、序号规则、事务）
- B3：通过（Repository 仅参数化原生 SQL 数据访问）
- B4：通过（请求/响应均为 Java record DTO）
- B5：通过（无 JPA Entity 暴露，Row 记录仅作内部映射）
- B6：通过（DTO↔Row 转换清晰，无循环依赖）
- B7：通过（构造器注入，状态字段 `final`）
- B8：通过（Grep 全源码无 TODO/FIXME/假实现/`System.out`/`printStackTrace`，无未使用代码）

### C. REST API 与错误处理
- C1：通过（`/api/v1/users/me/playback-progress`，与现有 `users/me` 资源风格一致）
- C2：通过（200/400/401/403/404/409/415/429/503 语义逐项测试覆盖）
- C3：通过（稳定 DTO，无 Entity/内部对象）
- C4：通过（record 字段 Bean Validation + 路径 `@Pattern` + Reader 内 `Validator.validate` 等价入口校验，非法 UUID 400 且不触达 Service——集成测试 `verifyNoInteractions(repository)` 佐证）
- C5：通过（`ProblemDetail` + `application/problem+json`，全部新增映射含 `no-store`）
- C6：通过（稳定 `code` + `urn:problem:music:*`，`problem()` 助手统一生成，测试断言 `type` 精确匹配）
- C7：通过（409 序号冲突/过期、429 额度、415 媒体类型、404 可见性、503 存储/额度/认证状态）
- C8：通过（异常消息为安全固定文案，cause 不回传——`content(not(containsString("private")))` 断言覆盖）
- C9：通过（7 个新增异常全部由 `GlobalExceptionHandler` 统一处理）
- C10：通过（`Instant`，UTC 微秒，跨 JVM 时区往返测试）
- C11：通过（不适用：无列表接口）
- C12：通过（同号同内容幂等、并发首次创建单行单时间戳、旧号 409——并发集成测试覆盖）
- C13：通过（响应仅五字段，无敏感信息）

### D. 安全
- D1：通过（`SecurityConfig` 未改，`anyRequest().authenticated()` 覆盖本路径；未认证 401 先于参数校验——已测）
- D2：通过（`@PreAuthorize("isAuthenticated()")` 方法级授权，`PlaybackProgressService.java:22`；ProxyTest 验证未认证时零调用）
- D3：通过（不可通过请求参数访问他人资源：设备行归属固定取主体 userId）
- D4：通过（资源归属校验全部在服务端，路径仅有客户端自有命名空间内的 deviceId）
- D5：通过（token version、登录角色失效返回 TOKEN_REVOKED——`should_revoke_stale_version_or_role_tokens` 真实 JWT 链路测试）
- D6：通过（无硬编码密钥；测试仅用 test profile 占位值）
- D7：通过（`PLAYBACK_PROGRESS_RATE_LIMIT_PER_MINUTE` + 默认 120，启动期 `@Min(1)` 校验）
- D8：通过（503 日志仅 traceId + 异常类型名，日志内容断言测试覆盖）
- D9：通过（不适用：无文件上传）
- D10：通过（不适用：无大文件处理；请求体小且流式解析）
- D11：通过（不适用：本接口不写音频/封面）
- D12：通过（同步 DB/Redis 操作均短事务，无长阻塞任务；锁等待异常映射 503）
- D13：通过（不适用：本接口不提供音频播放；Range 语义属既有 stream 接口）
- D14：通过（未改动 CORS 配置，沿用现有白名单，无通配授权引入）
- D15：通过（账号级 60 秒 120 次限流，真实 Redis 140 并发恰 120 获准测试）

### E. 数据库与迁移
- E1：通过（`V11__create_user_playback_progress.sql`）
- E2：通过（`ddl-auto=none` 未动）
- E3：通过（V11 版本号顺延、命名规范）
- E4：通过（V1–V10 零改动）
- E5：通过（列结构与原生 SQL、Row 映射一致；仓库集成测试核对 information_schema）
- E6：通过（联合主键 `(user_id, device_id)`、track 索引、两 CHECK、双外键，均经真实 MySQL 验证）
- E7：通过（`created_at/updated_at/created_by/updated_by` 与 V9 约定一致；幂等保留完整审计行已测）
- E8：通过（事务边界在事务 Service，事务内无远程调用）
- E9：通过（FOR SHARE 歌曲锁 + upsert 行锁 + FOR UPDATE 持锁比较 + `last_sequence < :sequence` 条件更新；真实 1213/40001 死锁、锁超时、双序到达顺序测试覆盖）
- E10：通过（全部按主键/索引点查，无无界查询）
- E11：通过（MySQL 8.4 Testcontainers 实跑迁移与约束断言）
- E12：通过（user_id INT、track_id BIGINT 与 users/tracks 现有 ID 类型一致；设备 UUID 字符串沿用 CHAR(36) 先例）

### F. 可靠性与可观测性
- F1：通过（DB/事务异常 → 503 专用存储错误码；Redis 额度异常 → 503 专用码）
- F2：通过（无吞异常/空 catch/假成功）
- F3：通过（部分写入回滚、提交确认失败后原事件幂等重试、账号消失回滚——均已测）
- F4：通过（不适用：无上传/外部对象写入）
- F5：通过（重复请求不产生重复行/状态漂移，并发与幂等测试覆盖）
- F6：通过（SLF4J）
- F7：通过（`MDC.get("traceId")`，可经 TraceIdFilter 追踪）
- F8：通过（warn 级别，不含请求体/凭据）
- F9：通过（Actuator 暴露范围未动）
- F10：通过（零新增依赖；`tools.jackson` 为 Spring Boot 4 自带 Jackson 3，Awaitility 为既有 test starter 依赖）

### G. 测试
- G1：通过（仅新增 12 个测试文件，既有测试零修改）
- G2：通过（首次/更新/幂等重试）
- G3：通过（严格 JSON 35 项非法用例、非法 UUID）
- G4：通过（未认证 401 先于绑定）
- G5：通过（本接口无角色级拒绝场景；403 FORBIDDEN 映射经错误表参数化测试，方法授权经 ProxyTest 验证）
- G6：通过（TRACK_NOT_FOUND，含 Long.MAX_VALUE 不存在）
- G7：通过（同号异内容 409、旧号 409、并发同号竞争、真实死锁/锁超时）
- G8：通过（0 秒、Long.MAX_VALUE、sequence 1 与 9007199254740991、durationMs null/0/900/1000/215430 边界）
- G9：通过（同事件重试幂等、提交确认失败重试）
- G10：通过（@WebMvcTest 断言状态码、五字段契约、ProblemDetail 码与 type、Retry-After、no-store）
- G11：通过（Service/事务单测验证规则、顺序、异常转换）
- G12：通过（Testcontainers MySQL 8.4，无 H2）
- G13：通过（真实 Redis 7.4 限流集成、真实 MinIO 既有套件全绿）
- G14：通过（命名表达行为，如 `should_reject_conflicting_first_event_with_same_sequence`）
- G15：通过（每用例清理表数据；限流断言依赖自增 userId 唯一键，不依赖执行顺序）
- G16：通过（第 2 次独立完整执行 2541/0/0/0，跳过清单为空；第 1 次 1 例无关偶发失败已在独立验证节如实记录并三证核实）

### H. OpenAPI 与 API 文档
- H1：通过（`@Tag/@Operation/@ApiResponse` 含全部 10 个状态码与 Retry-After、no-store 头声明）
- H2：通过（请求/响应 record `@Schema` 与实现一致）
- H3：通过（集成测试核对 `/v3/api-docs` 唯一 PUT、请求必填、3/5 字段 Schema）
- H4：通过（`docs/api/playback/save-playback-progress.md`）
- H5：通过（Method、完整 Path、用途）
- H6：通过（认证、登录角色、归属与隔离规则）
- H7：通过（字段表、校验规则、请求示例）
- H8：通过（200 响应示例）
- H9：通过（错误码表含全部稳定 code 与 409 示例）
- H10：通过（PowerShell curl 可执行示例）
- H11：通过（`$env:ACCESS_TOKEN`，无真实 Token/密码/密钥）
- H12：通过（设计文档仅更新状态行，未扩展无关内容）

### I. 配置与构建
- I1：通过（配置经 properties + 环境变量外部化，保留安全默认值）
- I2：通过（`.env.example` 仅占位符）
- I3：通过（未提交 `.env`/密钥/导出）
- I4：通过（无新增本地依赖，docker-compose 无需变更）
- I5：通过（构建无本地路径/IDE 依赖）
- I6：通过（第 2 次完整 `cleanTest check` 退出码 0）
- I7：通过（无 TODO/假数据/占位实现）
- I8：通过（仅依赖 Gradle Wrapper + Docker，无未记录环境状态）

## 阻断项（Blockers）
- 无

## 非阻断项（Non-blocking）
- NOTE-001 [src/test/java/org/example/music/user/PasswordResetQueueIntegrationTest.java:62] 既有测试 `should_fail_closed_on_damaged_task_without_clearing_it[4]` 在第 1 次全量独立验证中偶发失败（`PasswordResetRequestStore.enqueue` 失败关闭，无底层 cause），隔离复跑与第 2 次全量均通过，判定为与本接口无关的偶发失败（失败机制指向 `enqueue.lua` 时间一致性校验在重负载/宿主时钟微调下的一次性触发）→ 建议在独立任务中排查该测试及 `enqueue`/`admit` 时间校验对时钟回拨的敏感性；不属本接口范围，不阻塞合并，排期跟踪即可

## 上轮反馈复核
- 首次审核：不涉及

## 已通过检查
- 范围与 Git 核对（40 项一致、无越界、V1–V10 未触碰、单接口）、契约与权限、严格请求解析、序号/幂等/并发语义（含真实死锁 1213/40001、锁超时、提交确认失败）、V11 迁移与约束、Redis 限流（真实 120/60s 边界）、错误映射与信息泄漏防护、JWT/登录角色/token version 链路、审计字段与 UTC 微秒时间、OpenAPI 与中文 API 文档、零新增依赖、无 TODO/假实现。本模块 12 个测试文件 165 用例在两次全量运行中均为 165/0/0/0。

## Codex 下一步
1. 本接口实现审核通过，无阻断项。按 AGENTS.md 第 3.1 节如有异议可举证复核；NOTE-001 按收益与排期处置（建议另开任务，不并入本接口变更）。
2. `accepted` 后按协议登记审核结果与非阻断项处置，将 H02 保存接口移入任务书已完成区，更新 PROJECT_STATE 与设计/计划状态；审核原文按请求约定保存至 `docs/reviews/2026-11-15-playback-progress-save-review.md` 并注明来源。
3. H02 读取接口保持独立规划，另行确认范围。

## 重新审核重点
- 本接口无剩余阻断项；若后续对 NOTE-001 的既有偶发测试做修复，按独立任务单独送审，勿并入播放进度变更。
- 复审（如有）沿用本会话与原问题编号，重点核对第 1/2 次验证差异已如实归档（`build/playback-progress-save-independent-check*.log` 与对应 XML 归档）。

## Codex 反馈复核与处置

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001，非阻断 | 失败事实成立。首跑 XML 中 PasswordResetQueueIntegrationTest 参数 short-ttl 在第 62 行准备 enqueue 时失败，该用例耗时 0.009 秒，发生于人为修改 TTL 之前。堆栈为 enqueue → result → unavailable，实际脚本 reply、Redis 两次 TIME 及清理后状态均缺少当时采样，精确拒绝条件与时钟回拨归因仍待核实。相关 user 代码、测试和 Lua 对实施基线差异为空，该测试直接使用独立 Redis8.10.2，播放测试使用独立 Redis7.4。 | 认可原非阻断分级。专项复跑 18/0/0/0、最终独立完整复跑 2541/0/0/0，审核已 accepted，当前没有找到与播放接口直接共享状态的入口。 | 认可独立排查，调查同时记录脚本实际返回、admit/enqueue 两次 Redis 时间、清理后 latest/pending/outstanding 状态。报告中的时钟原因作为调查假设保留。 | 排期至任务书 O10，保留首跑及所有复跑证据。 | 失败记录为 build/reports/playback-progress-save-independent-first-attempt/TEST-org.example.music.user.PasswordResetQueueIntegrationTest.xml。最终全量为 210 套件 2541/0/0/0。具体根因等待独立调查取证。 |

审核原文对时间一致性校验的归因缺少当时 reply 与时间采样，Codex 将该项归因为待核实。失败事实、非阻断分级及独立排查方向均已核对，原报告保留完整来源与措辞。

## 收尾登记

H02 保存接口按任务书的部分交付规则登记为 H02-A，读取部分继续作为 H02 的独立候选。设计、计划和 PROJECT_STATE 同步本轮 accepted 与真实验证过程。代码、测试和 API 对应已审核的 763c7a7 快照，收尾登记记录审核来源、反馈处置与任务状态。
