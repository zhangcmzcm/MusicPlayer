# T04 歌曲模糊搜索实施计划

> 执行者遵循 AGENTS.md，由 Codex 逐任务实施，Claude Code CLI 负责独立只读实现审核。复选框按实际执行结果更新，用户已批准本会话逐任务实施。

**目标** 扩展 `GET /api/v1/tracks` 的 q 参数，实现当前登录角色可见范围内的多词跨字段包含搜索、精确分页和成本控制。

**架构** 在现有列表参数中保存规范化词列表，Repository 将同一搜索谓词用于分页前过滤和 COUNT。查询 Service 使用现有 REPEATABLE_READ 只读事务，外层 Service 在权限检查之后、歌曲事务之前调用 Redis 原子限流。搜索内容和 COUNT 各使用配置化 MySQL 执行预算。

**技术栈** Java 21、Spring Boot 4.1.1、Spring MVC/Validation/Security、JPA、MySQL 8.4、Redis、JUnit 5、MockMvc、Testcontainers，依赖取自当前 build.gradle。

**设计依据** [已确认的书面设计](../specs/2026-11-13-track-search-design.md)，用户于 2026-11-13 批准。计划代码基线为 `main @ 8778308`，其业务代码基线为 `1d13502`，项目目录为 `D:\code\Java-code\projects\music`。

## 全局约束

- 唯一接口为 `GET /api/v1/tracks`，参数集合为 q、page、size、sort、status，歌曲 ID 为 Long，身份使用可信主体的 Integer userId 与 loginRole。
- USER 仅可查询 PUBLISHED，显式筛选 DRAFT 或 ARCHIVED 为 403。ADMIN 默认全部状态，管理员以 USER 登录时适用 USER 规则。
- q 原始解码值最多 100 个 Unicode 码点，Unicode White_Space 拆分后为 1 至 5 词，重复词计入词数，每词至少 1 个码点。
- 每词在 title、artist_name、album_name 内 OR 匹配，各词之间 AND，匹配与排序使用 utf8mb4_0900_ai_ci。
- LIKE 显式使用 `!` 转义，用户的 `!`、`%`、`_` 和反斜杠按字面搜索，值使用绑定参数。
- page 默认 0，size 默认 20 且范围 1 至 100，使用 long 检查 `page × size <= 10000`。默认 id,desc，非 ID 排序追加同方向 ID。
- 缺省 q 使用普通列表路径，空白 q、未知或重复参数统一 400 VALIDATION_FAILED。
- 每账户默认 60 秒 60 次，首请求建立窗口，USER 与 ADMIN 共享额度，数据库失败请求计入额度。
- 搜索内容和 COUNT 默认各 2000 毫秒，使用最外层 SELECT 的 MAX_EXECUTION_TIME。
- 用户已接受首版预计 1 万首以内的 LIKE 扫描取舍，实际执行计划和 COUNT 成本须分别记录。
- 数据结构采用现有 V1 至 V10 的表和索引，响应使用现有分页与歌曲 DTO，封面元信息批量读取。
- 成功为 200，业务错误使用 ProblemDetail、稳定 code 与 urn:problem:music:*，响应使用 no-store，日志保留 traceId。
- 中文 API、计划、审核材料和提交说明遵循项目约定，完整验证设置 MINIO_INTEGRATION_TESTS=true。

## 审查重点

1. Unicode 空白包含 NBSP 和全角空格，补充平面字符按码点计数，由任务 1 的参数测试固定。
2. 通配符、显式转义符、反斜杠与 SQL 片段同时出现时保持字面搜索，由任务 2 的真实 MySQL 测试固定。
3. 搜索后的内容、总数及越界页使用相同权限谓词和读快照，由任务 2 的 HTTP 集成与并发归档测试固定。
4. Redis 即将到期、计数饱和、配置额度降低及异常状态分别判定，由任务 3 的真实 Redis 与脚本结果测试固定。
5. 内容或 COUNT 超时后返回正确 503，后续请求可继续使用连接与事务，由任务 2 的真实 MySQL 超时测试固定。

## 文件与接口结构

表内路径相对项目根目录。测试辅助方法限于所属测试类，业务组件使用下列明确接口。

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 修改 | src/main/java/org/example/music/track/service/TrackListParameters.java | q 解析、不可变词列表及既有构造入口兼容 |
| 修改 | src/main/java/org/example/music/track/repository/TrackListRepository.java | 搜索 SQL、绑定及同条件 COUNT |
| 修改 | src/main/java/org/example/music/track/service/TrackListQueryService.java | 搜索仓储调用、配置化预算及读快照 |
| 修改 | src/main/java/org/example/music/track/service/TrackListService.java | 状态权限之后消耗额度 |
| 新建 | src/main/java/org/example/music/track/config/TrackSearchProperties.java | 搜索正整数配置 record |
| 新建 | src/main/java/org/example/music/track/config/TrackSearchConfig.java | 注册配置类型 |
| 新建 | src/main/java/org/example/music/track/service/TrackSearchRateLimiter.java | 单账户原子固定窗口 |
| 新建 | src/main/java/org/example/music/track/exception/TrackSearchRateLimitedException.java | 429 及重试秒数 |
| 新建 | src/main/java/org/example/music/track/exception/TrackSearchRateLimitUnavailableException.java | 限流依赖 503 |
| 修改 | src/main/java/org/example/music/track/controller/TrackListController.java | q、429、503 的 OpenAPI |
| 修改 | src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java | 两个搜索限流异常映射 |
| 修改 | src/main/resources/application.properties、.env.example | 两项搜索环境变量与默认值 |
| 修改 | src/test/java/org/example/music/track/service/TrackListParametersTest.java | Unicode、长度及词数边界 |
| 修改 | src/test/java/org/example/music/track/service/TrackListQueryServiceTest.java | 搜索调用及现有 DTO 计算 |
| 修改 | src/test/java/org/example/music/track/service/TrackListServiceTest.java | 权限、额度及查询顺序 |
| 修改 | src/test/java/org/example/music/track/controller/TrackListControllerTest.java | q 转发、参数和错误契约 |
| 修改 | src/test/java/org/example/music/track/TrackListIntegrationTest.java | 真实角色、搜索、计数、资产及快照 |
| 新建 | src/test/java/org/example/music/track/config/TrackSearchConfigTest.java | 配置默认值、覆盖及启动失败 |
| 新建 | src/test/java/org/example/music/track/service/TrackSearchRateLimiterTest.java | 脚本返回值和故障转换 |
| 新建 | src/test/java/org/example/music/track/service/TrackSearchRateLimiterIntegrationTest.java | 真实 Redis 窗口、并发及损坏状态 |
| 新建 | src/test/java/org/example/music/track/TrackSearchTimeoutIntegrationTest.java | 两条搜索 SQL 的真实超时 |
| 新建 | src/test/java/org/example/music/track/TrackSearchQueryPlanIntegrationTest.java | 1 万首数据及执行计划产物 |
| 修改 | docs/api/track/list-tracks.md | 当前唯一接口的完整中文契约 |
| 新建 | docs/reviews/2026-11-13-track-search-query-plans.md | 实际查询计划证据 |
| 新建 | docs/reviews/2026-11-13-track-search-review-request.md | 单接口送审材料 |
| 新建 | docs/reviews/2026-11-13-track-search-review.md | 审核原文、来源与逐项处置 |
| 更新 | 本设计与计划、docs/tasks/backend-roadmap.md、PROJECT_STATE.md | 实际进度与 accepted 后的完成登记 |

## 任务 1 关键词参数与 HTTP 绑定契约

**文件** 修改 TrackListParameters、TrackListParametersTest、TrackListControllerTest。

**输入接口** 现有 `TrackListParameters.from(MultiValueMap<String, String> raw)`、TrackListSort、TrackListValidationException。

**输出接口** record 末尾增加 `List<String> searchTerms`，提供 `boolean hasSearch()`。既有四参数构造器委派至五参数构造器并使用空词列表，from 返回规范化结果。测试类添加 `private TrackListParameters parseQ(String rawQ)`，仅负责构造单值参数 map 并调用 from。

- [x] **步骤 1** 新增 `should_split_unicode_whitespace_and_keep_cross_field_terms` 和 `should_count_code_points_before_normalization`。核心断言如下，补充测试辅助方法 parseQ。

```java
assertEquals(List.of("周杰伦", "晴天"), parseQ("\u00a0周杰伦\u3000\t晴天\n").searchTerms());
assertTrue(parseQ("晴").hasSearch());
assertEquals(List.of("😀".repeat(100)), parseQ("😀".repeat(100)).searchTerms());
assertThrows(TrackListValidationException.class, () -> parseQ("😀".repeat(101)));
assertThrows(TrackListValidationException.class, () -> parseQ(" ".repeat(100) + "晴"));
```

- [x] **步骤 2** 执行 `.\gradlew.bat test --tests "org.example.music.track.service.TrackListParametersTest" --console=plain`，记录词列表接口缺失或新行为断言失败。
- [x] **步骤 3** 实现 record 及 private 规范化方法。先检查原始码点数，再以启用 UNICODE_CHARACTER_CLASS 的连续空白正则拆分并过滤边界空词。构造器防御性复制词列表，约束归一化单词及总长度，空列表表示缺省 q。
- [x] **步骤 4** 添加 `should_enforce_five_occurrences_including_duplicate_terms`、`should_reject_blank_or_repeated_q`、`should_keep_search_terms_immutable`，断言五词合法、六词含重复也失败、空白失败、返回列表不可修改。将既有 `q|hello` 非法参数改为真实未知参数，q 加入重复参数矩阵。
- [x] **步骤 5** Controller 测试将既有 `q|song` 非法项替换为真实未知参数，新增 `should_forward_normalized_search_terms`，用参数捕获器断言 q=周杰伦 晴天 转交两个词。保持其余错误优先级断言。
- [x] **步骤 6** 执行 `.\gradlew.bat test --tests "org.example.music.track.service.TrackListParametersTest" --tests "org.example.music.track.controller.TrackListControllerTest" --console=plain`，核对 XML 中本组全部通过后提交三个文件，说明为 `feat: 定义歌曲多词搜索参数契约`。

## 任务 2 搜索查询、执行预算与一致性

**文件** 修改 Repository、QueryService、对应单元测试和 TrackListIntegrationTest。新增 TrackSearchProperties、TrackSearchConfig、TrackSearchConfigTest、TrackSearchTimeoutIntegrationTest、TrackSearchQueryPlanIntegrationTest，更新 application.properties 与 .env.example。

**输入接口** 任务 1 的 searchTerms、hasSearch，现有 TrackListSort、TrackListRow、MediaAssetRepository 和 TrackStoreUnavailableException。

**输出接口** TrackSearchProperties 位于 track.config，record 为 `TrackSearchProperties(int rateLimitPerMinute, int queryTimeoutMs)`。Repository 增加以下重载，现有普通列表入口继续供缺省 q 和原查询计划测试调用。

```java
public List<TrackListRow> findRows(TrackStatus status, int offset, int size,
        TrackListSort sort, List<String> searchTerms, int timeoutMs);
public long count(TrackStatus status, List<String> searchTerms, int timeoutMs);
public static String contentSql(TrackListSort sort, boolean filtered, int termCount, int timeoutMs);
public static String countSql(boolean filtered, int termCount, int timeoutMs);
```

QueryService 构造器为 `TrackListQueryService(TrackListRepository tracks, MediaAssetRepository assets, TrackSearchProperties properties)`，public query 签名沿用现有入口。

- [ ] **步骤 1** 新增搜索调用测试 `should_pass_identical_terms_and_budget_to_content_and_count`，词列表为 List.of("周杰伦","晴天")、预算为 2000。核验两条查询收到同一列表、PUBLISHED、offset=0、size=20，现有封面批次与 Long 分页断言继续执行。新增配置测试断言默认 60/2000、配置覆盖 30/500，以及 0、负数、整数溢出均导致启动失败。
- [ ] **步骤 2** 执行 `.\gradlew.bat test --tests "org.example.music.track.service.TrackListQueryServiceTest" --tests "org.example.music.track.config.TrackSearchConfigTest" --console=plain`，记录缺失接口失败。
- [ ] **步骤 3** 实现配置 record 的 @ConfigurationProperties、@Validated、@Min(1) 与默认值，并由 TrackSearchConfig 注册。在 application.properties 添加设计中的两个环境变量默认表达式，在 .env.example 添加 60 和 2000 两项示例。
- [ ] **步骤 4** 实现搜索重载。内容 SQL 在候选 ID 子查询的分页前生成同一状态和多词谓词，COUNT 使用相同谓词构造逻辑，参数依次为 q0 至 q4。private 模式生成方法按 !、%、_ 的顺序转义。两条搜索 SQL 的最外层 SELECT 加配置化 MAX_EXECUTION_TIME，返回行复用现有投影转换。
- [ ] **步骤 5** QueryService 按 hasSearch 选择相应 Repository 入口，搜索路径传入相同词列表与 queryTimeoutMs。保留现有 readOnly、REPEATABLE_READ、批量封面和 DTO 组装。更新手动构造 QueryService 的单元测试依赖。
- [ ] **步骤 6** 在真实列表集成测试新增 `should_match_every_term_across_fields_and_count_only_visible_tracks`。构造标题/艺术家分别命中、专辑命中、只命中一个词、空专辑及三状态数据，断言 USER、ADMIN、管理员 USER 登录、状态筛选的精确 ID 与计数。新增 `should_search_literal_pattern_characters`，将含 `!%_\`、大小写/重音及 SQL 片段的数据与干扰行区分，断言精确命中集合。
- [ ] **步骤 7** 新增 `should_keep_search_content_and_count_on_one_snapshot`，复用当前 Repository spy 与独立 JDBC 连接，在搜索 count 前归档一首已命中歌曲。当前响应内容和总数使用原快照，下一次请求反映归档。覆盖搜索空页、最深合法页、八种排序、共享封面批次、本页损坏资产及字段投影。新增断言示例如下，业务有封面三条查询加认证查询合计四条。

```java
assertEquals(4, TrackListIntegrationTest.SqlCapture.statements().size());
assertEquals(List.of("周杰伦", "晴天"), parameters.searchTerms());
```

- [ ] **步骤 8** 新建真实超时测试，复用当前 MySQL 8.4、Redis、MockMvc、JWT 和 StatementInspector 注册方式。测试属性设预算 200 毫秒、连接池上限 1，种入必命中歌曲。内部 Inspector 用 ThreadLocal 区分 CONTENT、COUNT，分别只对目标搜索 SQL 的 WHERE 追加固定 `SLEEP(10)=0`，保留原 MAX_EXECUTION_TIME 和绑定值。两个测试名为 `should_return_503_when_search_content_query_exceeds_mysql_budget`、`should_return_503_when_search_count_query_exceeds_mysql_budget`。

```java
assertEquals(503, result.getResponse().getStatus());
assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
assertEquals(3024, sqlException.getErrorCode());
assertEquals("HY000", sqlException.getSQLState());
```

  同时断言 ProblemDetail code=TRACK_STORE_UNAVAILABLE。sqlException 从 MvcResult 的已解析异常原因链取得。finally 清理 Inspector 后再次搜索成功，测试整体设置 20 秒兜底。MySQL 文档给出嵌入 WHERE 的 SLEEP 因预算中断返回 3024 的行为依据。[MySQL SLEEP](https://dev.mysql.com/doc/refman/8.4/en/miscellaneous-functions.html#function_sleep)

- [ ] **步骤 9** 新建搜索执行计划测试，参照既有 TrackListQueryIntegrationTest 使用独立 MySQL、Flyway、批量 JDBC 和 NamedParameterJdbcTemplate，直接调用上述生产 SQL 工厂并绑定样本词。i 从 0 至 9999，ID 为 i+1，标题为“晴天现场 ”加 i%101，艺术家在偶数 i 为“周杰伦”、奇数 i 为“其他歌手”，专辑在 i%5=0 时为 null、其余为“十一月的萧邦”。状态按 i%3 对应 DRAFT、PUBLISHED、ARCHIVED，时间使用固定 UTC 基准加 i%31 秒。

  全部状态搜索“晴”匹配 10000 首，PUBLISHED 匹配 3333 首。搜索“周杰伦 晴天”及“晴 天 现 场 周”分别匹配全部状态 5000 首、PUBLISHED 1666 首。“不存在词XYZ”匹配 0 首。首屏覆盖四字段双向排序，高频词和五词组合补充 size=100、offset=10000。内容和 COUNT 分别记录 EXPLAIN 与 EXPLAIN ANALYZE 到 `build/reports/track-search-query-plans.md`，预期 ID、排序与页切片由上述固定样本独立计算，并记录实际扫描行数和耗时。
- [ ] **步骤 10** 执行 `.\gradlew.bat test --tests "org.example.music.track.service.TrackListQueryServiceTest" --tests "org.example.music.track.config.TrackSearchConfigTest" --tests "org.example.music.track.TrackList*" --tests "org.example.music.track.TrackSearchTimeoutIntegrationTest" --tests "org.example.music.track.TrackSearchQueryPlanIntegrationTest" --console=plain`，汇总 XML，必要容器测试全部实跑后提交任务 2 文件，说明为 `feat: 实现歌曲多词搜索与查询执行预算`。

## 任务 3 搜索额度、HTTP 故障与调用顺序

**文件** 新增限流组件、两个异常、两个限流测试类，修改 TrackListService、GlobalExceptionHandler、TrackListController 及相关现有测试。

**输入接口** 任务 1 的 hasSearch、任务 2 的 TrackSearchProperties 和查询 Service，既有 StringRedisTemplate 与可信 Integer userId。

**输出接口** `TrackSearchRateLimiter(StringRedisTemplate redis, TrackSearchProperties properties)`、`void consumeOrThrow(Integer userId)`。异常构造器为 `TrackSearchRateLimitedException(long retryAfterSeconds)`、`TrackSearchRateLimitUnavailableException(Throwable cause)`，前者提供 `long getRetryAfterSeconds()`。列表 Service 构造器增加 TrackSearchRateLimiter 参数。

- [ ] **步骤 1** 编写限流单测，参照 PasswordChangeRateLimiterTest，用脚本结果 `{0,0}` 断言放行、`{1,1}` 断言重试 1 秒、未知状态/null/非法列表断言依赖故障。执行 `.\gradlew.bat test --tests "org.example.music.track.service.TrackSearchRateLimiterTest" --console=plain`，记录类型缺失失败。
- [ ] **步骤 2** 实现原子 Lua 和 Java 结果校验。组件内使用包级常量 `static final RedisScript<List> CONSUME`，由 consumeOrThrow 执行，供同包集成测试读取同一脚本。key 为 `music:track-search:user:<userId>`，窗口 PX=60000。计数为 1 至 Integer.MAX_VALUE 的规范正整数字符串，PTTL 允许 0 至 60000。缺失或 -2 建新窗，-1/越界 TTL/错误类型或计数返回故障。计数达到或超过当前额度时返回限流并保留计数与到期时间，Retry-After 为 1 至 60 秒。脚本返回 `{0,0}` 表示允许、`{1,seconds}` 表示限流、`{2,0}` 表示依赖状态故障，其他结构或数值统一转换依赖故障。
- [ ] **步骤 3** 新建真实 Redis 集成测试，参考现有 LettuceConnectionFactory 生命周期，使用独立 Redis 7.4 容器及真实 StringRedisTemplate。`should_admit_exactly_sixty_concurrent_searches` 同时提交 80 次，断言 60 放行、20 限流、最终计数 60。`should_keep_saturated_count_and_window_expiry` 使用 PEXPIRETIME 比较拒绝前后到期时间，删除到期 key 后断言重新计数 1。

```java
assertEquals(60, admitted);
assertEquals(20, rejected);
assertEquals("60", redis.opsForValue().get(key));
assertEquals(expiryBefore, expiryAfter);
```

- [ ] **步骤 4** 真实 Redis 覆盖非法类型、0/负数/前导零/非数字/超出 int 的计数、永久 key、TTL 大于 60000、不同账户隔离和配置从 60 降至 30 时既存计数 40 返回限流。过长 TTL 设为 120000 毫秒。瞬时边界测试读取 CONSUME 的原脚本，在测试包装脚本内通过局部 redis.call 包装固定 PTTL 为 0 或 -2，其余命令委派真实 Redis。断言 0 时饱和计数保持 60、Retry-After=1，-2 时重新建窗计数为 1。真实 Redis 同时覆盖自然到期与短 TTL 行为。
- [ ] **步骤 5** TrackListService 在状态权限检查后、数据库 try 边界之前按 hasSearch 调用 consumeOrThrow(actor.userId())。更新单元测试手动构造器，添加 `should_consume_search_quota_before_query` 的 InOrder 断言，普通列表、非法输入和隐藏状态权限失败保持相应的零额度调用断言。数据库失败后的已消耗额度由真实 HTTP 测试固定。
- [ ] **步骤 6** GlobalExceptionHandler 增加 429 TRACK_SEARCH_RATE_LIMITED 及 Retry-After，503 TRACK_SEARCH_RATE_LIMIT_UNAVAILABLE 和脱敏故障日志。Controller OpenAPI 增加 q 的 100 码点/5 词规范、429、限流 503 和重试响应头。WebMvcTest 对 429、两类 503、no-store、ProblemDetail type/code 作精确断言。
- [ ] **步骤 7** 在 TrackListIntegrationTest 接入真实 Redis 额度断言，使用同一管理员账户的 USER 和 ADMIN Token 验证共享 key。认证、q 校验、隐藏状态权限失败结束于计数之前，数据库超时或故障后计数增加。饱和请求返回 429 且业务 SQL 计数为 0，普通列表完成现有回归。
- [ ] **步骤 8** 执行 `.\gradlew.bat test --tests "org.example.music.track.service.TrackSearchRateLimiterTest" --tests "org.example.music.track.service.TrackSearchRateLimiterIntegrationTest" --tests "org.example.music.track.service.TrackListServiceTest" --tests "org.example.music.track.controller.TrackListControllerTest" --tests "org.example.music.track.TrackListIntegrationTest" --tests "org.example.music.track.TrackSearchTimeoutIntegrationTest" --console=plain`。全部通过、必要容器测试 0 跳过后提交任务 3 文件，说明为 `feat: 增加歌曲搜索账户限流与HTTP契约`。

## 任务 4 API、完整验证与独立审核闭环

**文件** 更新列表 API、本设计和本计划，生成执行计划、审核请求与审核记录。accepted 后更新任务书及 PROJECT_STATE.md。

**输入接口** 任务 1 至 3 的真实 HTTP 入口、测试和构建产物，以及 AGENTS.md、CLAUDE.md 的审核协议。

**输出结果** 完整中文 API、实际测试数量、可追溯的查询计划与 CLI 审核证据、有效 accepted 或按协议明确交付的 escalated/真实环境阻塞状态。

- [ ] **步骤 1** 更新 docs/api/track/list-tracks.md，将 q 纳入查询参数集合，写明规范化、特殊符号、AND/OR、状态权限、精确分页、额度、Retry-After、两项配置、查询预算单位和已确认扫描取舍。核对 JSON 与 curl 可执行语法，OpenAPI 描述逐项对应实现。
- [ ] **步骤 2** 核对任务 2 的实际查询计划产物，将环境、数据分布、SQL、扫描行数和耗时整理到 `docs/reviews/2026-11-13-track-search-query-plans.md`。运行 `git diff --check`，核对已跟踪、未跟踪及本轮已有文档改动的完整范围。
- [ ] **步骤 3** 执行以下完整验证并保留退出码和完整日志。

```powershell
New-Item -ItemType Directory -Force -Path 'build/reports' | Out-Null
$env:MINIO_INTEGRATION_TESTS = 'true'
.\gradlew.bat cleanTest check --console=plain > build/track-search-full-check.log 2>&1
$searchCheckExitCode = $LASTEXITCODE
```

  结果须为 BUILD SUCCESSFUL。读取 build/test-results/test/TEST-*.xml，汇总 tests、failures、errors、skipped，通过数为 tests 减去其余三项。逐项列出跳过类/方法及原因，核实 MySQL、Redis、MinIO 实跑。完整 check 的进程持续监控至退出，运行故障在授权范围内诊断恢复。

- [ ] **步骤 4** 将本次 XML 复制至 `build/reports/track-search-self-check-xml` 保留自验证据，按 AGENTS.md 第 14 节填写 review-request，写明唯一接口、需求权限、声称行为、全量文件、基线、API、现有结构、真实数量、curl、扫描取舍和首次反馈“不涉及”。对实施及送审材料作范围明确的中文提交，取得实际送审 commit。
- [ ] **步骤 5** 从项目根目录调用本机 Claude Code CLI。每轮输出独立的 JSON 和 stderr，提示先读 AGENTS.md、CLAUDE.md、审核请求及历史材料，先范围检查，再带 MINIO_INTEGRATION_TESTS=true 独立完整 cleanTest check 和逐项审查。命令形态如下，变量分别设为当前轮次路径和完整中文审核提示。

```powershell
$searchReviewResultPath = 'build/reports/track-search-review-round1.json'
$searchReviewErrorPath = 'build/reports/track-search-review-round1.stderr.log'
$searchReviewPrompt = '请先读取AGENTS.md、CLAUDE.md、docs/reviews/2026-11-13-track-search-review-request.md及历史审核意见，核对Git范围和材料中的实际送审提交。当前唯一接口为GET /api/v1/tracks的q搜索扩展，用户已批准首版1万首以内LIKE扫描取舍。先范围检查，再设置MINIO_INTEGRATION_TESTS=true独立执行完整cleanTest check，按XML汇总通过、失败、错误和跳过数量，再逐项审查并输出标准中文审核报告。职责为独立只读审核，Bash仅用于读取、Git只读及Gradle/Docker验证，遵守三轮上限及反馈复核规则。'
claude --print $searchReviewPrompt `
  --tools 'Read,Glob,Grep,Bash' `
  --allowedTools 'Read' 'Glob' 'Grep' 'Bash' `
  --disallowedTools 'Write' 'Edit' 'NotebookEdit' `
  --disable-slash-commands `
  --output-format json `
  2> $searchReviewErrorPath | Out-File -LiteralPath $searchReviewResultPath -Encoding utf8
$searchReviewExitCode = $LASTEXITCODE
```

  首轮路径为 `build/reports/track-search-review-round1.json` 与 `build/reports/track-search-review-round1.stderr.log`。Bash 范围限定读取、Git 只读及 Gradle/Docker 验证。CLI 使用本机默认模型配置，持续监控至进程退出，核对退出码、JSON、is_error、result、permission_denials 和真实验证证据。运行故障先恢复，宿主授权按工具流程处理。

- [ ] **步骤 6** 将 result 原文保存到 review.md，登记 session_id、实际 modelUsage、轮次、基线和验证结果。needs_revision 按原编号核实问题、分级、方向和证据，记录处置并完成确认修复的验证，再以 `--resume <session_id>` 接续原会话。每轮独立留存 JSON、stderr 和原文，最多 3 轮，第三轮核对既有阻断项。escalated 时提交双方依据与人类待决事项。
- [ ] **步骤 7** 有效 accepted 后登记所有非阻断项处置，核对代码与送审范围，将 T04 移入任务书完成区，更新 PROJECT_STATE.md、设计和计划，执行文档差异及链接检查并作中文收尾提交。最终汇报实际提交、API、测试通过/失败/错误/跳过数量、审核来源及剩余环境事项。

## 计划自查与执行交接

计划自查逐项对照设计的接口、权限、参数、匹配、DTO、事务、限流、超时、成本、文档和审核要求，并核对所有方法名、构造器、配置属性及任务间输入输出。上述五项审查重点均有对应任务测试。

本计划的阶段验收命令服务于各任务定位，接口完成依据为任务 4 的完整 check 和有效独立审核结论。

建议在当前会话逐任务实施，三个实现任务共享参数和仓储调用接口，便于连续核对普通列表回归与真实依赖行为。计划由用户审阅后确认执行方式，再按复选框推进。

任务 1 实际验证为 BUILD SUCCESSFUL，2 个套件、63 通过、0 失败、0 错误、0 跳过。先执行测试记录了新增接口缺失的 7 个编译错误，再完成实现和 HTTP 契约验证。
