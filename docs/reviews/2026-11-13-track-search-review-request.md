# T04 歌曲模糊搜索独立实现审核请求

本轮为第1/3轮独立实现审核，当前实施与完整自验已完成，审核结论待Claude输出。

## 本次接口

- `GET /api/v1/tracks`，扩展可选查询参数q，任务书编号T04。
- 设计及代码审核基线为 `main @ 877830862dfb0c76cf5882d9db6564435dbbc031`，该提交业务代码与 `1d13502` 一致。
- 已确认设计及计划提交cd4cb6f，参数契约0b0f359，查询及预算324eedb，额度与HTTP契约07cffad。
- 审核范围为上述基线到CLI提示指定的完整送审HEAD，包含本请求及本轮文档提交。

## 需求与权限

需要有效Bearer JWT，身份来自可信AuthenticatedUserPrincipal。USER及管理员USER登录只查询PUBLISHED，显式筛选DRAFT或ARCHIVED为403。ADMIN默认全部状态，可按单个status筛选。内容、总数和页数采用同一授权搜索条件。

q按Unicode White_Space拆词，所有词须在同首歌曲的标题、艺术家、专辑中命中，可跨字段组合，支持中文单字。用户已确认扩展原列表入口、继承现有排序及权限、首版预计1万首以内，并明确接受参数化LIKE包含查询可能扫描全部候选歌曲的工程取舍。

## 声称实现的行为

- q原始URL解码值最多100个Unicode码点，首尾空白计入长度，规范化后1至5词，重复词计入词数。空值、纯空白、未知或重复参数统一400 VALIDATION_FAILED。
- 每词三字段OR，各词AND。使用绑定值和显式ESCAPE '!'，%、_、反斜杠、!按字面搜索，匹配使用现有utf8mb4_0900_ai_ci。
- page默认0、size默认20且1至100、page×size不超过10000。默认id,desc，其他排序追加同方向ID。
- 缺省q走普通列表入口，响应复用现有PageResponse和TrackListItemResponse，空结果及越界页保留精确统计。
- REPEATABLE_READ短只读事务内完成内容、COUNT、本页封面批次，业务SQL最多三条，认证SQL另计。
- 搜索内容和COUNT最外层SELECT分别应用MAX_EXECUTION_TIME，默认各2000毫秒，真实3024超时转换503 TRACK_STORE_UNAVAILABLE。
- 通过认证、全部参数校验及状态权限后，用真实Redis原子消费账户额度，再进入歌曲事务。默认首请求起60秒60次，两种登录角色共享账户key，数据库故障计入已消耗额度。
- 饱和计数及TTL保持，PTTL 0返回至少1秒重试，缺失/-2建窗，异常类型、计数、TTL及脚本结果返回503 TRACK_SEARCH_RATE_LIMIT_UNAVAILABLE。
- 超额429 TRACK_SEARCH_RATE_LIMITED携带Retry-After，搜索错误复用列表no-store响应处理与稳定URN。
- 配置以TRACK_SEARCH_RATE_LIMIT_PER_MINUTE和TRACK_SEARCH_QUERY_TIMEOUT_MS外部化，正整数启动校验，示例默认60和2000。
- 原列表构造器、普通仓储入口、排序、分页、封面与角色测试继续执行，两个旧q拒绝案例按新契约改为未知参数案例并补充搜索断言。

## 数据库与API材料

数据库迁移为无，持久化使用现有tracks、media_assets及V1至V10结构。依赖来自现有build.gradle。

- 设计为docs/superpowers/specs/2026-11-13-track-search-design.md。
- 计划为docs/superpowers/plans/2026-11-13-track-search-plan.md。
- API为docs/api/track/list-tracks.md，OpenAPI位于TrackListController。
- 查询计划为docs/reviews/2026-11-13-track-search-query-plans.md，包含全部88组汇总和8组完整实例，原始产物build/reports/track-search-query-plans.md由真实测试生成。

## 实际测试

完整自验命令如下，退出码0，BUILD SUCCESSFUL，耗时21分42秒。

```powershell
$env:MINIO_INTEGRATION_TESTS = 'true'
.\gradlew.bat cleanTest check --console=plain
```

按本次XML汇总199套件、2376通过、0失败、0错误、0跳过。MySQL、Redis、MinIO实际执行。完整输出为build/track-search-full-check.log，自验XML已保存到build/reports/track-search-self-check-xml，供独立cleanTest后核对。

专项测试按各次运行分别记录，数量有交集。

- 参数与Controller组2套件，63通过、0失败、0错误、0跳过。
- 查询、配置、真实HTTP及真实超时组4套件，64通过、0失败、0错误、0跳过，1分23秒。
- 原列表及搜索查询计划组2套件，2通过、0失败、0错误、0跳过，最终复跑1分15秒，包含英文正命中、返回100行及最深合法空页。
- 限流与HTTP联合组6套件，114通过、0失败、0错误、0跳过，1分24秒。80次并发恰好60次放行，验证窗口及故障状态。

专项日志为build/track-search-query-check.log、build/track-search-plans-check.log、build/track-search-limiter-check.log。查询与限流组XML分别归档在build/reports/track-search-query-check-xml和track-search-limiter-check-xml。

真实MySQL超时测试通过测试专用StatementInspector在生产查询WHERE中加入SLEEP(10)=0，保留原最外层预算和绑定值，分别验证内容/COUNT的MySQL 3024、HY000、HTTP503、已消耗额度及后续连接恢复。瞬时PTTL边界用真实Redis执行同一生产Lua，仅在测试包装中固定PTTL返回，其余命令委派真实Redis。

## curl示例

ACCESS_TOKEN取自已有登录流程。

```sh
curl --get 'http://localhost:8080/api/v1/tracks' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'q=周杰伦 晴天' \
  --data-urlencode 'page=0' --data-urlencode 'size=20' --data-urlencode 'sort=id,desc'
```

## 默认项与风险

关键词上限、词数、单字匹配、排序、账户额度和SQL预算均已获用户确认。10000首为首版验证规模。精确COUNT和包含搜索随候选数据规模增长，查询计划如实保留了全表/索引扫描与实际耗时。配置预算以单条SQL计时，HTTP还包括认证、Redis、连接获取和封面元信息。

## 审核反馈复核与处置

首次提交，不涉及。当前无已登记的Claude阻断项。后续沿用原问题编号及同一session，逐项核实问题、分级、方向并按AGENTS.md留存处置记录。

## 审核执行与收尾

请先核对范围，再独立设置MINIO_INTEGRATION_TESTS=true执行完整cleanTest check，并按XML记录通过、失败、错误、跳过。按CLAUDE.md逐项审查后输出标准中文Verdict。角色为独立只读审核，工具为Read、Glob、Grep、Bash，Bash限读取、Git只读及Gradle/Docker验证。

实际审核原文和Codex处置将写入docs/reviews/2026-11-13-track-search-review.md。有效accepted后，按AGENTS.md更新任务书、PROJECT_STATE及设计/计划的完成记录。

## 全部变更文件

开始实施时已有本会话设计确认状态更新和未跟踪计划，均已纳入cd4cb6f及当前范围。以下包含基线后所有实施和文档文件，以及送审前未跟踪材料。
- .env.example
- docs/api/track/list-tracks.md
- docs/reviews/2026-11-13-track-search-query-plans.md
- docs/reviews/2026-11-13-track-search-review-request.md
- docs/superpowers/plans/2026-11-13-track-search-plan.md
- docs/superpowers/specs/2026-11-13-track-search-design.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/main/java/org/example/music/track/config/TrackSearchConfig.java
- src/main/java/org/example/music/track/config/TrackSearchProperties.java
- src/main/java/org/example/music/track/controller/TrackListController.java
- src/main/java/org/example/music/track/exception/TrackSearchRateLimitedException.java
- src/main/java/org/example/music/track/exception/TrackSearchRateLimitUnavailableException.java
- src/main/java/org/example/music/track/repository/TrackListRepository.java
- src/main/java/org/example/music/track/service/TrackListParameters.java
- src/main/java/org/example/music/track/service/TrackListQueryService.java
- src/main/java/org/example/music/track/service/TrackListService.java
- src/main/java/org/example/music/track/service/TrackSearchRateLimiter.java
- src/main/resources/application.properties
- src/test/java/org/example/music/track/config/TrackSearchConfigTest.java
- src/test/java/org/example/music/track/controller/TrackListControllerTest.java
- src/test/java/org/example/music/track/service/TrackListParametersTest.java
- src/test/java/org/example/music/track/service/TrackListQueryServiceTest.java
- src/test/java/org/example/music/track/service/TrackListServiceTest.java
- src/test/java/org/example/music/track/service/TrackSearchRateLimiterIntegrationTest.java
- src/test/java/org/example/music/track/service/TrackSearchRateLimiterTest.java
- src/test/java/org/example/music/track/TrackListIntegrationTest.java
- src/test/java/org/example/music/track/TrackSearchQueryPlanIntegrationTest.java
- src/test/java/org/example/music/track/TrackSearchTimeoutIntegrationTest.java
