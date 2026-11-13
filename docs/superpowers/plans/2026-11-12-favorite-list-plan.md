# F03 本人收藏分页列表实施计划

> 执行者遵循 AGENTS.md，由 Codex 在当前会话逐任务实现，Claude Code CLI 独立只读审核。用户已指定执行方式，不分派其他实现 Agent。复选框只按实际执行结果更新。

**目标** 实现 `GET /api/v1/users/me/favorites`，按本人归属及角色可见范围分页读取收藏，支持状态与五类排序，返回收藏时间。

**架构** 收藏模块增加独立 Controller、参数类型、外层权限 Service、只读事务 QueryService 和 Repository。复用 PageResponse 与现有封面批量元信息查询，内容、精确计数及封面处于同一 REPEATABLE_READ 快照。

**技术栈** Java 21、Spring Boot 4.1.1、Spring MVC/Security、JPA、Flyway、MySQL 8.4、JUnit 5、MockMvc、Testcontainers MySQL/Redis/MinIO，沿用 build.gradle，不增加依赖。

**设计依据** [F03 设计](../specs/2026-11-12-favorite-list-design.md)，用户于 2026-11-12 审阅批准。计划基线 `main @ 8d52f35`，工作目录 `D:\code\Java-code\projects\music`。当前仅编写计划，尚未执行实现测试。

## 全局约束

- 唯一接口为 GET /api/v1/users/me/favorites；用户 ID 为 Integer、歌曲 ID 为 Long，身份只取 AuthenticatedUserPrincipal。
- USER 仅 PUBLISHED；ADMIN 默认全部状态，管理员以 USER 登录按 USER 规则。显式请求隐藏状态的 USER 返回 403。
- page 默认 0、size 默认 20，size 为 1—100；long 运算检查 page × size ≤ 10000。认证、参数校验、状态权限依次执行。
- 仅接受 page、size、sort、status；未知、重复、空值、溢出及非法格式返回 400 VALIDATION_FAILED。数字解析保持 Integer.parseInt 语义，不 trim。
- sort 默认 favoritedAt,desc；字段为 id、title、artistName、createdAt、favoritedAt，方向严格 asc/desc；非 id 排序追加同向歌曲 id。
- 平铺歌曲列表项增加非空 Instant favoritedAt；UTC ISO-8601，DATETIME(6) 读取保持微秒精度，不依赖 JVM 默认时区。
- 成功 200、application/json、no-store；空结果与合法越界页均成功，统计仅含本人可见筛选范围。
- 数据访问与事务故障使用现有 503 FAVORITE_STORE_UNAVAILABLE；认证故障沿用既有契约，本页封面一致性故障为 500 INTERNAL_ERROR。
- 最多三条业务 SQL，认证 SQL 另计；不得内存分页、分页后过滤隐藏歌曲、逐条查封面或访问 MinIO。精确计数不承诺恒定成本。
- 新增 V10 索引迁移，保持 V1—V9、F01、F02 和现有歌曲列表外部行为不变。不增加缓存、搜索、专用限流或跨请求快照。
- 完整验证设置 MINIO_INTEGRATION_TESTS=true，必要容器 0 跳过；中文文档与提交说明，独立审核 accepted 后才登记完成。

## 审查重点

1. 非 UTC 默认时区可能二次偏移 DATETIME(6)，任务 2 验证同一精确 Instant，finally 恢复时区。
2. 隐藏歌曲与其他用户收藏可能污染 count 或占据页面，任务 2、4 验证筛选先于分页及内容/计数一致。
3. 空页及无封面仍可能在事务结束阶段失败，任务 3 验证实际代理边界的 503，不伪造成功空页。
4. 同值排序与歌曲/收藏创建时间混用可能导致错页，任务 2、4 验证五字段双向排序和明确不同的两类时间。
5. 取消、归档、删除发生在多次 SELECT 之间可能产生混合结果，任务 4 使用真实并发事务验证单次快照及下次刷新。

## 文件结构

以下路径相对项目根目录。为缩短任务引用，`M` 指 `src/main/java/org/example/music`，`T` 指 `src/test/java/org/example/music`。

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 新建 | M/favorite/service/FavoriteListParameters.java | 原始多值查询参数校验 |
| 新建 | M/favorite/service/FavoriteListSort.java | 排序白名单与解析 |
| 新建 | M/favorite/exception/FavoriteListValidationException.java | 专用参数错误 |
| 新建 | M/favorite/dto/FavoriteListItemResponse.java | 平铺列表 DTO 与 OpenAPI Schema |
| 新建 | M/favorite/repository/FavoriteListRow.java | 本页数据库投影 |
| 新建 | M/favorite/repository/FavoriteListRepository.java | 本人范围内容查询及 count |
| 新建 | src/main/resources/db/migration/V10__add_favorite_list_index.sql | 收藏时间索引 |
| 新建 | M/favorite/service/FavoriteListQueryService.java | 快照读取、封面验证及响应组装 |
| 新建 | M/favorite/service/FavoriteListService.java | 角色权限及事务外异常转换 |
| 新建 | M/favorite/controller/FavoriteListController.java | 独立集合 GET 与 OpenAPI |
| 新建 | M/favorite/web/FavoriteListErrorAdvice.java | 仅本集合路径的 ProblemDetail 缓存头 |
| 修改 | M/common/exception/GlobalExceptionHandler.java | 新参数异常映射 400 |
| 新建 | T/favorite/service/FavoriteListParametersTest.java | 参数与排序 |
| 新建 | T/favorite/FavoriteListRepositoryIntegrationTest.java | 真实查询、排序与 UTC |
| 新建 | T/favorite/FavoriteListQueryPlanIntegrationTest.java | V9 升级 V10、索引与执行计划 |
| 新建 | T/favorite/service/FavoriteListQueryServiceTest.java | 批量封面与分页组装 |
| 新建 | T/favorite/service/FavoriteListServiceTest.java | 角色范围与异常分类 |
| 新建 | T/favorite/service/FavoriteListProxyTest.java | 实际安全及事务代理 |
| 新建 | T/favorite/controller/FavoriteListControllerTest.java | HTTP 契约与错误头 |
| 新建 | T/favorite/FavoriteListIntegrationTest.java | 真实 JWT、数据与 SQL 次数 |
| 新建 | T/favorite/FavoriteListSnapshotIntegrationTest.java | 真实并发快照与写操作互不阻塞 |
| 修改 | T/favorite/FavoriteTrackIntegrationTest.java、T/favorite/UnfavoriteTrackIntegrationTest.java | 集合 GET 新增后缺失 ID 路由内部异常的精确断言，保留原 HTTP 与无副作用断言 |
| 修改 | docs/api/favorite/favorite-track.md、docs/api/favorite/unfavorite-track.md | 说明上述路由处理差异，对外错误不变 |
| 新建 | docs/api/favorite/list-favorites.md | 中文 API 与 curl |
| 新建 | docs/reviews/2026-11-12-favorite-list-review-request.md | 完整单接口送审材料 |
| 新建 | docs/reviews/2026-11-12-favorite-list-review.md | 独立审核来源、原文与处置 |
| 更新 | 本计划、F03 设计、docs/tasks/backend-roadmap.md、PROJECT_STATE.md | 实际进度及审核后完成登记 |

复用而不改动 FavoriteIntegrationSupport 的 MySQL/Redis 容器、jdbc、mvc、userId/adminId、publishedId/draftId/archivedId、createTrack、bearer、transaction；新增收藏与封面测试数据在各测试类内准备。查询计划测试参考 TrackListQueryIntegrationTest 的隔离容器与 Flyway target 模式。

## 任务 1 参数、排序与响应类型

**文件** 文件表中的 FavoriteListParameters、FavoriteListSort、FavoriteListValidationException、FavoriteListItemResponse 与 FavoriteListParametersTest。

**输入** `MultiValueMap<String,String>`，TrackStatus，既有 `TrackListItemResponse.Cover`。

**输出接口**

- `record FavoriteListSort(String property, boolean ascending)`，`static FavoriteListSort parse(String raw)`。
- `record FavoriteListParameters(int page, int size, FavoriteListSort sort, TrackStatus requestedStatus)`，`static FavoriteListParameters from(MultiValueMap<String,String> raw)`；构造器也校验值域。
- `FavoriteListValidationException extends RuntimeException`，无参构造器；固定安全文案“收藏列表查询参数无效”。
- `record FavoriteListItemResponse(Long id, String title, String artistName, String albumName, Long durationMs, String status, TrackListItemResponse.Cover cover, Instant favoritedAt)`，字段 Schema 与设计一致。

- [x] 编写 `should_use_favorite_time_descending_defaults`，断言默认参数如下。

```java
var p = FavoriteListParameters.from(new LinkedMultiValueMap<>());
assertEquals(0, p.page());
assertEquals(20, p.size());
assertEquals(new FavoriteListSort("favoritedAt", false), p.sort());
assertNull(p.requestedStatus());
```

- [x] 运行 `.\gradlew.bat test --tests "org.example.music.favorite.service.FavoriteListParametersTest" --console=plain`，记录新类型缺失导致的失败。
- [x] 实现上述类型，沿用歌曲列表数字解析规则，独立排序白名单不修改 TrackListParameters/TrackListSort。
- [x] 增加参数化断言：五字段与双方向通过，status 三枚举通过；page=100/size=100 通过，101/100 拒绝；size=0/101、负页、溢出、空白、重复值、未知 q/userId、ALL、多排序均抛 FavoriteListValidationException；解码后的 +1 和 01 接受而两侧空格拒绝。
- [x] 重跑任务命令，记录全部通过后提交本任务文件，说明 `feat: 定义收藏列表分页参数与响应契约`。

## 任务 2 索引、本人查询与 UTC 投影

**文件** 新迁移、FavoriteListRow、FavoriteListRepository、FavoriteListRepositoryIntegrationTest、FavoriteListQueryPlanIntegrationTest。

**输入** 任务 1 的 FavoriteListSort；现有 V9 表、歌曲状态与 EntityManager。

**输出接口**

- `record FavoriteListRow(Long id, String title, String artistName, String albumName, Long durationMs, TrackStatus status, Long coverAssetId, Instant favoritedAt)`。
- `List<FavoriteListRow> FavoriteListRepository.findRows(Integer userId, TrackStatus status, int offset, int size, FavoriteListSort sort)`，status 为 null 表示全部状态。
- `long FavoriteListRepository.count(Integer userId, TrackStatus status)`。
- `static String FavoriteListRepository.contentSql(FavoriteListSort sort, boolean filtered)`，供生产查询及真实执行计划共用，避免测试另写一套 SQL。

- [x] 编写 `should_filter_owner_and_status_before_paging`：本人三状态收藏、另一用户同歌收藏，查询本人 PUBLISHED/size=1，仅返回已发布歌曲且 count=1，另一用户记录不影响结果；ADMIN 范围由传 null 状态查询三条。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.favorite.FavoriteListRepositoryIntegrationTest" --console=plain`，记录新增类型缺失失败。
- [x] 实现 JOIN、白名单排序、绑定条件、LIMIT/OFFSET 和同范围 count。投影不读 Entity/ID3；收藏 DATETIME(6) 按 UTC 转 Instant，不通过默认时区解释 Timestamp。查询在调用方事务内执行，使用普通 SELECT。
- [x] 新增 V10，仅添加 `ix_user_favorites_user_created_track (user_id, created_at, track_id)`。不修改旧迁移、约束和字段。
- [x] 增加 `should_distinguish_track_time_from_favorite_time_and_break_ties_by_id`，种入两类时间顺序相反及同值行，断言五类双向排序的完整 ID 顺序；`should_read_microseconds_independently_of_default_timezone` 在 UTC 与 Pacific/Honolulu 均断言 `Instant.parse("2026-11-12T08:30:00.123456Z")`，finally 恢复原时区。
- [x] 编写 V9 有数据升级 V10 测试，断言行及审计字段未变、索引列序正确、旧外键仍存在。准备 12000 条本人收藏、重复字段、三状态及其他用户数据，ANALYZE 后对五字段双向、有无状态、offset=0/10000 记录真实 EXPLAIN，选默认与代表性其他排序补 EXPLAIN ANALYZE；结果数量依实际筛选范围核对，允许合法空页。
- [x] 将执行计划写入 `build/reports/favorite-list-query-plans.md`，包含内容和 count 访问路径、实际扫描与排序成本，不断言固定索引名称必须被优化器选中。若需要扩大索引或改契约，先报告证据。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.favorite.FavoriteListRepositoryIntegrationTest" --tests "org.example.music.favorite.FavoriteListQueryPlanIntegrationTest" --console=plain`，0 失败且容器 0 跳过后提交，说明 `feat: 增加本人收藏分页查询与排序索引`。

## 任务 3 权限、快照组装与故障边界

**文件** FavoriteListService、FavoriteListQueryService 及对应两个单测和 FavoriteListProxyTest。

**输入** 任务 1 参数、任务 2 Repository 与投影，`MediaAssetRepository.findListCovers(Collection<Long>)`（传入去重 Set）、TrackListCoverRow、AuthenticatedUserPrincipal、FavoriteStoreUnavailableException。

**输出接口**

- `PageResponse<FavoriteListItemResponse> FavoriteListService.list(FavoriteListParameters parameters, AuthenticatedUserPrincipal actor)`，方法级 isAuthenticated。
- `PageResponse<FavoriteListItemResponse> FavoriteListQueryService.query(Integer userId, FavoriteListParameters parameters, TrackStatus status)`，readOnly=true、REPEATABLE_READ。

- [x] 编写 `should_restrict_non_admin_to_published`，USER 未指定状态向 query 传 PUBLISHED，USER 筛选隐藏状态抛 AccessDeniedException 且不查询；ADMIN 原样传状态/null，始终传 actor.userId。
- [x] 编写 `should_batch_distinct_covers_and_preserve_track_urls`：两首歌共享一个资产，验证仅一次批量查询；两首歌各自 URL 正确，favorites.count 与 findRows 的用户及状态完全一致。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.favorite.service.FavoriteList*" --console=plain`，确认新 Service 缺失失败后实现两层 Service。
- [x] QueryService 完成本页投影、count、去重资产查询及 DTO 组装；复用 Cover 类型，沿用歌曲列表对 kind、MIME、大小、摘要和定位元信息校验。无封面与空页不查资产；分页页数用商加余数，hasNext 用 long 比较。
- [x] 增加 `should_keep_real_totals_for_out_of_range_page`、`should_fail_only_for_invalid_referenced_covers`，断言空页真实总数及 hasNext=false、缺失封面返回 null、坏引用抛 IllegalStateException。参数化覆盖全部封面校验条件；外层只转换 DataAccessException/TransactionException，保留 cause，不把一致性错误归类为 503。
- [x] 编写实际 Spring 代理测试 `should_translate_transaction_boundary_failure_even_for_empty_page`，覆盖开始与结束故障、空页、无封面页及有封面页；断言 FavoriteStoreUnavailableException 且原 cause 保留。未认证直接调用外层代理被拒绝且无查询；验证事务内只读及隔离设置。
- [x] 重跑任务命令全部通过后提交，说明 `feat: 实现收藏列表权限与快照读取`。

## 任务 4 HTTP、真实集成、快照与 API 文档

**文件** FavoriteListController、FavoriteListErrorAdvice、GlobalExceptionHandler、新 ControllerTest、FavoriteListIntegrationTest、FavoriteListSnapshotIntegrationTest、docs/api/favorite/list-favorites.md。

**输入** 任务 3 Service，现有 JWT/Security、统一错误响应及 FavoriteIntegrationSupport。

**输出接口** `ResponseEntity<PageResponse<FavoriteListItemResponse>> FavoriteListController.list(MultiValueMap<String,String> raw, AuthenticatedUserPrincipal actor)`；GET 集合路径与 F01/F02 Controller 共存，不改写操作构造器。

- [x] 先编写 `should_return_flat_items_with_favorite_time_and_no_store`，MockMvc GET 默认参数，断言 200、no-store、items[0].id/favoritedAt 与分页六字段；无 RequestBody，albumName/durationMs/cover 为显式 null。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.favorite.controller.FavoriteListControllerTest" --console=plain`，记录 GET 尚未提供导致的失败。
- [x] 实现 Controller 与 OpenAPI，参数传给任务 1 校验。统一异常处理增加参数异常分支，400 固定 detail“收藏列表查询参数无效”；独立 ResponseBodyAdvice 仅匹配精确集合路径的 ProblemDetail，设置 no-store、移除 ETag/Content-Length。复用现有收藏 503 映射，不改变共享文案。
- [x] ControllerTest 覆盖 400/401/403/500/503 媒体类型与头、校验优先级、未知 userId/q、重复参数、全部边界、原异常不泄漏；伪造身份头及附带正文不参与归属。
- [x] 实际 JWT 集成覆盖 USER/ADMIN/管理员以 USER 登录、上传者限制、三状态、多人同歌、所有隐藏为空、越界页、五字段双向、SQL 注入样式参数拒绝。坏签名、过期、必要声明、撤销版本和失效角色沿用真实认证行为。
- [x] 通过实际 PUT/DELETE/GET 验证重复 PUT 保留 favoritedAt、取消后重收藏更新时间并改变默认位置；新时间落在真实请求时间区间内，以旧时间种子避免计时精度偶发失败。
- [x] 集成验证正常/共享/损坏封面、隐藏及其他页损坏隔离，元信息合法而对象不存在仍可返回列表。通过现有存储 Bean spy 验证无调用，SQL 计数仅覆盖列表业务读取，排除认证 SQL，断言最多 3 条、空页及无封面至多 2 条。
- [x] 用实际 QueryService 代理和 Repository spy 同步点，在内容读取后暂停，由另一个真实事务分别取消、归档、删除并提交，再恢复 count 和封面查询；断言本次 items/count/cover 属于旧快照，下次 GET 反映变化。使用 CountDownLatch、有界 Future 和 finally 释放/关闭线程，不用固定 sleep 推测竞争。
- [x] 验证列表快照暂停时同歌 PUT/DELETE 能在期限内完成，确保没有主动歌曲排他锁；真实事务故障及三处数据查询故障 HTTP 返回 503；本页一致性故障为 500。
- [x] 写中文 API 文档，包含完整参数、精确 JSON/null、UTC、角色规则、排序次序、快照边界、查询成本、错误及 POSIX curl 占位符。通过 /v3/api-docs 验证 GET DTO/参数以及既有 PUT、DELETE 契约仍在。
- [x] 设置 `$env:MINIO_INTEGRATION_TESTS = "true"`，执行 `.\gradlew.bat test --tests "org.example.music.favorite.*" --tests "org.example.music.track.*List*" --console=plain`，从 XML 记录实际通过/失败/错误/跳过，全部通过且必要容器未跳过后提交，说明 `feat: 提供本人收藏分页列表并验证快照语义`。

## 任务 5 完整验证、独立审核与完成登记

**文件** 审核请求、审核报告、本计划、设计状态、任务书及 PROJECT_STATE.md。业务修复仅限审核确认成立且属于 F03 的问题，所有新增或修改文件必须披露。

- [x] 执行 `git diff --check`，检查相对基线的完整文件列表（含 untracked）及 V1—V9 未变，检查所有文档相对链接。
- [x] 设置 `$env:MINIO_INTEGRATION_TESTS = "true"` 后运行 `.\gradlew.bat cleanTest check --console=plain`。保存日志，汇总 build/test-results/test/TEST-*.xml 的套件、通过、失败、错误、跳过数量；失败先修复，必要容器跳过不能送 accepted 审核。
- [x] 准备中文 review-request，明确唯一 GET、已确认契约、全部文件、无新依赖、V10、API 路径、基线和送审提交、实际命令及数量、执行计划证据与成本、首次反馈“不涉及”。源代码或测试变动后重新执行受影响验证和完整基准。
- [x] 提交完整送审材料，核对 Git 范围；按最新 AGENTS.md 8(7) 自动运行本机 `claude --print`，Read/Glob/Grep/Bash 工具，禁用 Write/Edit/NotebookEdit，提示独立只读审核并带 MinIO 重跑 cleanTest check。分别保存 `build/reports/favorite-list-review-round1.json` 和 `.stderr.log`，持续监控到退出。
- [x] 检查 exit code、JSON、is_error、permission_denials、result、实际 XML 及完整 check 证据，记录实际 session_id/modelUsage、基线和轮次。按原样保存审核原文，Codex 处置另列，不把 CLI 成功等同于 accepted。
- [x] needs_revision 时逐项核实事实、分级和方向，按原编号记录六列处置表，范围内修复并验证，`--resume` 接续同一会话，逐轮保存材料。最多三轮，escalated 停止修改交人类决策。
- [x] 有效 accepted 后登记所有非阻断项处置，更新设计和计划真实完成状态，将 F03 移入任务书已完成区，更新 PROJECT_STATE；文档检查通过后提交 `docs: 登记收藏列表审核通过并归入完成区`，汇报验证及遗留项，推送依用户授权执行。

## 计划自查与执行交接

实施证据补充：初始 JOIN 的真实执行计划显示部分排序可能从全曲库扫描。范围内改用 STRAIGHT_JOIN，由本人收藏索引驱动并逐项查歌曲主键；默认时间排序使用 V10 覆盖索引，其他字段在本人范围排序。补充 12000 曲库中仅 1 条收藏的稀疏样本，避免全曲库扫描，不增加索引或改变契约。

扩大回归发现 F01/F02 缺失 ID 的精确集合路径现在抛 HttpRequestMethodNotSupportedException，原先是静态资源回退；既有全局映射仍返回500。相应两项旧测试的处理器断言需调整为精确异常断言，保留状态、错误码、无业务调用和数据库无变化断言，并更新两份API文档。此依赖变更在送审范围中披露。

设计 1—5 节对应任务 1、4，6—8 节对应任务 2、3、4，9 节对应任务 3、4，10 节对应任务 2—5。已核对类型、签名、状态 null 语义、UTC 转换、五个审查重点归属、真实容器和独立审核路径。

用户已批准计划并授权实施。任务1—3已完成，参数、真实MySQL查询与迁移、Service及实际代理测试通过；任务4完成；扩大回归的两项路由断言已修正，41项F01/F02复跑通过。完整cleanTest check为194套件2293通过、0失败、0错误、0跳过，耗时20分16秒。独立第1轮accepted，194套件2293/0/0/0，19分46秒。执行方式保持Codex实施、Claude Code只读审核。

独立审核第1轮直接accepted，needs_revision/escalated分支不适用；其复选框表示条件流程已核对。两条非阻断意见已逐项复核并排期，完整原文与处置见2026-11-13-favorite-list-review.md。
