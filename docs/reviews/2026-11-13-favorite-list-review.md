# F03 本人收藏列表独立审核与处置

审核日期2026-11-13。送审68af0a49eeb895edfe0789b5fd76ee956642a029，实现基线8d52f35，第1/3轮accepted，零阻断项。CLI退出码0，is_error=false，permission_denials为空。实际模型mimo-v2.6-pro，会话1cd4c5c7-191e-4638-9ed7-8c904a0c2c46。

原始JSON为build/reports/favorite-list-review-round1.json，诊断日志为同目录favorite-list-review-round1.stderr.log。独立验证日志build/favorite-list-independent-check.log，cleanTest check带MINIO_INTEGRATION_TESTS=true，耗时19分46秒。Codex复核XML为194套件2293通过、0失败、0错误、0跳过，与自验一致。

## 审核原文

以下完整保留result原文，包括审核方的判断；Codex复核另列于后。
全部验证证据齐备。以下为标准审核报告。

---

## Verdict: accepted

审核任务：
- `GET /api/v1/users/me/favorites`（本人收藏分页列表，唯一接口）

审核轮次：
- 第 1/3 轮

Git 基线：
- main @ `68af0a49eeb895edfe0789b5fd76ee956642a029`（实现基线 `8d52f35`，工作树干净，与送审哈希一致）

## 范围检查
- 结果：通过
- 预期范围：`GET /api/v1/users/me/favorites` 单接口（实现、测试、V10 迁移、API 文档），含请求中披露的 F01/F02 旧 PUT/DELETE 缺少 ID 路由断言适配及状态文档登记
- 实际变更文件（31 个，与送审清单逐一对应，无 untracked、无 staged）：
  - `PROJECT_STATE.md`
  - `docs/api/favorite/favorite-track.md`
  - `docs/api/favorite/list-favorites.md`
  - `docs/api/favorite/unfavorite-track.md`
  - `docs/reviews/2026-11-13-favorite-list-review-request.md`
  - `docs/superpowers/plans/2026-11-12-favorite-list-plan.md`
  - `docs/superpowers/specs/2026-11-12-favorite-list-design.md`
  - `docs/tasks/backend-roadmap.md`
  - `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`
  - `src/main/java/org/example/music/favorite/controller/FavoriteListController.java`
  - `src/main/java/org/example/music/favorite/dto/FavoriteListItemResponse.java`
  - `src/main/java/org/example/music/favorite/exception/FavoriteListValidationException.java`
  - `src/main/java/org/example/music/favorite/repository/FavoriteListRepository.java`
  - `src/main/java/org/example/music/favorite/repository/FavoriteListRow.java`
  - `src/main/java/org/example/music/favorite/service/FavoriteListParameters.java`
  - `src/main/java/org/example/music/favorite/service/FavoriteListQueryService.java`
  - `src/main/java/org/example/music/favorite/service/FavoriteListService.java`
  - `src/main/java/org/example/music/favorite/service/FavoriteListSort.java`
  - `src/main/java/org/example/music/favorite/web/FavoriteListErrorAdvice.java`
  - `src/main/resources/db/migration/V10__add_favorite_list_index.sql`
  - `src/test/java/org/example/music/favorite/FavoriteListIntegrationTest.java`
  - `src/test/java/org/example/music/favorite/FavoriteListQueryPlanIntegrationTest.java`
  - `src/test/java/org/example/music/favorite/FavoriteListRepositoryIntegrationTest.java`
  - `src/test/java/org/example/music/favorite/FavoriteListSnapshotIntegrationTest.java`
  - `src/test/java/org/example/music/favorite/FavoriteTrackIntegrationTest.java`
  - `src/test/java/org/example/music/favorite/UnfavoriteTrackIntegrationTest.java`
  - `src/test/java/org/example/music/favorite/controller/FavoriteListControllerTest.java`
  - `src/test/java/org/example/music/favorite/service/FavoriteListParametersTest.java`
  - `src/test/java/org/example/music/favorite/service/FavoriteListProxyTest.java`
  - `src/test/java/org/example/music/favorite/service/FavoriteListQueryServiceTest.java`
  - `src/test/java/org/example/music/favorite/service/FavoriteListServiceTest.java`
- 越界改动：无
- 范围说明：F01/F02 两项集成测试的改动仅为新增集合 GET 后内部路由处理器断言的精确化适配（`HttpRequestMethodNotSupportedException` 替代 `ResourceHttpRequestHandler`），状态码、`INTERNAL_ERROR` 错误码、无业务调用与数据库无变化断言全部保留，两份 API 文档同步披露，对外 500 契约未变；属当前接口必要组成，已在送审请求中披露，不计越界。`build.gradle`、V1—V9 迁移、`.env` 均未改动。

## 独立验证
- 执行命令：`MINIO_INTEGRATION_TESTS=true ./gradlew.bat cleanTest check --console=plain`（Windows Gradle Wrapper，日志 `build/favorite-list-independent-check.log`）
- 结果：通过（BUILD SUCCESSFUL in 19m 46s，GRADLE_EXIT_CODE=0）
- 套件数：194
- 通过测试数：2293
- 失败测试数：0
- 错误测试数：0
- 跳过测试数：0
- 跳过测试：无（`build/test-results/test/TEST-*.xml` 全量汇总 194/2293/0/0/0，与 Codex 自报一致；MinIO 必要测试实际执行，如 `S3AudioStorageIntegrationTest` 5 项、`TrackCoverIntegrationTest` 18 项、`FavoriteListSnapshotIntegrationTest` 4 项、`FavoriteListQueryPlanIntegrationTest` 1 项、`FavoriteTrackIntegrationTest` 16 项，均 0 跳过。F03 新增 9 套件 124 项，与自报一致）
- 失败摘要：无
- 真实执行计划核验：`build/reports/favorite-list-query-plans.md`（本次运行重新生成）显示默认 `favoritedAt` 排序走 `ix_user_favorites_user_created_track` 覆盖索引反向扫描（`Using index`）后 eq_ref 关联歌曲主键；其他字段排序以 `user_id=const` 的本人收藏驱动 + 本人候选内 filesort；稀疏用户（1 条收藏）五字段计划均从 `f` 表 ref/eq_ref 访问、`type != ALL`，无全曲库扫描；count 有无状态过滤的 EXPLAIN ANALYZE 均收录。

## 审查清单

### A. 范围与契约
- A1：通过（唯一接口 `GET /api/v1/users/me/favorites`）
- A2：通过（Method/Path 与审核请求一致）
- A3：通过（31 个文件均与本接口直接相关）
- A4：通过（无顺手重构；F01/F02 断言适配为必要组成且已披露）
- A5：通过（有效 JWT；用户 ID 与角色仅取 `AuthenticatedUserPrincipal`；USER 含上传者仅 PUBLISHED，ADMIN 本人全部状态，管理员以 USER 登录按 USER，USER 显式隐藏状态 403；与设计矩阵一致）
- A6：通过（仅 page/size/sort/status；未知、重复、空白、溢出、非法格式 400；`Integer.parseInt` 语义不 trim，`+1`/`01` 接受；long 运算 `page×size≤10000`，100×100 过、101×100 拒）
- A7：通过（200 平铺项 + 非空 `favoritedAt` UTC 微秒；显式 null；page/size/totalElements/totalPages/hasNext 类型与设计一致；空页与合法越界页契约正确）
- A8：通过（分页、五字段双向单排序、同向 `track_id` 次排序、状态过滤、REPEATABLE_READ 短只读事务边界均与契约一致；幂等、缓存、专用限流按用户批准的设计不适用，成功与错误均 `no-store`）

### B. 代码分层
- B1：通过（Controller 仅参数收集、调用 Service、构造 no-store 响应）
- B2：通过（权限/角色判断与事务外异常转换在 `FavoriteListService`，快照读取在 `FavoriteListQueryService`）
- B3：通过（Repository 仅 native 投影与 count 数据访问）
- B4：通过（`FavoriteListItemResponse` record，复用 `TrackListItemResponse.Cover`，未暴露 Entity）
- B5：通过（`FavoriteListRow` 为只读投影 record，非 JPA Entity）
- B6：通过（Row→DTO 转换清晰，无循环依赖）
- B7：通过（构造器注入，字段 final）
- B8：通过（全源码 grep 无 TODO/FIXME/假实现/未使用代码）

### C. REST API 与错误处理
- C1：通过（`/api/v1/users/me/favorites` 复数资源）
- C2：通过（200/400/401/403/500/503 语义正确）
- C3：通过（`PageResponse<FavoriteListItemResponse>`，稳定结构）
- C4：通过（等价校验：自定义参数校验入口与歌曲列表一致；无请求体故无 `@Valid` 需求）
- C5：通过（`ProblemDetail` + `application/problem+json`，本集合路径错误 `no-store`）
- C6：通过（`VALIDATION_FAILED`/`FORBIDDEN`/`INTERNAL_ERROR`/`FAVORITE_STORE_UNAVAILABLE` 均沿用 `urn:problem:music:*`）
- C7：通过（400 参数、401 认证、403 状态权限、500 封面一致性、503 数据/事务故障）
- C8：通过（500/503 固定安全文案；测试断言 `"internal secret"`/`"private SQL"` 等不泄漏）
- C9：通过（`GlobalExceptionHandler` 新增 `FavoriteListValidationException` 分支，Controller 无手写错误响应）
- C10：通过（`Instant` UTC ISO-8601，微秒保留，`date_format` 文本读取规避 JDBC/JVM 时区偏移）
- C11：通过（统一 `page`/`size`/`sort` + `PageResponse`；STRAIGHT_JOIN 本人收藏索引驱动防全表扫描，真实执行计划证实）
- C12：通过（GET 幂等；重复 PUT 保留首次收藏时间、取消再收藏新时间影响默认排序均有真实 HTTP 测试）
- C13：通过（响应仅白名单字段，无凭据/内部信息）

### D. 安全
- D1：通过（`anyRequest().authenticated()` + `@PreAuthorize("isAuthenticated()")`）
- D2：通过（非管理接口；方法级认证已启用）
- D3：通过（伪造 `X-User-Id`/`X-Role`、正文身份、`userId` 参数均不能扩大范围并返回 400）
- D4：通过（归属仅取服务端认证主体）
- D5：通过（真实 JWT 集成覆盖过期、坏签名、缺声明、issuer/audience、token version 撤销、角色失效）
- D6：通过（无硬编码密钥）
- D7：通过（无新增配置项）
- D8：通过（日志仅 traceId 与异常类型，无 Token/正文/凭据）
- D9：不适用（无上传）
- D10：不适用（无文件读写）
- D11：通过（不访问 MinIO/S3，测试 `verifyNoInteractions(storage)` 证实）
- D12：通过（同步短只读事务、最多 3 条业务 SQL，无不可控长请求）
- D13：不适用（非播放接口）
- D14：不适用（无 CORS 改动）
- D15：通过（按用户批准的设计不设专用限流；默认参数校验先于业务查询）

### E. 数据库与迁移
- E1：通过（Flyway `V10__add_favorite_list_index.sql`）
- E2：通过（`ddl-auto=none` 未变）
- E3：通过（V10 版本号顺延、命名规范）
- E4：通过（V1—V9 未修改，diff 仅新增 V10）
- E5：通过（索引列 `(user_id, created_at, track_id)` 与查询使用一致）
- E6：通过（V9 联合主键、track_id 索引、两条 CASCADE 外键保留，升级测试断言列序与外键）
- E7：不适用（无新表、无审计字段变更）
- E8：通过（事务在 Service 层只读短事务，无远程调用入事务）
- E9：不适用（只读接口）
- E10：通过（执行计划证实索引驱动、无全表扫描；offset 上限约束，count 成本如实披露不承诺 SLA）
- E11：通过（真实 MySQL 8.4 Testcontainers 验证 V9 有数据升级 V10：行与审计字段不变）
- E12：通过（userId 为 Integer、trackId 为 Long，与 V9 现有 ID 表达一致）

### F. 可靠性与可观测性
- F1：通过（`DataAccessException`/`TransactionException` → 503；封面一致性 → 500）
- F2：通过（无吞异常，cause 保留，测试断言）
- F3：通过（只读事务失败无部分状态）
- F4：不适用（无写入/上传）
- F5：通过（只读无重复数据；重复收藏语义由 F01 契约与测试保证）
- F6：通过（SLF4J）
- F7：通过（`traceId` 经 MDC/`TraceIdFilter`）
- F8：通过（warn/error 级别，无敏感请求体）
- F9：通过（Actuator 未变）
- F10：通过（无新依赖）

### G. 测试
- G1：通过（测试均与本接口相关；F01/F02 修改为必要路由适配且保留原断言）
- G2：通过（成功请求多场景）
- G3：通过（参数校验失败覆盖未知/重复/空白/溢出/格式/边界）
- G4：通过（未认证 401，且先于参数校验）
- G5：通过（USER 显式隐藏状态 403，且在参数全部合法后才判定）
- G6：通过（集合语义无 404；空结果与合法越界页契约已覆盖，与设计一致）
- G7：通过（重复 PUT、取消再收藏、真实并发快照互不阻塞）
- G8：通过（size 0/101、page×size 边界、Integer 溢出、最长非法排序等）
- G9：通过（GET 幂等；重复请求一致性由快照与幂等读测试覆盖）
- G10：通过（ControllerTest 断言状态码、STRICT JSON 契约、ProblemDetail code、缓存头）
- G11：通过（ServiceTest/QueryServiceTest/ProxyTest 验证权限矩阵、异常转换、事务属性）
- G12：通过（Testcontainers MySQL，无 H2）
- G13：通过（真实 Redis/MinIO 容器；本接口以 `verifyNoInteractions(storage)` 验证不访问对象存储，S3 集成测试实际执行）
- G14：通过（命名表达行为）
- G15：通过（每次 seed 清理重建，不依赖顺序）
- G16：通过（独立运行 2293/0/0/0，0 跳过，必要容器实际执行）

### H. OpenAPI 与 API 文档
- H1：通过（`@Tag`/`@Operation`/`@ApiResponse`/`@Parameter` 齐全准确）
- H2：通过（DTO `@Schema` 齐全）
- H3：通过（`/v3/api-docs` 集成断言：无 requestBody、4 参数、200/400/401/403/500/503、DTO schema 无嵌套 track，PUT/DELETE 契约未变）
- H4：通过（`docs/api/favorite/list-favorites.md`）
- H5：通过（Method、完整 Path、用途）
- H6：通过（认证、角色、归属矩阵）
- H7：通过（请求字段、校验规则、请求示例）
- H8：通过（成功状态码与响应示例）
- H9：通过（错误状态码及稳定 code 表）
- H10：通过（可执行 curl 示例）
- H11：通过（`${ACCESS_TOKEN}` 占位符，无真实凭据）
- H12：通过（设计文档仅更新状态行，未扩展无关内容）

### I. 配置与构建
- I1：通过（无新增配置）
- I2：通过（`.env.example` 未动）
- I3：通过（无 `.env`/密钥提交）
- I4：通过（`docker-compose.yml` 未动，无新依赖）
- I5：通过（构建无本地路径/人工步骤依赖）
- I6：通过（`cleanTest check` 独立验证通过）
- I7：通过（无 TODO/假数据/占位实现）
- I8：通过（独立环境复现通过，2293/0/0/0）

## 阻断项（Blockers）
- 无

## 非阻断项（Non-blocking）
- NOTE-001 [src/main/java/org/example/music/favorite/repository/FavoriteListRepository.java:35-36] count 的 SQL 文本为独立字面量，与 `contentSql()` 不同，`FavoriteListQueryPlanIntegrationTest.java:66` 又手写了同一 count SQL；若日后调整联接或条件需三处同步 → 可提取共享的 count SQL 构造（类似 `contentSql`）以消除漂移风险，可排期，不影响当前正确性
- NOTE-002 [src/main/java/org/example/music/favorite/web/FavoriteListErrorAdvice.java:23] no-store 适配以路径精确等于 `/api/v1/users/me/favorites` 生效，尾斜杠变体 `/api/v1/users/me/favorites/` 的既有全局 500 响应不带 no-store（该路径对外 500 契约为有意保留的既有全局行为）→ 建议在既定的全局未匹配路由治理事项中一并统一错误响应缓存头，不纳入本次范围

## 上轮反馈复核
- 首次审核：不涉及

## 已通过检查
- 范围（31 文件逐一对应、无越界、无 untracked）、独立验证（194 套件 2293/0/0/0，MinIO 必要测试 0 跳过）、A1—A8、B1—B8、C1—C13、D1—D15（D9/D10/D13/D14 不适用已确认）、E1—E12、F1—F10、G1—G16、H1—H12、I1—I8 全部通过。重点核验结论：真实执行计划证实本人收藏索引驱动与稀疏查询无全曲库扫描；UTC 微秒读取与序列化实测保留 6 位微秒；授权与 count 严格同范围过滤；真实并发快照（取消/归档/删除/PUT）经闩锁同步点验证单响应一致性且不阻塞收藏写操作；事务开始/结束及三类查询故障均转 503 且 cause 保留，封面一致性故障正确归 500；OpenAPI 与文档一致。

## Codex 下一步
1. 本次为 `accepted`，无阻断项，可推进；请按 AGENTS 第 3.1 节确认两条非阻断建议的处置（本轮处理、排期或说明不采纳），并记录于完成登记。
2. 完成登记（任务书移入完成区、更新设计/计划/PROJECT_STATE）后，按用户选定范围另行确认下一个接口。

## 重新审核重点
- 本轮无需复审。若非阻断项后续落地，重点回归 count SQL 共享后 `FavoriteListQueryPlanIntegrationTest` 与生产查询的一致性，以及错误响应缓存头治理不影响既有接口契约。
## Codex 反馈复核与处置

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断 | 成立。Repository.count与执行计划测试分别维护同一count SQL，当前归属、状态、STRAIGHT_JOIN一致 | 认可，当前正确性与授权无误，属于将来维护漂移风险 | 认可共享SQL方向，但无需为本接口验收再改已验证代码 | 排期，在后续查询维护时提取countSql并由生产与执行计划共用 | 本轮完整自验和独立验证均194套件2293/0/0/0；后续变更需验证同范围计数与计划 |
| NOTE-002 非阻断 | Advice精确路径匹配成立，尾斜杠不进入该分支；但“响应不带no-store”不能仅由此推出，SecurityConfig未关闭默认安全响应头，本轮没有独立复现该尾斜杠最终缓存头 | 接受非阻断定位，不将尚未核实的缓存风险宣称为事实 | 统一错误缓存头的方向可纳入已有全局路由治理，先实测完整过滤链，避免重复覆盖安全默认值 | 与F01 NOTE-004合并排期；本轮不扩展全局错误契约 | 待全局治理时验证带/不带尾斜杠、认证/未认证、实际Cache-Control及异常映射；F03精确路径的no-store已有测试 |

无阻断项或阻断争议。两项非阻断意见已记录，保留原分级，不修改独立报告。当前代码与送审68af0a4一致，收尾仅更新文档；无需因非阻断排期重复全量测试。