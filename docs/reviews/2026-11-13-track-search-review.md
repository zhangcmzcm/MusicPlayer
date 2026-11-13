# T04 歌曲搜索独立审核与反馈处置

最终结论为accepted。第2/3轮维持通过并由原审核方撤销NOTE-001，零阻断项、零未决非阻断项。

审核日期为2026-11-14，时区Asia/Shanghai。代码送审提交为7a58bae065dc7d2cad5e3dc3c2d36cafac7384c1，设计及实现基线为8778308。

第1/3轮结论accepted，零阻断项、1条非阻断NOTE-001。CLI退出码0、is_error=false、permission_denials为空，会话928af25d-9e96-4e4a-96e0-17a9216ed28c，实际modelUsage模型为mimo-v2.6-pro。

自验199套件2376通过、0失败、0错误、0跳过，21分42秒。独立cleanTest check带MINIO_INTEGRATION_TESTS=true，同样199套件2376/0/0/0，20分59秒，MySQL、Redis、MinIO实跑。

原始CLI结果为build/reports/track-search-review-round1.json，诊断日志为track-search-review-round1.stderr.log，退出码记录为track-search-review-round1.exit-code.txt。独立验证日志build/track-search-independent-check.log，独立XML归档build/reports/track-search-independent-check-xml，自验归档build/reports/track-search-self-check-xml。

## 第1轮审核原文

以下保存result原文，Codex核实另列。
独立验证全部完成，结果与自验一致。以下输出标准审核报告。

---

## Verdict: accepted

审核任务：
- `GET /api/v1/tracks` 扩展可选查询参数 q（任务书 T04 歌曲模糊搜索）

审核轮次：
- 第 1/3 轮

Git 基线：
- 基线 `main @ 877830862dfb0c76cf5882d9db6564435dbbc031`，完整送审 HEAD `7a58bae065dc7d2cad5e3dc3c2d36cafac7384c1`，工作树干净（`git status --short` 为空，与送审声明一致）。范围内 5 个提交：cd4cb6f（设计/计划）、0b0f359（参数契约）、324eedb（查询与预算）、07cffad（限流与 HTTP 契约）、7a58bae（文档与审核材料）。

## 范围检查
- 结果：通过
- 预期范围：GET /api/v1/tracks 的 q 搜索扩展这一个明确任务（含其测试、配置与文档）
- 实际变更文件：共 28 个，与送审清单逐一核对完全一致
  - `.env.example`、`docs/api/track/list-tracks.md`、`docs/reviews/2026-11-13-track-search-query-plans.md`、`docs/reviews/2026-11-13-track-search-review-request.md`、`docs/superpowers/plans/2026-11-13-track-search-plan.md`、`docs/superpowers/specs/2026-11-13-track-search-design.md`
  - `src/main/java/.../common/exception/GlobalExceptionHandler.java`、`track/config/TrackSearchConfig.java`、`track/config/TrackSearchProperties.java`、`track/controller/TrackListController.java`、`track/exception/TrackSearchRateLimitedException.java`、`track/exception/TrackSearchRateLimitUnavailableException.java`、`track/repository/TrackListRepository.java`、`track/service/TrackListParameters.java`、`track/service/TrackListQueryService.java`、`track/service/TrackListService.java`、`track/service/TrackSearchRateLimiter.java`、`src/main/resources/application.properties`
  - 测试 10 个：`TrackSearchConfigTest`、`TrackListControllerTest`、`TrackListParametersTest`、`TrackListQueryServiceTest`、`TrackListServiceTest`、`TrackSearchRateLimiterIntegrationTest`、`TrackSearchRateLimiterTest`、`TrackListIntegrationTest`、`TrackSearchQueryPlanIntegrationTest`、`TrackSearchTimeoutIntegrationTest`
- 越界改动：
  - 无。无新增/修改迁移（V1—V10 保持原状），未触碰 `.env`、`build.gradle`、`SecurityConfig`；设计文档改动仅为状态行更新，均属本任务范围。

## 独立验证
- 执行命令：`MINIO_INTEGRATION_TESTS=true ./gradlew cleanTest check --console=plain`（Git Bash 环境，等价 POSIX 形式），完整日志已保存至 `build/track-search-independent-check.log`
- 结果：通过（BUILD SUCCESSFUL in 20m 59s，退出码 0）
- 通过测试数：2376
- 失败测试数：0
- 错误测试数：0
- 跳过测试数：0
- 跳过测试：
  - 无（199 个套件全部实跑；已抽查确认 `S3AudioStorageIntegrationTest`、`S3AudioStreamStorageHttpTest`、`TrackCoverIntegrationTest`、`TrackStreamIntegrationTest` 等 22 个存储相关套件 0 跳过，MySQL/Redis/MinIO Testcontainers 均实际执行）
- 失败摘要：
  - 无
- 本任务关键套件独立结果：`TrackListIntegrationTest` 43/0/0/0、`TrackListControllerTest` 30/0/0/0、`TrackListParametersTest` 34/0/0/0、`TrackSearchRateLimiterIntegrationTest` 15/0/0/0、`TrackSearchRateLimiterTest` 16/0/0/0、`TrackSearchTimeoutIntegrationTest` 2/0/0/0、`TrackSearchQueryPlanIntegrationTest` 1/0/0/0、`TrackSearchConfigTest` 7/0/0/0。
- 附：Codex 自验归档 `build/reports/track-search-self-check-xml` 独立汇总为 199 套件/2376/0/0/0，与自报一致（仅作线索，上述为独立执行结果）。

## 审查清单

### A. 范围与契约
- A1：通过 — 单接口 GET /api/v1/tracks 的 q 扩展，一个明确任务。
- A2：通过 — Method/Path 与审核需求一致。
- A3：通过 — 28 个文件均服务于本接口。
- A4：通过 — 无顺手重构；旧 q 拒绝案例改为未知参数案例是新契约的必要组成，已在送审材料中说明。
- A5：通过 — 认证、USER 仅 PUBLISHED、显式 DRAFT/ARCHIVED 403、ADMIN 全状态、管理员以 USER 登录按 USER 规则，与设计一致（`TrackListService.java:26-33`，集成测试覆盖四种登录身份）。
- A6：通过 — q 原始解码值 ≤100 码点（首尾空白计入）、Unicode White_Space 拆 1—5 词、重复词计数、纯空白/空值/未知/重复参数 400，与契约一致（`TrackListParameters.java:44-73` 及参数测试 34 项）。
- A7：通过 — 200 + PageResponse\<TrackListItemResponse\>，空页与越界页保留真实统计，与契约一致。
- A8：通过 — page/size/sort 既有边界、page×size≤10000、账户限流 60/60s、每条搜索 SQL 预算 2000ms、REPEATABLE_READ 短只读事务、no-store 均有实现与测试；幂等不适用（只读接口）；LIKE 扫描取舍经用户明确批准并有 88 组真实执行计划记录。

### B. 代码分层
- B1：通过 — Controller 仅绑定参数、委派 Service、构造响应。
- B2：通过 — 权限判断、额度消耗、事务边界在 Service。
- B3：通过 — Repository 仅数据访问与 SQL 构造。
- B4：通过 — 请求/响应均为 DTO，未暴露 Entity。
- B5：通过 — Entity 仅持久化映射。
- B6：通过 — DTO/Entity 转换清晰，无循环依赖。
- B7：通过 — 全部构造器注入，字段 final。
- B8：通过 — 无 TODO/FIXME/假实现（全仓 src 检索无命中）；`contentSql(sort,filtered)` 二参重载仍由 `TrackListQueryIntegrationTest` 使用，非未使用代码。

### C. REST API 与错误处理
- C1：通过 — /api/v1/tracks 复数资源路径。
- C2：通过 — 200/400/401/403/429/500/503 语义正确（429 携带 Retry-After，真实超时 3024→503 有测试）。
- C3：通过 — 成功响应为稳定 DTO。
- C4：通过 — 全部查询参数经 `TrackListParameters.from` 入口校验（既有 Bean Validation 等价模式），Controller 启用校验。
- C5：通过 — ProblemDetail + application/problem+json（WebMvcTest 断言 content type）。
- C6：通过 — `TRACK_SEARCH_RATE_LIMITED`/`TRACK_SEARCH_RATE_LIMIT_UNAVAILABLE`，URN `urn:problem:music:track_search_rate_limited`/`..._unavailable` 符合既有格式。
- C7：通过 — 403 状态筛选、429 额度、503 依赖故障语义正确。
- C8：通过 — 错误响应仅稳定业务说明；限流故障日志只记 traceId 与异常类型。
- C9：通过 — 新异常由 `GlobalExceptionHandler` 统一映射（`GlobalExceptionHandler.java:1074-1091`），no-store 由既有 `TrackListErrorAdvice` 统一附加，Controller 无手写错误响应。
- C10：通过（不适用：响应无时间字段，created_at 仅作排序）。
- C11：通过 — 统一 page/size/sort 与分页响应；全表扫描为用户批准并记录的明确取舍。
- C12：通过（不适用：只读接口）。
- C13：通过 — 无密码哈希、凭据、内部版本泄漏。

### D. 安全
- D1：通过 — `@PreAuthorize("isAuthenticated()")` 与 SecurityConfig 既有链路一致。
- D2：通过（不适用：本接口无管理员专属入口；状态筛选授权在 Service 按登录角色执行）。
- D3：通过 — 共享曲库按登录角色限定可见状态，无跨用户资源参数。
- D4：通过 — 身份取自 `AuthenticatedUserPrincipal`，额度 key 由服务端 userId 构造。
- D5：通过 — JWT subject/角色/token version 沿用既有体系（测试经 `JwtTokenService` 真实签发）。
- D6：通过 — 无硬编码密钥/凭据。
- D7：通过 — 两项配置经环境变量外部化，`@Min(1)` 启动校验，示例仅占位默认值。
- D8：通过 — 日志无密码/Token/验证码/敏感请求体。
- D9/D10/D11：通过（不适用：本接口只读查询，无上传、无大文件读入、无对象存储写入）。
- D12：通过 — 每条搜索 SQL 有 2000ms 预算上限，无不可控长请求。
- D13：通过（不适用：本接口无音频播放，Range/206 属既有 stream 接口）。
- D14：通过（不适用：无 CORS 变更）。
- D15：通过 — 搜索接口账户级限流 60 次/60 秒，真实 Redis 原子脚本验证。

### E. 数据库与迁移
- E1：通过 — Flyway 保持 V1—V10，无新增迁移（使用现有表结构，与“数据库迁移为无”一致）。
- E2：通过 — 未触碰 ddl-auto。
- E3：通过（不适用：无新增迁移）。
- E4：通过 — 未修改任何已执行迁移。
- E5：通过 — 原生 SQL 列与现有表结构一致（真实 MySQL 8.4 集成测试验证）。
- E6：通过（不适用：无新增表列约束）。
- E7：通过（不适用：无新增写入表）。
- E8：通过 — 事务在 `TrackListQueryService`（REPEATABLE_READ 只读），Redis 额度操作在事务之前。
- E9：通过（不适用：只读接口）。
- E10：通过 — 分页上限 10000 约束深度；LIKE 扫描与精确 COUNT 成本经用户批准，88 组 EXPLAIN ANALYZE 如实记录扫描与耗时。
- E11：通过 — 迁移由真实 MySQL 8.4 Testcontainers + Flyway 实跑验证（含 1 万首计划测试）。
- E12：通过 — track id 沿用 Long，userId 沿用 Integer，无横向改造。

### F. 可靠性与可观测性
- F1：通过 — Redis 故障/脚本结果非法→503 TRACK_SEARCH_RATE_LIMIT_UNAVAILABLE；数据库故障/超时→503 TRACK_STORE_UNAVAILABLE。
- F2：通过 — 无吞异常；脚本返回结构逐项校验，非法即 503。
- F3：通过 — 额度先于数据库消耗（失败保留已消耗额度，符合设计）；损坏计数状态不被修改（集成测试断言）。
- F4：通过（不适用：无上传写入）。
- F5：通过（不适用：只读，无重复数据风险；并发快照一致性有测试）。
- F6：通过 — 仅 SLF4J。
- F7：通过 — 故障日志携带 MDC traceId，可由 TraceIdFilter 追踪。
- F8：通过 — warn 级别，仅异常类型，不记敏感请求体。
- F9：通过 — Actuator 未改动。
- F10：通过 — 无新增依赖（build.gradle 未变）。

### G. 测试
- G1：通过 — 测试均与本接口相关；两处旧 q 案例按新契约调整已在送审材料说明。
- G2：通过 — 单词/多词/跨字段/单字成功场景（WebMvc + 真实 MySQL）。
- G3：通过 — 空 q、纯空白、100/101 码点、5/6 词、重复词、Unicode 空白变体、溢出、非法排序/状态。
- G4：通过 — 未认证 401 且不耗额度。
- G5：通过 — 普通用户 DRAFT/ARCHIVED 403 且不耗额度。
- G6：通过（不适用：无单资源查询；空结果与越界空页已覆盖）。
- G7：通过 — 80 并发恰好 60 放行、饱和 429 计数与 TTL 保持、额度降低边界、快照并发一致性。
- G8：通过 — 码点/词数/特殊符号（`%`、`_`、`\`、`!`、SQL 片段）/大小写重音/空专辑/最深分页/最大页大小边界。
- G9：通过（不适用：只读接口）。
- G10：通过 — HTTP 状态码、JSON 契约、ProblemDetail type/code、Retry-After、no-store 精确断言。
- G11：通过 — Service 测试覆盖权限、额度顺序（InOrder）、异常转换。
- G12：通过 — 数据库集成测试均为 Testcontainers MySQL 8.4，无 H2。
- G13：通过 — Redis 限流为真实 Redis 7.4 集成测试（含真实 Lua、并发、损坏状态、PTTL 边界），超时测试为真实 MySQL SLEEP 预算中断。
- G14：通过 — 命名表达行为（如 `should_admit_exactly_sixty_concurrent_searches`）。
- G15：通过 — 各集成测试 setup 清理数据，不依赖执行顺序。
- G16：通过 — 独立 cleanTest check 2376/0/0/0，0 跳过，必要 Testcontainers 全部实跑。

### H. OpenAPI 与 API 文档
- H1：通过 — `@Tag`/`@Operation`/`@ApiResponse`/参数注解更新且与实现一致（含 429 Retry-After 头）。
- H2：通过 — 响应 DTO 为既有 `@Schema`，无结构变化。
- H3：通过 — OpenAPI 状态码/请求/响应与实现一致。
- H4：通过 — `docs/api/track/list-tracks.md` 已更新为完整搜索契约。
- H5：通过 — Method、完整 Path、用途明确。
- H6：通过 — 认证、角色、状态权限表完整。
- H7：通过 — 请求字段、校验规则、边界与示例完整。
- H8：通过 — 200 响应示例与字段表。
- H9：通过 — 错误状态码及稳定 code 表完整。
- H10：通过 — 三条可执行 curl 示例。
- H11：通过 — curl 使用 `${ACCESS_TOKEN}` 占位，无真实凭据。
- H12：通过 — 设计文档仅必要的状态行更新。

### I. 配置与构建
- I1：通过 — `app.track-search.*` 经环境变量外部化。
- I2：通过 — `.env.example` 仅占位默认值。
- I3：通过 — 未提交 `.env`/密钥/导出。
- I4：通过 — 无新增本地依赖，docker-compose 无需变更。
- I5：通过 — 构建无本地路径或人工步骤依赖。
- I6：通过 — 独立 `cleanTest check` BUILD SUCCESSFUL（带 MINIO_INTEGRATION_TESTS=true）。
- I7：通过 — 无 TODO/假数据/占位实现。
- I8：通过 — 可编译、可测试、可部署，无未记录环境依赖。

## 阻断项（Blockers）
- 无

## 非阻断项（Non-blocking）
- NOTE-001 [src/main/java/org/example/music/track/service/TrackSearchRateLimiter.java:29] Lua 脚本中 PTTL 返回 ≥0 后紧跟的 `GET` 若因键恰在两调用间到期（TTL≈0 的极窄竞态）返回 false，走 `not count` 分支返回 `{2,0}`（503 故障），而设计 §8.1 对“key 缺失”定义为重新建窗。实际影响极小且为安全失败（不会错误放行），出现概率为亚毫秒级竞态 → 可在脚本中对 `not count` 且 ttl≥0 时按缺失建窗处理，或在设计 §8.1 补一句该边界的既定行为；可排期，不影响本接口通过。

## 上轮反馈复核
- 首次审核：不涉及

## 已通过检查
- 范围检查（28 文件逐一核对、无迁移/.env/build.gradle 越界）、独立完整验证（199 套件 2376/0/0/0、MinIO 实跑、0 跳过）、A 全部、B 全部、C 全部、D 全部（D9—D11、D13、D14 不适用已注明）、E 全部、F 全部、G 全部（G6、G9 不适用已注明）、H 全部、I 全部。查询计划材料（88 组汇总 + 8 组完整实例）与送审声明一致，执行计划如实保留扫描成本，用户批准的 LIKE 取舍有据可查。

## Codex 下一步
1. 有效 `accepted`，无阻断项，可按 AGENTS.md 将 T04 登记完成：更新任务书、PROJECT_STATE.md 及设计/计划完成记录。
2. NOTE-001 按收益与范围决定本轮处理、排期或说明不采纳，不影响合并。
3. 后续接口按用户选定范围另行确认。

## 重新审核重点
- 本轮无需复审。若 NOTE-001 采用脚本或文档修改，可在下次相关变更中顺带核对该边界行为与设计文字的一致性。

## Codex对NOTE-001的证据核实

原分级保留为非阻断。已核对当前脚本PTTL和GET之间仅有数值判断，Redis 7.4官方资料提供以下依据。

- Redis对Lua执行采用固定逻辑时间，键过期判定在脚本内保持一致。[Redis复制文档](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/#how-redis-replication-deals-with-expires-on-keys)
- 7.4.0的commandTimeSnapshot返回固定cmd_time_snapshot，enterExecutionUnit在最外层进入时更新时间。[server.c时间快照](https://github.com/redis/redis/blob/7.4.0/src/server.c#L201-L220)、[执行单元入口](https://github.com/redis/redis/blob/7.4.0/src/server.c#L1053-L1066)
- PTTL计算及keyIsExpired使用相同的commandTimeSnapshot。[expire.c](https://github.com/redis/redis/blob/7.4.0/src/expire.c#L707-L727)、[db.c](https://github.com/redis/redis/blob/7.4.0/src/db.c#L1788-L1802)
- 官方脚本测试在脚本内设置1毫秒期限，延迟10毫秒后仍判断键存在，脚本结束后判断到期。[Redis 7.4官方测试](https://github.com/redis/redis/blob/7.4/tests/unit/scripting.tcl#L1764-L1783)

据此结合当前脚本判断，NOTE所描述的自然到期条件需要审核方复核。本次提交源码和测试与7a58bae相符，补充材料为本记录和审核请求。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001，非阻断 | Redis 7.4固定脚本时间，PTTL与GET采用相同快照，官方源码及测试支持复核原到期竞态描述 | 保留原非阻断分级 | 先复核触发条件，现有TYPE/PTTL初始缺失已按设计建窗，异常返回仍按额度故障处理 | 请求原Claude会话复核 | 本会话已独立复跑199套件2376/0/0/0，真实Redis边界测试通过，补充官方资料证据 |

## 第2轮证据复核原文

CLI退出码0、is_error=false、permission_denials为空，会话沿用928af25d-9e96-4e4a-96e0-17a9216ed28c。原始结果为build/reports/track-search-review-round2.json，诊断为同目录track-search-review-round2.stderr.log，实际modelUsage键为mimo-v2.6-pro。

---

## Verdict: accepted（维持；NOTE-001 复核后撤销）

审核任务：
- `GET /api/v1/tracks` 扩展可选查询参数 q（任务书 T04）— 本轮仅复核第 1 轮非阻断项 NOTE-001

审核轮次：
- 第 2/3 轮（意见复核沿用原任务与轮次，不重置计数）

Git 基线：
- 源码与测试基线保持送审 HEAD `7a58bae065dc7d2cad5e3dc3c2d36cafac7384c1`（与第 1 轮独立验证版本逐字节相同）。相对 7a58bae 的变化仅两个审核文档：`docs/reviews/2026-11-13-track-search-review-request.md`（修改）、`docs/reviews/2026-11-13-track-search-review.md`（新增），`git diff --name-only -- src build.gradle .env.example` 为空，已核对。

## 范围检查
- 结果：通过
- 预期范围：NOTE-001 证据复核（含 Codex 核实记录与复核请求文档更新）
- 实际变更文件：
  - `docs/reviews/2026-11-13-track-search-review-request.md`（反馈复核章节更新，+4/-2）
  - `docs/reviews/2026-11-13-track-search-review.md`（新增，含第 1 轮审核原文存档与 Codex 核实表）
- 越界改动：
  - 无。源码、测试、配置、迁移均未改动，无越界。

## 独立验证
- 同基线完整验证（复用第 1 轮独立执行，代码基线未变）：`MINIO_INTEGRATION_TESTS=true ./gradlew cleanTest check --console=plain`，BUILD SUCCESSFUL，日志 `build/track-search-independent-check.log`
  - 结果：通过；199 套件 / 通过 2376 / 失败 0 / 错误 0 / 跳过 0（MySQL、Redis、MinIO 实跑）
- 本轮专项证据验证（真实 Redis 7.4.11 容器，临时容器已清理）：
  - 执行命令：`docker run redis:7.4` + `redis-cli EVAL`（生产 CONSUME 脚本原文及等价片段）
  - 结果：通过，三项场景全部证实 Codex 证据（详见下文）
  - 跳过/失败：无

## NOTE-001 复核（沿用原编号）

**原意见（第 1 轮非阻断）**：PTTL 返回 ≥0 后紧跟的 GET 若因键恰在两调用间自然到期返回 false，走 `not count` 分支返回 `{2,0}`（503），与设计 §8.1 “key 缺失→建窗”不一致；建议脚本内按缺失建窗或设计补注边界行为。

**Codex 复核主张（review.md 核实表）**：Redis 对 Lua 执行采用固定逻辑时间，键过期判定在脚本内保持一致；PTTL 与 GET 之间仅有数值判断；请求复核自然到期竞态的触发条件，原分级保留。

**独立核实结果**：

1. **代码事实核对**：`TrackSearchRateLimiter.java:22-28` 中 PTTL（第 22 行）与 GET（第 28 行）之间仅 `ttl == -2`、`ttl < 0 or ttl > 60000` 两次数值判断，无其他 `redis.call`。Codex 对代码事实的描述准确。
2. **官方语义复核**：Redis 在脚本执行单元入口（`enterExecutionUnit`）固定时间快照，`commandTimeSnapshot()` 在脚本内返回该快照，`keyIsExpired`（db.c）与 PTTL 计算（expire.c）使用同一快照；复制文档亦以“脚本内过期判定确定性”作为主从一致前提。脚本为原子执行，期间无其他客户端可删除该键。
3. **真实 Redis 7.4.11 实证**（独立执行）：
   - 场景 A（脚本中途自然到期）：脚本内 `SET PX 40` 后立刻 `PTTL`=40，忙等循环约 380ms（远超 40ms TTL）后再次 `PTTL`=**40**、`GET`=`"v1"`、`TIME` 微秒级完全冻结，脚本结束后键确已到期（PTTL=-2）。**即 PTTL≥0 与 GET 在同一脚本内不可能因自然到期而不一致**，原意见假设的竞态触发条件不存在。
   - 场景 B1（饱和键脚本中途到期执行生产判定）：返回 `{1,1}`（429、Retry-After 1），与“PTTL 0 即将到期→受限重试 1 秒”契约一致，未出现 `{2,0}`。
   - 场景 B2（键在脚本开始前已自然到期）：TYPE 按冻结快照判定为缺失，走建窗分支返回 `{0,0}`、计数重建为 1，设计 §8.1 “key 缺失→建窗”语义得到满足。

**逐项处置**：

| 原编号与原分级 | 问题核实 | 分级判断 | 修改方向判断 | 处置 | 验证结果 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001，非阻断 | **不成立（误报）**：脚本内逻辑时间冻结，PTTL≥0 后 GET 必然命中，自然到期竞态不可触发 | 原非阻断分级随问题撤销 | 原建议（建窗改造/设计补注）不再需要；`not count` 防御分支保留即为合理实现 | **撤销** | 真实 Redis 7.4.11 三场景实证 + 官方源码/文档语义一致 |

- 非阻断项处置：Codex“先复核触发条件、暂不改动”的处置合理，证据充分；本项闭环，无需排期。
- 剩余争议：无。

## 审查清单
- A1—A8、B1—B8、C1—C13、D1—D15、E1—E12、F1—F10、G1—G16、H1—H12、I1—I8：全部沿用第 1 轮“通过”结论（含注明的不适用项）。依据：本轮 `git diff` 确认 src/测试/配置/迁移相对已独立验证的 7a58bae 逐字节未变，第 1 轮逐项审查结论与 2376/0/0/0 独立验证证据继续有效；本轮变更仅为审核文档，不影响任何清单项。

## 阻断项（Blockers）
- 无

## 非阻断项（Non-blocking）
- 无（NOTE-001 已撤销，原分级、原编号保留历史记录于 review.md 第 1 轮原文，不重编号）

## 上轮反馈复核
- NOTE-001（原非阻断）/ Codex 主张脚本内逻辑时间固定、竞态描述需复核 / 独立核实：代码事实准确，官方语义成立，真实 Redis 7.4.11 实证 PTTL 与 GET 跨真实到期仍一致（40→40→"v1"，TIME 冻结）/ **撤销误报** / 原因：脚本原子执行期间过期判定使用同一冻结时间快照，“键在 PTTL 与 GET 之间自然到期”的触发条件不存在；`not count` 为不可经自然到期触达的防御分支，按依赖故障处理是安全设计，无需修改脚本或设计文档。

## 已通过检查
- 本轮范围检查（仅两个审核文档）、同基线完整验证复用（199 套件 2376/0/0/0）、代码事实核对（PTTL 与 GET 间仅数值判断）、官方语义复核、真实 Redis 三场景实证、第 1 轮全部清单项维持通过。

## Codex 下一步
1. NOTE-001 已撤销，T04 闭环：零阻断项、零未决非阻断项，可按 AGENTS.md 登记完成（任务书、PROJECT_STATE.md、设计/计划完成记录），第 1 轮 `accepted` 继续有效。
2. 本复核记录（含 Redis 7.4.11 实证数据）可并入 review.md 的 Codex 核实表收尾行。
3. 后续接口按用户选定范围另行确认。

## 重新审核重点
- 无需复审。若未来调整 CONSUME 脚本或 Redis 版本基线，再核对脚本内过期判定一致性与本次实证结论是否仍成立。

## Codex最终处置登记

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001，原非阻断 | 官方时间快照语义及审核方真实Redis7.4.11三场景验证共同支持复核证据 | 保留历史原分级，由原审核方在第2轮撤销 | 原建议对应的自然到期条件经复核已排除，当前防御分支获得审核认可 | 审核方撤销，已闭环 | 同代码基线完整独立199套件2376/0/0/0，附真实Redis专项验证，零剩余争议 |

本次收尾登记使用7a58bae的源代码、测试、配置和API版本。两轮审核原文与Codex判断分别保留。两次CLI均退出0，JSON解析成功，is_error=false，permission_denials为空。第2轮复用同会话已完成的完整独立验证，并额外执行真实Redis7.4.11验证。

## 实际modelUsage返回值

以下分别保存两次CLI返回的原始modelUsage对象。

### 第1轮

```json
{
  "mimo-v2.6-pro": {
    "inputTokens": 140508,
    "outputTokens": 27497,
    "cacheReadInputTokens": 2363520,
    "cacheCreationInputTokens": 0,
    "webSearchRequests": 0,
    "costUSD": 2.571725,
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

### 第2轮

```json
{
  "mimo-v2.6-pro": {
    "inputTokens": 267395,
    "outputTokens": 42832,
    "thinkingTokens": 0,
    "cacheReadInputTokens": 3316480,
    "cacheCreationInputTokens": 0,
    "cacheCreationEphemeral5mInputTokens": 0,
    "cacheCreationEphemeral1hInputTokens": 0,
    "webSearchRequests": 0,
    "costUSD": 4.066015,
    "contextWindow": 1000000,
    "maxOutputTokens": 32000,
    "canonicalModel": "mimo-v2.6-pro",
    "provider": "firstParty",
    "costBasis": "unknown"
  }
}
```
