# 分页歌曲列表

## 接口与权限

`GET /api/v1/tracks`，需要 `Authorization: Bearer <ACCESS_TOKEN>`，无请求体。支持按关键词搜索，返回轻量歌曲列表与精确分页统计，封面使用数据库资产元信息。

| 登录角色 | 未传 status | PUBLISHED | DRAFT / ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| 非管理员，含上传者本人 | 仅 PUBLISHED | 允许 | 403 FORBIDDEN |
| ADMIN 登录角色 | 所有状态 | 仅该状态 | 仅该状态 |

管理员以 USER 角色登录时按非管理员处理。身份来自服务端验证后的认证上下文，不接受请求中的用户 ID、角色或管理员标记。返回内容、计数、页数都只包含同一授权筛选范围。

## 查询参数

| 参数 | 类型 | 默认 | 约束 |
| --- | --- | --- | --- |
| q | String | 缺省时读取普通列表 | 原始解码值 1—100 个 Unicode 码点，按 Unicode 空白拆为 1—5 个词 |
| page | Integer | 0 | 从 0 开始，非负整数 |
| size | Integer | 20 | 1—100，超限拒绝，不静默截断 |
| sort | String | id,desc | 单一字段与方向 |
| status | String | 按角色决定 | DRAFT / PUBLISHED / ARCHIVED，严格大写 |

未知参数、重复参数、空值、数字溢出、错误格式均返回 400 VALIDATION_FAILED。status 使用单个明确状态。认证先于全部参数校验，参数合法后检查状态权限，再检查搜索额度。

排序仅支持 id、title、artistName、createdAt；方向严格 asc/desc。非 ID 排序自动追加同方向 id，例 title,asc 实际按 title ASC、id ASC 排序。字符串按既有 MySQL utf8mb4_0900_ai_ci 规则排序，不提供拼音排序。

使用 long 运算限制 `page × size <= 10000`：page=100,size=100 允许，101,100 拒绝。请求结果之外的页是正常空页，不等于深分页参数一定合法。

### 关键词匹配

q 的长度按 URL 解码后的原始值计算，首尾空白也计入 100 码点上限。常见汉字及单个补充平面字符各计 1 个码点。

按 Unicode White_Space 去除边界空白并拆词，普通空格、全角空格、NBSP、制表符和换行具有相同的分隔语义。连续空白形成一个分隔区间，规范化后须有 1—5 个词。重复词按出现次数计入上限。纯空白 q 为 400。

每个词在标题、艺术家、专辑中任意一个字段连续命中即可，各词须全部命中同一首歌，允许跨字段组合。例如 `q=周杰伦 晴天` 可分别命中艺术家“周杰伦”和标题“晴天现场版”。单字 `q=晴` 可命中“晴天”，可空专辑参与其余字段的组合匹配。

匹配采用 MySQL utf8mb4_0900_ai_ci 的大小写及重音不敏感规则。`%`、`_`、反斜杠和 `!` 按字面搜索。搜索排序使用 sort，默认 id,desc。

关键词、状态条件同时应用于分页内容和精确统计。搜索属于当前角色可见的共享曲库查询。

### 搜索额度与执行预算

携带有效 q 并通过状态权限检查的请求消耗搜索额度。每个账户默认每 60 秒 60 次，从首个合格搜索请求起算窗口。USER 和 ADMIN 登录共享账户额度，数据库查询失败和超时也计入已消耗次数。

超额返回 429 TRACK_SEARCH_RATE_LIMITED，Retry-After 为当前窗口剩余秒数向上取整，范围 1—60。Redis 故障或计数状态异常返回 503 TRACK_SEARCH_RATE_LIMIT_UNAVAILABLE，结束本次搜索。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| app.track-search.rate-limit-per-minute | TRACK_SEARCH_RATE_LIMIT_PER_MINUTE | 60 |
| app.track-search.query-timeout-ms | TRACK_SEARCH_QUERY_TIMEOUT_MS | 2000 |

两项均接受 1 至 Integer.MAX_VALUE 的整数，启动时校验。内容查询和精确 COUNT 的最外层 SELECT 分别应用毫秒预算，默认各 2 秒。超时返回 503 TRACK_STORE_UNAVAILABLE。HTTP 耗时还包含认证、Redis、连接获取、网络及封面元信息读取。

```http
GET /api/v1/tracks?page=0&size=20&sort=id,desc
Authorization: Bearer <ACCESS_TOKEN>
```

管理员筛选草稿示例：`GET /api/v1/tracks?status=DRAFT&page=0&size=20&sort=title,asc`。

## 成功响应

200 OK，application/json，Cache-Control: no-store。

```json
{
  "items": [
    {
      "id": 42,
      "title": "歌曲标题",
      "artistName": "艺术家",
      "albumName": "专辑名称",
      "durationMs": 245000,
      "status": "PUBLISHED",
      "cover": {
        "url": "/api/v1/tracks/42/cover",
        "contentType": "image/jpeg",
        "sizeBytes": 125600
      }
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| items | Array | 本页列表，空结果为 [] |
| items[].id | Long | 歌曲 ID |
| items[].title / artistName | String | 标题 / 艺术家 |
| items[].albumName | String 或 null | 专辑 |
| items[].durationMs | Long 或 null | 毫秒时长 |
| items[].status | String | DRAFT / PUBLISHED / ARCHIVED |
| items[].cover | Object 或 null | 正常无封面为 null |
| items[].cover.url | String | 与封面读取接口一致的同源鉴权地址 |
| items[].cover.contentType | String | JPEG、PNG 或 WebP 对应 MIME |
| items[].cover.sizeBytes | Long | 封面字节数 |
| page / size | Integer | 请求页与页大小 |
| totalElements / totalPages | Long | 授权筛选后的总数 / 向上整除的页数 |
| hasNext | Boolean | 是否还有下一页 |

albumName、durationMs、cover 缺失时明确输出 null。无结果时 totalElements=0、totalPages=0、hasNext=false。越界页保留真实总数和页数，items=[]、hasNext=false。

不返回曲序、碟号、ID3、审计字段、资产 ID、存储位置、摘要或凭据。createdAt 仅为排序字段，不增加响应字段。

## 封面语义与一致性

列表只读取本页封面资产元信息，不访问 MinIO HEAD/GET。无引用正常返回 null；本页有引用但资产记录缺失、非 COVER 或本地 MIME/大小/摘要/定位元信息非法，整页返回 500 INTERNAL_ERROR，不静默隐藏错误。隐藏状态或其他页的损坏资产不影响本页。

资产有效但实际存储对象已丢失时，列表仍可返回元信息和 URL；调用 [封面读取接口](get-track-cover.md) 时再按其 404/502/503 契约处理。共享资产只批量查询一次，URL 仍由各歌曲 ID 构造。

内容、count 和封面查询在同一个 REPEATABLE_READ 短只读事务内完成。ID 次排序保障固定数据集内同值顺序确定；并发修改仍可能让跨请求的 offset 分页移动，本接口不承诺跨页快照。

## 错误

业务错误为 application/problem+json，稳定 code、urn:problem:music:*，Cache-Control: no-store，保留现有 traceId 机制。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | q 长度、空白、词数、未知/重复/空参数、类型范围、排序、状态或深分页非法 |
| 401 | UNAUTHORIZED | 未提供认证 |
| 401 | INVALID_TOKEN | 认证必要声明无效 |
| 401 | TOKEN_REVOKED | Token 版本不符、用户不存在或登录角色失效 |
| 403 | FORBIDDEN | 普通用户筛选 DRAFT / ARCHIVED |
| 429 | TRACK_SEARCH_RATE_LIMITED | 搜索额度耗尽，携带 Retry-After |
| 500 | INTERNAL_ERROR | 当前页引用封面资产一致性故障 |
| 503 | TRACK_STORE_UNAVAILABLE | 业务数据库读取或事务故障，包含搜索内容或 COUNT 超时 |
| 503 | TRACK_SEARCH_RATE_LIMIT_UNAVAILABLE | 搜索额度依赖故障或状态异常 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态数据库不可用 |

JWT 签名、过期、issuer/audience 等解码失败沿用资源服务器既有 401 + WWW-Authenticate，可能没有业务 ProblemDetail。集合空结果不返回 404，也没有本接口的对象存储 502/503 分支。

```json
{
  "type": "urn:problem:music:validation_failed",
  "title": "请求校验失败",
  "status": 400,
  "detail": "Invalid track list parameters",
  "instance": "/api/v1/tracks",
  "code": "VALIDATION_FAILED"
}
```

## curl

POSIX shell，ACCESS_TOKEN 来自已有登录流程，管理员状态筛选需以 ADMIN 角色登录：

```sh
curl --get 'http://localhost:8080/api/v1/tracks' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'page=0' --data-urlencode 'size=20' --data-urlencode 'sort=id,desc'

curl --get 'http://localhost:8080/api/v1/tracks' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'status=DRAFT' --data-urlencode 'sort=title,asc'

curl --get 'http://localhost:8080/api/v1/tracks' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'q=周杰伦 晴天' \
  --data-urlencode 'page=0' --data-urlencode 'size=20' --data-urlencode 'sort=id,desc'
```

## 查询与维护说明

每次最多三条业务 SQL：歌曲投影、同范围 count、本页去重资产批次（最多 100 个 ID）。空页或无封面跳过资产查询。既有认证状态 MySQL 查询另计；本接口未实现认证缓存。

普通列表内容查询在同一 SQL 中先按索引分页取得 ID，再读取本页字段。搜索在候选 ID 分页之前读取标题、艺术家和专辑并应用全部关键词。精确 count 成本随候选数据增长，offset 上限约束分页深度。

新增 V8__add_track_list_indexes.sql：created_at、(status,title)、(status,artist_name)、(status,created_at) 四个辅助索引。保留既有索引及迁移；索引增加写入和存储开销，部署时需安排建索引空间与元数据锁影响。真实 MySQL 执行计划见审核材料，不承诺特定环境延迟 SLA。

关键词采用参数化 LIKE 包含查询，前置通配符影响普通 B-tree 范围定位。用户已确认首版预计 1 万首以内曲库的扫描取舍，内容和 COUNT 均可能扫描全部候选歌曲。该选择配套关键词边界、账户额度、SQL 执行预算和真实 MySQL 查询计划验证，实测材料见 [搜索查询计划](../../reviews/2026-11-13-track-search-query-plans.md)。
