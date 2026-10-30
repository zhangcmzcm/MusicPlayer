# 分页歌曲列表接口实现计划

> 执行方式：沿用项目 AGENTS.md，Codex 在当前会话实现，Claude 只读独立审核。不派发其他 Agent 编写代码。步骤使用复选框跟踪。

**目标：** 实现 `GET /api/v1/tracks`，按可信登录角色与可选 status 分页、受控排序并批量组装封面信息。

**架构：** Controller 校验原始查询参数并返回明确 DTO；Service 在进入数据库前确定授权范围，独立查询 Bean 在 REPEATABLE_READ 短只读事务内读取投影、总数与去重封面批次。外层 Service 转换数据库和事务启动故障，Repository 负责有界数据访问。

**技术栈：** Java 21、Spring Boot 4.1.1、Spring MVC/Security、JPA Criteria、MySQL 8.4、JUnit 5、MockMvc、Testcontainers。实际版本以 build.gradle 为准，不新增依赖。

**规格：** [分页歌曲列表设计](../specs/2026-10-30-track-list-design.md)，已获用户确认；实现基线 main @ 93685fb。

## 全局约束

- 一个接口：`GET /api/v1/tracks`；不新增 /draft、/admin/tracks 或搜索。
- page=0、size=20、sort=id,desc；size 范围 1—100；long 运算的 page×size 不超过 10000。
- 参数仅 page、size、sort、status，未知、重复、空值、格式或范围错误均为 400 VALIDATION_FAILED。
- status 严格 DRAFT/PUBLISHED/ARCHIVED；排序字段仅 id/title/artistName/createdAt，方向严格 asc/desc，单一条件，非 ID 排序补同向 ID。
- 非管理员默认/显式 PUBLISHED；显式 DRAFT/ARCHIVED 为 403；ADMIN 默认全部状态。管理员 USER 登录降权，上传者无例外。
- 响应 items、page、size、totalElements、totalPages、hasNext；计数 Long、页码及大小 Integer；显式 null 字段；成功及业务错误 no-store。
- 不返回 Entity、Spring Page、ID3、审计或存储内部字段，不查询 MinIO；正常无封面为 null，异常资产使本页 500。
- 最多三条业务 SQL；认证查询单独计量。全部状态/单状态分别构造受控谓词，不使用可空 OR 或字符串拼接输入。
- 不改既有迁移、配置或依赖；如确需索引，仅在 EXPLAIN 证据支持下新增下一可用版本迁移，当前为 V8。
- 不顺手处理 O01/O06/O07/O08；文档、计划、提交说明使用中文。
- 最终 MINIO_INTEGRATION_TESTS=true 全量 check；skipped 不算通过。

## 重点复核

1. 重复参数和未知参数在 Spring 默认绑定时可能被吞掉：保留原始 MultiValueMap，并固定 400；任务 1/4 测试覆盖。
2. page×size 与总页数计算溢出，或越界空页被误推导为总数零：使用 long 乘积、除法加余数，显式 count；任务 1/2 测试覆盖。
3. 正文被过滤但总数含隐藏状态，或 USER 登录管理员被当作 ADMIN：先决定有效过滤，两个查询复用同一状态；任务 2/4 测试覆盖。
4. 共享封面重复查询，或列表多行导致 N+1/读 ID3：封面 ID 去重、投影查询、SQL 计量；任务 2/3 覆盖。
5. 同值排序、并发状态更新或损坏封面导致内容与统计不一致：ID 次排序、单次 RR 快照，只校验本页可见资产；任务 2/3/4 覆盖。

## 任务 1：参数契约和返回 DTO

**新增文件：**
- `src/main/java/org/example/music/common/dto/PageResponse.java`。
- `src/main/java/org/example/music/track/dto/TrackListItemResponse.java`。
- `src/main/java/org/example/music/track/service/TrackListParameters.java`、`TrackListSort.java`。
- `src/main/java/org/example/music/track/exception/TrackListValidationException.java`。
- `src/test/java/org/example/music/track/service/TrackListParametersTest.java`。

**接口：**
- `PageResponse<T>(List<T> items, int page, int size, long totalElements, long totalPages, boolean hasNext)`。
- `TrackListItemResponse(Long id, String title, String artistName, String albumName, Long durationMs, String status, Cover cover)`；嵌套 Cover 为 `String url, String contentType, Long sizeBytes`，不重构已有详情 DTO。
- `TrackListParameters.from(MultiValueMap<String,String> raw)`：返回包含 `int page, int size, TrackListSort sort, TrackStatus requestedStatus` 的已校验 record，未传 status 为 null。
- `TrackListSort`：仅四个字段枚举/受控属性与 asc/desc 方向；提供生成同向 ID 次排序所需信息，外部不能直接传 JPA 属性名或 SQL。

- [ ] 先写 defaults 测试：空参数得到 0、20、id DESC、null；不把显式空值当缺省。
- [ ] 写非法参数表：size 0/101、page -1、数字/溢出、status=published/ALL、重复 page/size/sort/status、q/未知字段、非法字段/方向/关联路径/表达式，全部抛 TrackListValidationException。
- [ ] 固定乘积边界：page=100,size=100 允许；101,100 拒绝；Integer.MAX_VALUE,100 拒绝且不能 int 溢出绕过；page=10000,size=1 允许。
- [ ] 执行 `.\gradlew.bat test --tests "*TrackListParametersTest"`，确认缺失实现或预期行为失败。
- [ ] 实现显式等价入口校验：先检查原始参数名称、出现次数和非空，再做类型/范围、状态、排序转换与 long 乘积。不能先交给 Pageable 默认绑定后才检查重复参数。
- [ ] 实现最小 DTO 和解析 record，使用 OpenAPI Schema 描述字段及 null；重跑上述测试通过。

## 任务 2：授权、投影与批量封面组装

**新增文件：**
- `src/main/java/org/example/music/track/repository/TrackListRepository.java`。
- `src/main/java/org/example/music/track/repository/TrackListRow.java`、`TrackListCoverRow.java`。
- `src/main/java/org/example/music/track/service/TrackListService.java`、`TrackListQueryService.java`。
- `src/test/java/org/example/music/track/service/TrackListServiceTest.java`、`TrackListQueryServiceTest.java`。

**最小修改：** `src/main/java/org/example/music/track/repository/MediaAssetRepository.java`，增加一次批量列表封面投影方法，不改现有方法。

**接口：**
- `TrackListService.list(TrackListParameters parameters, AuthenticatedUserPrincipal actor)` 返回 `PageResponse<TrackListItemResponse>`。先判断 actor.loginRole，拒绝状态时抛现有 AccessDeniedException，不能调用查询 Bean。
- `TrackListQueryService.query(TrackListParameters parameters, TrackStatus effectiveStatus)` 返回同一分页 DTO；effectiveStatus=null 只表示已获授权的全状态查询，不来自字符串 ALL。
- Repository：`List<TrackListRow> findRows(TrackStatus status, int offset, int size, TrackListSort sort)`、`long count(TrackStatus status)`。用 EntityManager/JPA Criteria 动态添加或省略状态谓词和白名单排序，offset/limit 明确设置。
- TrackListRow：id、title、artistName、albumName、durationMs、status、coverAssetId，必要排序列可内部增加但不返回。
- MediaAssetRepository：`List<TrackListCoverRow> findListCovers(Collection<Long> ids)`；投影 id、kind、contentType、sizeBytes、sha256、bucket、objectKey。

- [ ] 先写角色矩阵测试：ADMIN 缺省传 null，USER 缺省传 PUBLISHED，普通用户/USER 登录管理员显式隐藏状态拒绝，且查询 Bean 零调用。
- [ ] 写查询组装测试：空页保留真实 count；total=0 的 totalPages=0；total=Long.MAX_VALUE,size=1 不溢出；非整页向上取整；越界页 hasNext=false。
- [ ] 写封面批量测试：本页重复资产 ID 只提交一个 ID；无 ID 不查询；同资产不同歌曲 URL 分别生成；资产缺失/错误 kind/非法 MIME、size、SHA 或定位信息使整页 500，不能 null 降级。
- [ ] 写外层 DataAccessException/TransactionException → TrackStoreUnavailableException 测试；权限拒绝先于存储异常。
- [ ] 执行 `.\gradlew.bat test --tests "*TrackListServiceTest" --tests "*TrackListQueryServiceTest"`，确认失败后实现以上接口。
- [ ] query 独立 Bean 设置 `@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)`；findRows 与 count 用一致的 effectiveStatus。显式 count，不依赖空页推导。
- [ ] 分页查询只选行投影，不加载 TrackEntity/ID3；批量封面通过去重 Map 组装，不按行调用 findById；page/size 之外不进行内存分页或排序。
- [ ] 计算总页数为 `total / size + (total % size == 0 ? 0 : 1)`，hasNext 用 long 比较请求页与真实页数；重跑测试通过。

## 任务 3：真实查询验证与必要索引

**新增文件：**
- `src/test/java/org/example/music/track/TrackListQueryIntegrationTest.java`。
- `docs/reviews/2026-10-30-track-list-query-plans.md`，只填入实际测得的 EXPLAIN 及结论，不预填结果。
- 如确需索引：`src/main/resources/db/migration/V8__add_track_list_indexes.sql`，若期间版本有新增则顺延。

**环境：** MySQL 8.4 Testcontainers；使用 JdbcTemplate 批量准备至少 12000 条三状态混合记录，制造重复 title/artist_name/created_at，避免以极少行证明索引效率。查询测试走真实 Repository 与事务。

- [ ] 验证四种排序的 asc/desc 和同向 ID 次排序，跨页无同值随机顺序；验证单状态与全状态内容/count 一致，超出结果范围空页仍保留总数。
- [ ] 使用独立连接在分页与 count 之间提交状态/资产更新，证明本次 RR 读快照一致；有界等待并释放连接，避免依赖用例顺序。
- [ ] 测试配置启用 Hibernate 统计及测试专用 SQL StatementInspector。直接调用业务 Service 排除认证查询，再分别访问 HTTP 记录额外认证成本；不把连接/事务控制语句混称逐条数据查询。
- [ ] size=1/20/100、各行独立封面时，业务数据 SQL 恒为 3（内容、count、资产批次）；无封面或空页为 2。断言不存在逐首封面读取、SQL 无 id3_metadata，列表不注入或调用 AudioStorage。
- [ ] 输出单状态/全状态 × 四种排序的真实 EXPLAIN，并补两个方向和接近 offset=10000 的代表性计划。精确 count 单独分析，不将 LIMIT 误称为计数上限。
- [ ] 缺索引导致内容查询全量扫描/排序时，仅增加必要排序或状态组合索引；当前没有 created_at 索引，不能假设其已覆盖。保留 V1—V7、既有索引，记录迁移前后计划与写入成本。
- [ ] 如新增迁移，用真实 MySQL 验证执行、既有数据保留和计划改善；不依赖强制索引或固定成本数字使测试伪通过。
- [ ] 执行 `$env:MINIO_INTEGRATION_TESTS="true"; .\gradlew.bat test --tests "*TrackListQueryIntegrationTest"`，预期实际执行、零失败/零跳过。执行计划资料与最终实际 SQL 一致后进入 HTTP 交付。

## 任务 4：HTTP、真实认证及最终交付

**新增文件：**
- `src/main/java/org/example/music/track/controller/TrackListController.java`。
- `src/main/java/org/example/music/track/web/TrackListErrorAdvice.java`，仅本列表路由错误 no-store。
- `src/test/java/org/example/music/track/controller/TrackListControllerTest.java`。
- `src/test/java/org/example/music/track/TrackListIntegrationTest.java`。
- `docs/api/track/list-tracks.md`、`docs/reviews/2026-10-30-track-list-review.md`。

**最小修改：** GlobalExceptionHandler 仅新增 TrackListValidationException → 400 VALIDATION_FAILED；任务书 T02 状态与 T05 重叠说明。设计/计划可记录用户确认及实际执行，不扩大业务范围。

**接口：** Controller 绑定 `@RequestParam MultiValueMap<String,String>` 与可信 `@AuthenticationPrincipal`，调用参数解析和 Service，返回 `ResponseEntity<PageResponse<TrackListItemResponse>>` 并设置 no-store；没有 Controller 业务权限或 Repository 访问。

- [ ] 先写 WebMvcTest，验证默认及显式参数、完整 JSON、null、空列表、越界统计、200/no-store、全部参数错误、401/403/500/503 的稳定 code 和 application/problem+json。
- [ ] 验证普通用户请求 status=DRAFT 且其他参数非法时先 400；所有已登录角色未知状态 400；未认证仍由认证链先返回 401。
- [ ] 执行 `.\gradlew.bat test --tests "*TrackListControllerTest"` 确认失败后实现 HTTP 契约、定向错误缓存与最小异常映射。不更改现有封面缓存或其他业务路由。
- [ ] OpenAPI 明确 page/size/sort/status、角色默认值、深分页上限、响应列表/分页 Schema、200/400/401/403/500/503。不要将任意 Pageable 属性暴露为可用排序。
- [ ] 真实 MySQL/Redis Testcontainers 和 JwtTokenService 签名 Token 覆盖三状态数据、ADMIN/USER 角色、管理员 USER 登录降权、本人无特权、token version 撤销；核对 items 与 totalElements 不泄漏隐藏状态。
- [ ] 验证仅本页封面损坏影响响应；隐藏状态或其他页损坏不导致当前可见页失败；数据库记录有效而对象缺失时列表仍返回元信息，不访问 MinIO。
- [ ] 中文 API 文档包含四参数与默认值、权限矩阵、字段/null、空结果/越界页、排序规则、深分页、错误与 curl；精确统计成本与跨请求分页移动限制写清。
- [ ] T02 更新为待审核，T05 改为仅新增管理字段/筛选需求时再单独设计；不提前标记代码完成。
- [ ] 执行 `$env:MINIO_INTEGRATION_TESTS="true"; .\gradlew.bat cleanTest check`，预期 BUILD SUCCESSFUL、必要 MySQL/Redis/MinIO 零跳过。统计最终 XML 通过/失败/跳过，含新增列表类与现有存储回归。
- [ ] 运行 git diff --check；复核含 untracked 文件清单、实际迁移/依赖、基线 93685fb、性能证据、默认项和风险，生成首轮 Claude 代码审核材料。未 accepted 前不进入下一接口或自行推送。

## 计划自检与执行约定

规格第 3/5/6 节由任务 1/4 覆盖，第 4/7/8 节由任务 2/3/4 覆盖，第 9 节由任务 3 覆盖，第 10—12 节由任务 4 交付。五项重点复核均有归属测试。

未新增专用限流、缓存或搜索。索引字段以实际 EXPLAIN 为依据；新增迁移与执行计划将列入同一单接口审核范围。总数 Long、参数类型与方法签名已一致，未定义的实现细节按现有项目惯例处理。

计划已由用户确认。执行方式为 Codex 实现、Claude 独立审核；当前实现和验证完成，Claude 第 1 轮代码审核 accepted。

## 执行中的实现调整

真实 V7 EXPLAIN 显示 createdAt 无索引，深分页直接投影可能全表排序。内容查询改为受控原生 SQL 的派生表 ID 分页加本页回表，仍是一条业务 SQL、不读 Entity/ID3；字段和方向只来自已校验白名单的常量映射，值使用参数绑定。count 保留 Criteria 查询。新增 V8 四个必要辅助索引，执行计划测试记录前后计划及 EXPLAIN ANALYZE；不强制索引。

参数首个测试因类型缺失失败，实施后通过；其余测试随实现补齐，原始步骤清单不代表全部测试均按红灯优先顺序执行。最终数量及完整检查结果以审核材料记录为准。

## 最终验证结果

`$env:MINIO_INTEGRATION_TESTS="true"; .\gradlew.bat cleanTest check`：BUILD SUCCESSFUL（6m 12s），417 通过 / 0 失败 / 0 跳过，列表新增 89 项。V7→V8 真实迁移保留 12000 行原数据；RR 并发读快照、SQL 数量、真实 JWT 权限与既有 MinIO 回归通过。实际性能数据见 docs/reviews/2026-10-30-track-list-query-plans.md。

T02 已更新已完成；用户授权提交推送，非阻断项另行处理，不重跑测试。首轮代码审核请求及最终结论：docs/reviews/2026-10-30-track-list-review.md。
