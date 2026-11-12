# F01 收藏歌曲独立实现审核记录

审核执行工具为本机Claude Code，使用其默认模型mimo-v2.6-pro。审核会话为10676fc4-7bab-4d06-8370-a18aad24062f，审核提交为98152c0，进程退出码0，CLI结果is_error为false。以下保留审核原文，后附Codex反馈复核。

## Verdict: accepted

审核任务：
- `PUT /api/v1/users/me/favorites/{trackId}`（F01 收藏歌曲，首次实现审核）

审核轮次：
- 实现审核第 1/3 轮（沿用既有任务编号；前序设计审核第 1/3 轮 accepted，意见编号 NOTE-001/002 沿用）

Git 基线：
- main @ 08c78bb（设计基线）.. 98152c0（HEAD，实现提交 cb38211、b90125f、845c5a0、98152c0），working tree 另含已披露的 `docs/tasks/backend-roadmap.md` 修改与 untracked `PROJECT_STATE.md`

## 范围检查
- 结果：通过
- 预期范围：`PUT /api/v1/users/me/favorites/{trackId}` 单接口实现（含 V9 迁移、favorite 模块、503 异常映射、测试、API 文档及披露的文档/交接登记）
- 实际变更文件（`git diff --name-status 08c78bb..HEAD` 共 21 个，+1607/−9）：
  - src/main/resources/db/migration/V9__create_user_favorites.sql（新增）
  - src/main/java/org/example/music/favorite/repository/FavoriteRepository.java（新增）
  - src/main/java/org/example/music/favorite/service/FavoriteTrackTransactionService.java（新增）
  - src/main/java/org/example/music/favorite/service/FavoriteTrackService.java（新增）
  - src/main/java/org/example/music/favorite/exception/FavoriteStoreUnavailableException.java（新增）
  - src/main/java/org/example/music/favorite/controller/FavoriteTrackController.java（新增）
  - src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java（仅新增收藏 503 映射，+18 行）
  - src/test/java/org/example/music/favorite/ 下 7 个测试类 + FavoriteIntegrationSupport（新增）
  - src/test/java/org/example/music/track/TrackDeleteIntegrationTest.java（仅新增 1 个收藏级联场景与 DataSource 注入，+32/−2）
  - docs/api/favorite/favorite-track.md、docs/superpowers/specs/2026-11-11-favorite-track-design.md、docs/superpowers/plans/2026-11-12-favorite-track-plan.md、docs/reviews/ 两份（均在请求披露清单内）
  - 工作树：docs/tasks/backend-roadmap.md（既有用户任务书修改，已披露保留）、PROJECT_STATE.md（untracked 交接文件，已披露）
- 越界改动：
  - 无。`GlobalExceptionHandler` 与 `TrackDeleteIntegrationTest` 的既有文件改动均为本接口必要组成且已在请求中说明；V1—V8 迁移未改动（git diff 证实）；`build.gradle`、配置文件未动；工作树两项均为文档且已披露

## 独立验证
- 执行命令：`powershell.exe` 下 `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`（Docker 29.9.1 可用）
- 结果：通过（BUILD SUCCESSFUL in 18m 20s，EXITCODE=0）
- 通过测试数：2097
- 失败测试数：0
- 错误测试数：0
- 跳过测试数：0
- 跳过测试：
  - 无。Testcontainers MySQL 8.4、Redis 7.4、MinIO 均实际执行（build/test-results/test 下 179 个 XML 套件汇总；含 MinIO 门控的 TrackDeleteIntegrationTest 10/10 通过，无 skipped）
- 失败摘要：
  - 无
- 新增套件独立核对（XML 实际值）：FavoriteRepositoryIntegrationTest 6、FavoriteTrackTransactionServiceTest 7、FavoriteTrackServiceTest 7、FavoriteTrackProxyTest 4、FavoriteTrackControllerTest 13、FavoriteTrackIntegrationTest 16、FavoriteTrackConcurrencyIntegrationTest 9，合计新增 62 项 + TrackDeleteIntegrationTest 新增 1 项 = 63 项，与 2034 + 63 = 2097 吻合

## 审查清单
### A. 范围与契约
- A1：通过 — 仅 `PUT /api/v1/users/me/favorites/{trackId}` 一个接口，无其他 API 混入
- A2：通过 — FavoriteTrackController.java:32 `@PutMapping("/{trackId}")` + `/api/v1/users/me/favorites`，与审核需求一致
- A3：通过 — 21 个变更文件均与本接口直接相关
- A4：通过 — 无顺手重构或无关注释清理；TrackDeleteIntegrationTest.java:429 仅补齐文件尾换行，属同一文件正常编辑
- A5：通过 — 需登录、收藏归属认证本人、USER 仅 PUBLISHED、ADMIN 全部现存状态、按当前登录角色（loginRole）判定，与需求一致（FavoriteTrackTransactionService.java:30）
- A6：通过 — `@PathVariable @Positive Long trackId`（FavoriteTrackController.java:43），0/负数/小数/非数字/Long 溢出经测试断言 400 VALIDATION_FAILED；无请求体、未知查询参数与自定义头不参与身份判定（双层测试断言）
- A7：通过 — 204 空正文 + `Cache-Control: no-store`（FavoriteTrackController.java:47，测试断言）
- A8：通过 — 幂等为数据库联合主键 + `ON DUPLICATE KEY` 无变化更新、事务边界为歌曲行锁短事务（@Transactional，FavoriteTrackTransactionService.java:26）；无限流、无分页、无缓存，均按已确认契约明确不适用

### B. 代码分层
- B1：通过 — Controller 仅绑定路径参数、取认证主体、调用 Service、构造 204
- B2：通过 — 权限判断、可见性核验、事务边界在 Service（外层认证与故障翻译 + 内层事务）
- B3：通过 — Repository 仅两方法：锁定读状态、幂等插入，无业务规则
- B4：通过 — 无请求/响应实体暴露，204 无正文
- B5：通过 — 本接口未建 JPA Entity，原生 SQL 仅持久化映射，无 Entity 对外暴露
- B6：通过 — 无 DTO/Entity 转换需求，无循环依赖
- B7：通过 — 全部构造器注入，字段均 final
- B8：通过 — 无 TODO、FIXME、假实现（grep 核查新增目录为空）

### C. REST API 与错误处理
- C1：通过 — `/api/v1/users/me/favorites/{trackId}`，复数资源、本人子资源路径合理
- C2：通过 — 204/400/401/404/503 语义均有测试断言
- C3：通过 — 成功响应为空正文，不返回任何内部对象
- C4：通过 — Bean Validation 生效（实测 400 VALIDATION_FAILED），无需 @Valid 请求体
- C5：通过 — 错误为 ProblemDetail + `application/problem+json`（测试断言媒体类型）
- C6：通过 — 稳定 code + `urn:problem:music:favorite_store_unavailable`（GlobalExceptionHandler.java:1213 类型构造，FavoriteTrackControllerTest:92 断言）
- C7：通过 — 404 TRACK_NOT_FOUND 用于不存在/不可见，503 用于存储与事务故障，401 沿用认证链语义
- C8：通过 — 503 detail 为固定文案"歌曲收藏暂时无法完成"，注入 "private SQL" 异常时响应不含细节（测试断言）；堆栈仅服务端日志
- C9：通过 — 新增异常经 GlobalExceptionHandler.java:1052 统一映射，Controller 无手写错误响应
- C10：通过 — 成功响应无时间字段；持久化时间 UTC 微秒 DATETIME(6)（非 UTC 默认时区回归测试断言）
- C11：不适用 — 本接口无列表；已明确判断
- C12：通过 — 重复请求 204 且全字段保持首次值（真实 MySQL 断言）；12 并发同用户单条记录；两用户并行两条；提交成功但确认丢失后重试保留首次时间（真实提交后注入，测试断言）
- C13：通过 — 204 无正文，错误响应无密码哈希、Token、S3 凭据等

### D. 安全
- D1：通过 — SecurityConfig `anyRequest().authenticated()` 覆盖新路径，Service `@PreAuthorize("isAuthenticated()")` 双层一致
- D2：不适用（已判断）— 非管理接口，本人操作，管理面规则不适用；方法级认证已启用
- D3：通过 — 伪造 userId/role 查询参数、X-User-Id 头、JSON 正文均不改变收藏归属（HTTP 层与集成层双重断言）
- D4：通过 — 归属取 `AuthenticatedUserPrincipal.userId`，服务端判定
- D5：通过 — subject/角色/token version 沿用现有体系；token 版本失效、角色失效、用户删除均 401（TOKEN_REVOKED 等），真实 JWT 过完整过滤链
- D6：通过 — 新增代码无任何硬编码凭据（grep 核查）
- D7：通过 — 无新增配置项，Clock 用现有 `authenticationClock`（SecurityConfig.java:40，systemUTC）
- D8：通过 — 日志仅 traceId + 异常类型 + 服务端堆栈（GlobalExceptionHandler.java:1057），无敏感数据
- D9：不适用（已判断）— 无文件上传
- D10：不适用（已判断）— 无大文件读入
- D11：不适用（已判断）— 本接口不写音频/封面；删除级联测试经真实 MinIO 证实媒体仍走对象存储
- D12：通过 — 事务仅锁 + 检查 + 单条插入，简短可控；真实锁等待超时转 503 且可重试（并发测试实测）
- D13：不适用（已判断）— 无音频播放
- D14：通过 — 未新增或放宽任何 CORS 规则，沿用现有配置
- D15：通过 — 按已确认契约首版不设收藏专用限流与数量上限（API 文档已写明），非遗漏

### E. 数据库与迁移
- E1：通过 — V9__create_user_favorites.sql 经 Flyway 执行（flyway_schema_history 断言 version=9 success=1）
- E2：通过 — `spring.jpa.hibernate.ddl-auto=none` 保持（application.properties:7）
- E3：通过 — `V9__create_user_favorites.sql` 为下一个版本号与规范命名
- E4：通过 — V1—V8 未修改、未删除（git diff 证实）
- E5：通过 — 无 JPA Entity；information_schema 断言六列类型（int/bigint/datetime）与 DATETIME(6) 精度，与 SQL 一致
- E6：通过 — 六列 NOT NULL、联合主键 (user_id, track_id)、两条外键 CASCADE、显式 track_id 索引（V9:8-11，测试逐一断言）
- E7：通过 — created_at/updated_at/created_by/updated_by 齐全，首次写入取当前 userId，重复请求保持首值（契约确认行为，测试断言）
- E8：通过 — 事务边界在 FavoriteTrackTransactionService，锁内仅状态判断与单条写入，无远程调用入事务
- E9：通过 — 联合主键唯一约束 + `SELECT ... FOR UPDATE` 行排他锁，与归档/发布/删除协调（并发测试以真实锁等待验证）
- E10：通过 — 全部主键点操作，无查询接口、无 N+1、无无界查询
- E11：通过 — MySQL 8.4 Testcontainers 真实执行，迁移结构与行为均验证（本轮 0 跳过）
- E12：通过 — user_id INT 对齐 users.id、track_id BIGINT 对齐 tracks.id（information_schema 类型断言）

### F. 可靠性与可观测性
- F1：通过 — 存储/约束/锁等待/死锁/事务故障统一转 503 FAVORITE_STORE_UNAVAILABLE
- F2：通过 — 无吞异常、空 catch、假成功
- F3：通过 — 故障注入后回滚（count=0）且重试成功，业务状态一致（真实事务断言）
- F4：不适用（已判断）— 本接口无 S3/上传写入，无外部资源补偿需求
- F5：通过 — 重复/并发请求仅一条记录，首次时间保留
- F6：通过 — SLF4J，无 System.out/printStackTrace
- F7：通过 — 503 日志携带 `MDC.get("traceId")`，经 TraceIdFilter 可追踪
- F8：通过 — WARN 级别，仅异常类型与堆栈，无敏感请求体
- F9：通过 — Actuator 暴露范围未改动
- F10：通过 — 无新增依赖（build.gradle 未变更）；测试使用的 Awaitility 系既有测试类路径传递可用（另见 NOTE-003）

### G. 测试
- G1：通过 — 测试均属本接口；TrackDeleteIntegrationTest 仅增相关级联场景
- G2：通过 — 成功 204（Controller/Service/集成/并发多层）
- G3：通过 — 0/-1/abc/1.5/溢出 → 400
- G4：通过 — 无 Token、坏 Token、无效签名、过期、缺声明均 401，且业务零调用
- G5：通过 — 方法级认证拒绝未认证调用；角色可见性越界（USER 收藏 DRAFT/ARCHIVED、降级 ADMIN）→ 404
- G6：通过 — 不存在歌曲（Long.MAX_VALUE）→ 404 TRACK_NOT_FOUND
- G7：通过 — 重复提交、12 并发、双用户并发、归档/删除竞争、用户中途删除等冲突场景全覆盖
- G8：通过 — ID 边界（1、Long.MAX_VALUE、溢出）、锁超时、时间微秒边界均有断言
- G9：通过 — 幂等与"提交成功但确认丢失后重试保留首次时间"均有真实验证
- G10：通过 — 状态码、JSON 契约、ProblemDetail code/type、Cache-Control、OpenAPI 结构均断言
- G11：通过 — 状态权限矩阵、时间截断、异常转换、调用顺序（inOrder）均有单元测试
- G12：通过 — 数据库测试全部 Testcontainers MySQL 8.4，无 H2
- G13：通过 — Redis（认证状态）与 MinIO（删除级联 + 媒体对象清理）真实容器验证
- G14：通过 — `should_*` 行为式命名清晰
- G15：通过 — seed() 每用例清库重建，不依赖顺序或残留
- G16：通过 — 独立运行 2097/0/0/0，0 跳过，必要 Testcontainers 用例均实际执行

### H. OpenAPI 与 API 文档
- H1：通过 — @Tag/@Operation/@ApiResponse/@Parameter 齐备准确
- H2：通过 — 路径参数 @Schema 完整；无响应体 schema 需求
- H3：通过 — OpenAPI 实测断言 204 无 content、无 requestBody、六种状态码齐
- H4：通过 — docs/api/favorite/favorite-track.md 已新增
- H5：通过 — Method、完整 Path、用途齐备
- H6：通过 — 认证要求、USER/ADMIN 矩阵、loginRole 规则写明
- H7：通过 — 字段、校验规则、请求示例齐备
- H8：通过 — 204 与响应头示例齐备
- H9：通过 — 八类错误状态码及稳定 code 表格完整
- H10：通过 — curl 示例可执行
- H11：通过 — 仅 `${ACCESS_TOKEN}` 占位符，无真实凭据
- H12：通过 — 设计文档更新仅为确认状态与 NOTE 处置登记，符合按需更新

### I. 配置与构建
- I1：通过 — 无新增配置项
- I2：通过 — 未涉及 .env
- I3：通过 — 未提交任何密钥/凭据/数据库导出（PROJECT_STATE.md 已核查无秘密内容）
- I4：通过 — 无新增本地依赖，docker-compose 无需变更
- I5：通过 — 构建无本地路径或人工步骤依赖
- I6：通过 — `cleanTest check` 独立运行通过（2097/0/0/0）
- I7：通过 — 无 TODO、FIXME、假数据、占位实现
- I8：通过 — 可编译、测试、部署，无未记录环境依赖

## 阻断项（Blockers）
- 无

## 非阻断项（Non-blocking）
- NOTE-003 [src/test/java/org/example/music/favorite/FavoriteTrackConcurrencyIntegrationTest.java:3] 测试使用 `org.awaitility.Awaitility`，但 build.gradle 未显式声明该依赖，目前仅靠既有测试类路径传递引入（本轮构建实际可编译可运行）→ 建议在 dependencies 增加 `testImplementation 'org.awaitility:awaitility'`（版本由 Spring BOM 管理），防止上游依赖调整导致测试编译中断；不影响本接口正确性与部署，可排期处理
- NOTE-004 [src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java:1181] 未匹配路径落入 `@ExceptionHandler(Exception.class)` 兜底转 500 INTERNAL_ERROR，本接口缺路径段/尾斜杠实测为 500（FavoriteTrackIntegrationTest 已固定行为，API 文档已如实记录，非本次引入）→ 建议后续单独立项评估将 NoResourceFoundException 类未匹配路由映射为 404/405；属既有全局行为，Codex 可排期或说明不采纳，不影响本次通过

## 上轮反馈复核
- 既有设计审核非阻断项（沿用原编号）：
  - NOTE-001（原分级：非阻断）/ Codex 判断：成立，属实施澄清；取舍为两条 CASCADE 外键、审计列普通 INT、服务端写入本人 ID 并保持首值 / 独立核实：V9__create_user_favorites.sql:10-11 确认外键集合恰为 user_id、track_id 两条 ON DELETE CASCADE，created_by/updated_by 为普通 INT NOT NULL（V9:6-7）；FavoriteRepositoryIntegrationTest 外键集合、DELETE_RULE、真实用户删除级联与其他用户关系保留均经真实 MySQL 验证（本轮 6/6 通过）/ 处置：**已修复关闭**——设计歧义已由迁移结构与真实删除测试消除 / 原因及验证：独立 XML 汇总确认相关用例实际执行且通过
  - NOTE-002（原分级：非阻断）/ Codex 判断：成立，属 API 文档完整性；以真实路由测试固定行为再记录 / 独立核实：FavoriteTrackIntegrationTest:190-203 参数化 3 项实际通过，断言已认证 500 INTERNAL_ERROR + application/problem+json、未认证 401、ResourceHttpRequestHandler 兜底、Service 零调用、数据库零新增；API 文档 favorite-track.md:84-94 逐路径记录实测结果 / 处置：**已修复关闭**——行为已由真实 SpringBootTest 固定并写入文档 / 原因及验证：本轮 XML 汇总 16/16 通过、0 跳过，含该参数化用例
- 非阻断项处置：两项均按原非阻断分级闭环，未升级为通过条件；本轮新增 NOTE-003/004 均不影响 accepted 判定，Codex 可按收益排期或说明不采纳
- 剩余争议：无

## 已通过检查
- 范围检查：单接口、无越界、V1—V8 迁移未动、既有文件改动均为必要组成且已披露
- 独立验证：`MINIO_INTEGRATION_TESTS=true` 全量 `cleanTest check` 通过，179 套件 2097/0/0/0、0 跳过，MySQL/Redis/MinIO 真实执行
- 契约：角色矩阵、404/400/503 语义、204 幂等保留首次时间、归档保留与删除级联均与需求一致
- 真实事务核对：回滚故障注入（count=0）、提交成功后确认丢失重试保首值、真实行锁 + performance_schema 锁等待、锁超时 503 后重试、级联与媒体清理均经真实事务/容器验证
- 安全：身份仅取认证主体、伪造输入无效、JWT 体系一致、无凭据泄漏、日志脱敏
- 文档：OpenAPI 注解与实测一致，中文 API 文档含 curl 与错误码表

## Codex 下一步
1. 无需修复阻断项；按 AGENTS.md 第 3.1 节精神对 NOTE-003/004 逐项举证回应，选择本轮处理、排期或说明不采纳
2. 若采纳 NOTE-003，显式声明 Awaitility 测试依赖后重跑受影响测试即可，无需重审全部
3. accepted 已给出，可推进 F01 完成登记或进入下一接口（F02 取消收藏需另行单接口设计）

## 重新审核重点
- 本轮 accepted 无强制复查项；若 NOTE-003 落地，下轮顺带确认 build.gradle 依赖声明与构建通过即可
- 后续 F02/F03 设计与实现沿用本表 user_favorites 结构（联合主键、两条 CASCADE 外键、审计列普通 INT、首值保持约定）时，重点核对取消收藏的幂等语义与本表约定的一致性

## Codex审核反馈复核与完成登记

复核日期为2026-11-12。独立审核accepted，零阻断项。NOTE-001、NOTE-002已由审核方核实关闭。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-003，非阻断 | 事实成立，build.gradle仅显式声明spring-boot-starter-test。dependencyInsight显示Awaitility 4.3.0直接来自显式声明的spring-boot-starter-test 4.1.1，版本已由当前依赖管理确定 | 认可非阻断，当前编译与运行均通过，风险为后续测试依赖调整时的维护问题 | 显式声明可增强直接依赖表达，但当前已有测试starter提供该测试工具，重复声明的收益有限 | 本轮不采纳重复声明，沿用现有测试starter。后续拆分或升级测试starter时复核该依赖来源及并发测试编译 | 实际执行 `.\gradlew.bat dependencyInsight --dependency awaitility --configuration testCompileClasspath --console=plain`，退出码0，解析路径为starter-test 4.1.1直接引入awaitility 4.3.0。自验与独立完整测试均2097通过、0失败、0错误、0跳过 |
| NOTE-004，非阻断 | 成立。GlobalExceptionHandler.handleUnexpected将NoResourceFoundException交由Exception兜底并返回500，真实缺ID/尾斜杠测试及API文档结果一致，行为早于F01存在 | 认可非阻断，属于既有全局路由错误行为，F01正常入口契约正确 | 认可单独评估全局404/405及稳定错误契约的方向，需涵盖已有接口回归 | 排期为后续独立的全局路由异常契约治理，触发点为统一异常处理维护。当前F01保留已确认并实测记录的行为 | FavoriteTrackIntegrationTest的三种路径与认证组合均通过，收藏业务无调用且数据库无新增，API文档已明确。全局治理待独立设计 |

代码在审核期间保持98152c0提交状态，本轮收尾仅更新审核记录、设计/计划状态、任务书与交接材料。独立复跑XML已再次核对为179套件2097通过、0失败、0错误、0跳过。
