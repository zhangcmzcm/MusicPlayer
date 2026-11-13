# T04 歌曲模糊搜索接口设计

日期为 2026-11-13，时区为 Asia/Shanghai。

设计核对基线为 `main @ 1d135027ebbecfba8b7d6f46c394cc13da615b5d`，工作目录为 `D:\code\Java-code\projects\music`。设计开始时工作树及暂存区干净。F01 收藏、F02 取消收藏、F03 收藏列表均已登记独立实现审核 accepted。

状态为用户已确认业务选择、技术方案 A，以及请求匹配、成本控制、分层与验证三部分设计，并于 2026-11-13 确认书面设计、实施计划并授权本会话逐任务实施。2026-11-14 已完成实现、中文 API、完整自验及独立审核，第1轮 accepted，第2轮证据复核撤销 NOTE-001，零未决项。自验与独立复跑均为199套件2376通过、0失败、0错误、0跳过，MySQL、Redis、MinIO实跑，审核记录为 docs/reviews/2026-11-13-track-search-review.md。

## 1. 目标与接口边界

扩展一个接口 `GET /api/v1/tracks`，增加可选查询参数 `q`，在当前登录角色可见的歌曲范围内，按标题、艺术家、专辑的子串匹配搜索，并返回精确分页结果。

请求参数集合为 `q`、`page`、`size`、`sort`、`status`。业务输入来自查询参数，响应复用 `PageResponse<TrackListItemResponse>`。歌曲 ID 使用现有 Long 表达。

数据库使用现有 `tracks`、`media_assets` 表及现有索引。当前迁移目录为 V1 至 V10，本设计的持久化方案采用现有结构。运行依赖使用现有 MySQL 8.4、Redis、Spring Data JPA 和 Spring Data Redis。

相关资料为[任务书](../../tasks/backend-roadmap.md)、[歌曲列表 API](../../api/track/list-tracks.md)和[原列表设计](2026-10-30-track-list-design.md)。本设计定义 `q` 扩展后的契约，API 文档在实施阶段同步更新。

## 2. 方案比较与已确认取舍

| 方案 | 收益 | 成本 | 决定 |
| --- | --- | --- | --- |
| A，参数化 LIKE 包含查询 | 支持中文单字、多词跨字段组合和符号字面搜索，复用当前 MySQL 查询与读事务 | 前置通配符影响普通 B-tree 范围定位，内容查询和精确计数可能扫描全部歌曲 | 用户选定 |
| B，ngram 全文索引配合 LIKE 回退 | 部分多字词可通过全文索引缩小候选范围 | 新增全文索引迁移，单字、停用词和符号输入需要兼容处理，查询分支增加 | 比较方案 |

MySQL 对普通 B-tree 的 LIKE 范围优化要求模式以固定前缀开头。`%关键词%` 的任意位置子串匹配存在扫描成本。ngram 默认粒度为 2，其停用词、空格和短词行为需要额外适配。[MySQL 范围优化](https://dev.mysql.com/doc/refman/8.4/en/range-optimization.html)、[MySQL ngram](https://dev.mysql.com/doc/refman/8.4/en/fulltext-search-ngram.html)

用户明确选择首版曲库预计 1 万首以内，接受方案 A 在这一规模下可能扫描全部歌曲的成本。这是针对本接口关键词搜索、对 AGENTS.md 第 10.2 节避免全表扫描要求的明确工程取舍。配套约束为关键词长度、词数、分页上限、账户限流、SQL 执行预算和真实执行计划验证。

1 万首用作首版验证规模。实际成本随曲库规模、匹配分布及数据库负载增长，内容和 COUNT 的执行计划与扫描行数需要分别记录。

## 3. 认证与状态权限

需要有效 Bearer JWT，沿用签名、有效期、issuer、audience、token version 与当前登录角色验证。身份来自服务端 `AuthenticatedUserPrincipal`，Service 使用方法级认证保护。

| 登录身份 | 未传 status | PUBLISHED | DRAFT 或 ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| 非管理员，含上传者本人 | 仅 PUBLISHED | 允许 | 403 FORBIDDEN |
| 以 ADMIN 角色登录 | 全部状态 | 仅该状态 | 仅该状态 |

管理员以 USER 角色登录时按普通用户规则处理。共享曲库的可见范围由当前登录角色决定。

处理顺序为认证、全部查询参数校验、状态权限检查、搜索额度检查、数据库查询。未认证且参数非法时返回 401。已认证且任意参数非法时返回 400，参数合法后普通用户筛选隐藏状态返回 403。

内容、总数、页数和 hasNext 都使用相同的状态与搜索条件。所有状态和关键词条件在数据库分页之前执行。

## 4. 查询参数

| 参数 | 类型 | 默认值 | 校验 |
| --- | --- | --- | --- |
| q | String | 缺省时读取普通列表 | 提供时，URL 解码后的原始值为 1 至 100 个 Unicode 码点，规范化后为 1 至 5 个词 |
| page | Integer | 0 | 非负，使用 long 运算检查 page × size ≤ 10000 |
| size | Integer | 20 | 1 至 100 |
| sort | String | id,desc | 单一白名单字段和方向 |
| status | String | 按登录角色决定 | 严格大写 DRAFT、PUBLISHED 或 ARCHIVED |

每个参数仅允许出现一次。未知参数、重复参数、空值、非法格式、数字溢出以及超出范围统一为 400 VALIDATION_FAILED。`q` 的纯空白值同样为 400。

page、size、sort、status 使用现有解析规则。数字解析采用现有 Integer 语义，排序和状态使用严格格式。

排序字段为 id、title、artistName、createdAt，方向为严格小写 asc 或 desc。非 ID 排序追加同方向 ID，例如 `title,asc` 按 title ASC、id ASC 排序。字符串排序使用现有 MySQL `utf8mb4_0900_ai_ci`。

合法分页超出实际结果范围时返回正常空页，并保留匹配范围的真实总数和总页数。

## 5. 关键词规范化与匹配

### 5.1 规范化顺序

1. 对 URL 解码后的原始 q 使用 Unicode 码点计数，先检查 100 码点上限。首尾空白也计入原始长度。
2. 使用 Unicode White_Space 规则去除首尾空白，并按连续空白拆词。普通空格、制表符、换行、全角空格及不换行空格采用同一分隔语义。
3. 检查拆分结果为 1 至 5 个词，每个词至少包含 1 个码点。

常见汉字和单个补充平面字符各按 1 个码点计数。重复词按实际出现次数计入 5 词上限，其匹配条件保持 AND 语义。词内字符由数据库现有排序规则处理。

### 5.2 匹配条件

每个词分别匹配 title、artist_name、album_name 中的任意字段。词内采用连续子串匹配，词与词之间使用 AND，允许跨字段组合。

| 输入 | 示例结果 |
| --- | --- |
| 晴 | 可命中标题“晴天” |
| 周杰伦 晴天 | 可分别命中艺术家“周杰伦”和标题“晴天现场版” |
| 晴天 现场 | 两个词均须在同一首歌的可搜索字段中命中 |
| 100% | 按字面搜索包含“100%”的字段 |

album_name 为 null 时，该字段贡献零个命中，标题和艺术家的匹配继续参与判断。重复词命中同一字段片段即可满足重复的 AND 条件。

大小写及重音采用现有 `utf8mb4_0900_ai_ci` 比较规则。具体字符行为由真实 MySQL 测试固定。

### 5.3 参数绑定与通配字符

查询使用绑定参数，LIKE 显式指定转义字符 `!`。生成模式前依次转义 `!`、`%`、`_`，然后在词的两侧添加包含匹配所需的 `%`。用户输入中的反斜杠按字面字符处理。

查询结构由受控词数生成，排序列、方向和执行预算来自服务端白名单或经过校验的配置。内容和 COUNT 复用相同的状态与关键词谓词及参数绑定规则。[MySQL LIKE 与 ESCAPE](https://dev.mysql.com/doc/refman/8.4/en/string-comparison-functions.html)

## 6. 成功响应

返回 200 OK、application/json，携带 `Cache-Control: no-store`。

```json
{
  "items": [
    {
      "id": 42,
      "title": "晴天现场版",
      "artistName": "周杰伦",
      "albumName": null,
      "durationMs": 245000,
      "status": "PUBLISHED",
      "cover": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

items 使用现有歌曲列表项，字段为 Long 类型 id、标题、艺术家、可空专辑、可空毫秒时长、状态和可空封面元信息。封面具有同源鉴权 url、contentType、sizeBytes。

page、size 为 Integer，totalElements、totalPages 为 Long。无匹配结果时 items 为 []、totalElements 和 totalPages 为 0、hasNext 为 false。合法越界页保留真实统计并返回空 items 和 false 的 hasNext。

输出字段采用明确 DTO。封面元信息采用当前列表的资产一致性规则，引用资产缺失或本地元信息非法时整页返回 500 INTERNAL_ERROR。存储对象的实际读取由封面 URL 对应接口完成。

## 7. 分层、数据流与事务

1. TrackListController 绑定参数、调用参数校验、取得可信主体并委派 Service。
2. TrackListService 确定有效状态范围。携带合法 q 且通过状态权限检查的请求，先消耗账户搜索额度，再调用查询 Service。
3. TrackListQueryService 在现有 REPEATABLE_READ 短只读事务中完成内容、精确总数和本页封面元信息读取。
4. TrackListRepository 在候选歌曲分页之前加入状态及全部搜索词条件，再按受控排序取得本页投影。COUNT 使用相同过滤条件。
5. 查询 Service 批量读取本页去重封面资产，校验后组装 DTO。

Redis 额度操作发生在歌曲只读事务之前。一次搜索的业务 SQL 最多为内容、COUNT、封面批次三条，封面批次最多 100 个 ID。空页或本页封面集合为空时采用两条业务 SQL。既有认证状态查询单独计入认证路径。

歌曲投影限定为列表 DTO、排序及封面关联所需字段。封面处理复用现有批量元信息读取与校验逻辑。

同次响应的内容、总数和封面数据来自同一 MySQL 读快照。稳定 ID 次排序用于确定同值记录顺序，跨请求 offset 分页随并发插入、删除或状态变化移动。

携带 q 的请求使用搜索限流及搜索 SQL 执行预算。缺省 q 的请求通过现有普通列表分支完成读取。

## 8. 搜索限流与配置

### 8.1 账户固定窗口

每个账户默认每 60 秒最多 60 次搜索。窗口从该账户首个通过认证、全部参数校验及状态权限检查的搜索请求起算，USER 和 ADMIN 登录共享同一账户额度。

Redis key 采用 `music:track-search:user:<userId>`，userId 来自可信主体。通过一个原子 Lua 操作完成状态检查、首次计数及 60 秒过期设置、后续计数和限流判定。

既存计数采用无前导零的正整数字符串，范围为 1 至 Integer.MAX_VALUE。计数小于配置额度时递增并放行，达到或超过额度时返回限流并保留当前计数及到期时间。这样可处理配置额度降低时已存在的合法计数。

使用 PTTL 读取毫秒 TTL，有效范围为 0 至 60000。PTTL 为 0 时按即将到期处理，受限请求的 Retry-After 为 1。key 缺失或 PTTL 为 -2 时重新建立首次窗口，PTTL 为 -1、超出有效范围或计数格式非法时返回额度状态故障。

额度在数据库查询前消耗，数据库故障和超时请求计入已消耗次数。超过额度返回 429 TRACK_SEARCH_RATE_LIMITED，Retry-After 为剩余 TTL 向上取整后的秒数，至少为 1。

Redis 连接故障、非法 key 类型、异常计数或 TTL、脚本返回空值或非法结构，转换为 503 TRACK_SEARCH_RATE_LIMIT_UNAVAILABLE。该错误结束本次搜索的数据库调用。

### 8.2 配置项

| 配置属性 | 环境变量 | 默认值 | 校验 |
| --- | --- | --- | --- |
| app.track-search.rate-limit-per-minute | TRACK_SEARCH_RATE_LIMIT_PER_MINUTE | 60 | 1 至 Integer.MAX_VALUE |
| app.track-search.query-timeout-ms | TRACK_SEARCH_QUERY_TIMEOUT_MS | 2000 | 1 至 Integer.MAX_VALUE，单位毫秒 |

配置通过专用搜索配置类型绑定并在启动时校验，固定窗口时长为 60 秒。

### 8.3 SQL 执行预算

搜索内容查询和精确 COUNT 的最外层 SELECT 分别应用 `MAX_EXECUTION_TIME`，默认各 2000 毫秒。超时经 Service 的数据库故障边界转换为 503 TRACK_STORE_UNAVAILABLE。

MySQL 该提示适用于只读 SELECT，含子查询时放在最外层第一个 SELECT 后，预算覆盖整条语句。以配置校验后的整数生成提示。[MySQL 执行时间提示](https://dev.mysql.com/doc/refman/8.4/en/optimizer-hints.html#optimizer-hints-execution-time)

此预算以每条内容或统计 SQL 为计时单位。HTTP 耗时还包括认证、Redis、连接获取、网络和封面元信息读取。

## 9. 错误与可观测性

业务错误使用 application/problem+json 的 ProblemDetail，携带稳定 code、现有 `urn:problem:music:*` 类型及 traceId 机制。响应采用 no-store。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | q 长度、空值、词数或其他查询参数非法 |
| 401 | UNAUTHORIZED | 缺少认证 |
| 401 | INVALID_TOKEN / TOKEN_REVOKED | 现有认证业务校验失败 |
| 403 | FORBIDDEN | 普通用户显式筛选 DRAFT 或 ARCHIVED |
| 429 | TRACK_SEARCH_RATE_LIMITED | 账户搜索额度耗尽，携带 Retry-After |
| 500 | INTERNAL_ERROR | 当前页封面资产一致性故障 |
| 503 | TRACK_STORE_UNAVAILABLE | 歌曲内容、统计、资产读取或事务故障，包含搜索 SQL 超时 |
| 503 | TRACK_SEARCH_RATE_LIMIT_UNAVAILABLE | 搜索额度依赖或状态异常 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 现有认证状态存储故障 |

JWT 解码层的签名、过期、issuer 或 audience 失败采用现有资源服务器 401 与 WWW-Authenticate 行为。

故障日志采用 SLF4J，保留 traceId、故障类型和脱敏诊断。错误响应字段使用稳定业务说明。

## 10. 查询成本验证

以真实 MySQL 8.4 和 1 万首歌曲构造执行计划验证数据，覆盖三种状态、可空专辑、不同匹配分布与相同排序值。

分别采集内容和 COUNT 的 EXPLAIN、EXPLAIN ANALYZE，记录实际扫描行数、返回行数、索引与排序方式及耗时。场景至少包括以下组合。

- 单字高频词和完全零命中。
- 中文、英文和跨字段多词匹配。
- 最大 5 词组合。
- USER 的 PUBLISHED 范围和 ADMIN 默认全部状态。
- 默认排序及文本、时间排序，最大页大小和最深合法分页。

LIMIT 和 offset 上限约束返回规模及允许的分页深度。精确 COUNT 需要评估所有候选记录的搜索条件，性能记录分别呈现扫描规模和输出规模。

搜索过滤需要在候选阶段读取可搜索字段，相关计划按新的搜索 SQL 实测。普通列表既有查询计划材料用于对照已有查询路径。

## 11. 预计实现文件范围

| 部分 | 文件或位置 | 职责 |
| --- | --- | --- |
| HTTP 与参数 | TrackListController、TrackListParameters 及必要的搜索词值类型 | q 文档、解析、边界校验 |
| 业务与事务 | TrackListService、TrackListQueryService | 可见范围、额度调用、只读一致性、DTO 组装 |
| 持久化 | TrackListRepository | 同条件内容及 COUNT、参数转义、搜索 SQL 预算 |
| 搜索组件 | track 包内搜索配置类型与 Redis 限流组件 | 外部配置、原子额度操作 |
| 异常 | track.exception、GlobalExceptionHandler | 新增 429 和限流依赖 503 业务异常映射 |
| 运行配置 | application.properties、.env.example | 两项搜索配置 |
| 测试 | 现有列表测试及搜索参数、Redis、MySQL、查询计划测试 | 契约扩展与回归 |
| API 文档 | docs/api/track/list-tracks.md | 搜索参数、示例、权限、错误、限流和成本说明 |
| 实施及审核材料 | docs/superpowers/plans、docs/reviews | 单接口实施计划与实际验证证据 |

具体实现文件清单由实施计划结合当前源码确定，变更服务于本接口的搜索契约。

## 12. 测试与完成条件

### 12.1 参数和 HTTP 契约

- 普通列表缺省 q，以及有效单词、多词搜索。
- 空 q、纯空白、100 与 101 码点、补充平面字符、5 与 6 词及重复词。
- 首尾及连续 Unicode 空白，包含全角空格、不换行空格、制表符和换行。
- 未知与重复参数、分页溢出、深分页、排序和状态非法。
- 未认证、普通用户隐藏状态筛选、以 USER 角色登录的管理员，以及错误优先级。
- 空结果、合法越界页、列表 DTO、no-store、ProblemDetail 和 Retry-After。

### 12.2 真实 MySQL

- 中文单字、三字段匹配、多词全部命中及跨字段组合，验证只命中部分词时的结果范围。
- 大小写、重音、空专辑，`%`、`_`、反斜杠、`!` 及 SQL 片段的字面搜索。
- USER 与 ADMIN 的内容、总数、页数和 hasNext 使用相同状态条件。
- 全部排序字段、同值 ID 次排序、精确统计、最大合法分页。
- 业务 SQL 数量、字段投影、封面批量读取及资产一致性错误。
- 同次响应读快照，以及并发状态变化时的授权和统计一致性。
- 内容与 COUNT 均具有执行预算，真实 MySQL 验证超时及 HTTP 503 转换。
- 1 万首数据的各类执行计划与实际扫描成本。

### 12.3 真实 Redis

- 首次建窗、阈值边界、并发额度、USER 与 ADMIN 共享额度及账户间隔离。
- 窗口到期后重新计数、被限流请求的饱和计数、剩余 TTL 与 Retry-After。
- PTTL 为 0、-1、-2 及超出 60000 的边界，配置额度降低后的合法既存计数。
- Redis 故障、非法类型、异常计数、非法脚本结果。
- 认证、参数或权限失败时的处理顺序，以及数据库故障时的额度消耗。

### 12.4 文档和独立审核

更新列表 OpenAPI 注解及中文 API 文档，覆盖完整路径、q 规范化、角色权限、字段、边界、响应、错误及可执行 curl。

实施完成后设置 `MINIO_INTEGRATION_TESTS=true`，执行 `.\gradlew.bat check`。需要强制重跑时使用 `.\gradlew.bat cleanTest check --console=plain`，根据 XML 汇总通过、失败、错误和跳过数量。MySQL、Redis、MinIO 按项目流程进行真实集成验证。

Codex 按 AGENTS.md 生成中文单接口审核请求，自行调用本机 Claude Code CLI 独立只读审核并跟进。反馈按原问题编号复核处置，完成条件包含有效 accepted 结论。

## 13. 请求示例与书面审阅

POSIX shell 示例中的 ACCESS_TOKEN 来自已有登录流程。

```sh
curl --get 'http://localhost:8080/api/v1/tracks' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'q=周杰伦 晴天' \
  --data-urlencode 'page=0' \
  --data-urlencode 'size=20' \
  --data-urlencode 'sort=id,desc'
```

已确认的主要默认值为 100 码点、最多 5 词、单字可搜索、默认 id,desc、每账户每 60 秒 60 次、每条搜索内容及 COUNT SQL 默认 2 秒。

书面设计细化项包括 Unicode White_Space 的精确定义、重复词按出现次数计数、显式 LIKE 转义字符 `!`、Redis 计数与 TTL 边界、key 前缀及配置属性命名。上述细化项已随书面设计获用户确认，实施依据为本设计及对应实施计划。
