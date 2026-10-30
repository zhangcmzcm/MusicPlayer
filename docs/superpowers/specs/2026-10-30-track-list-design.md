# 分页歌曲列表接口设计

日期：2026-10-30

状态：用户已确认书面设计并要求进入实现计划（2026-10-30）；尚无独立代码审核结论。

基线：main @ 362a264。

## 1. 目标与范围

本次只实现 `GET /api/v1/tracks`，按当前登录角色及可选歌曲状态分页查询，返回轻量列表 DTO、可空封面元信息与分页统计。

状态使用查询参数 status，不新增 `/tracks/draft`、`/tracks/published`、`/tracks/archived` 等独立接口，不与已有 `/{trackId}` 详情路由混淆。歌曲状态不是流派等音乐类型。

不实现关键词搜索、播放、独立管理员列表、编辑、封面上传或认证缓存。封面图片继续由已完成的 `GET /api/v1/tracks/{trackId}/cover` 提供。

## 2. 方案比较与确认结果

| 方案 | 收益 | 代价 |
| --- | --- | --- |
| 统一列表加 status 参数（选定） | 单接口复用分页、排序和 DTO，后续便于扩展筛选 | 必须明确角色对默认值和筛选的影响 |
| 状态放路径，如 /tracks/draft | 直观 | 多个接口重复契约，需处理与详情路径的边界 |
| 普通列表和 /admin/tracks 分开 | 管理字段与权限可独立演进 | 当前相同列表字段需要维护两套入口 |

不传 status 时，非管理员仅查询 PUBLISHED，ADMIN 登录角色查询全部状态。非管理员显式筛选隐藏状态时，已选择明确拒绝的 403，而不是返回 200 空列表。

当前任务书 T05 的基础状态查看需求已由本接口覆盖。未来只有需要额外管理字段或筛选时才单独设计管理列表，不重复实现当前状态过滤能力；实现阶段更新该任务的说明。

## 3. HTTP 请求契约

- Method：GET。
- Path：`/api/v1/tracks`。
- 需要有效 Bearer JWT，沿用现有签名、issuer、audience、有效期、token version 与角色校验。
- 无请求体。
- 请求参数仅定义 page、size、sort、status；未定义的查询参数返回 400，本次不接受 q 或其他搜索参数。

| 参数 | 类型 | 缺省值 | 校验 |
| --- | --- | --- | --- |
| page | Integer | 0 | 从 0 开始，非负整数 |
| size | Integer | 20 | 1—100，超限返回 400，不截断 |
| sort | String | id,desc | 单一排序条件，格式为允许字段,asc 或允许字段,desc |
| status | String | 按角色决定 | 若提供，必须为 DRAFT、PUBLISHED、ARCHIVED 之一，严格大写 |

缺省仅指参数未提供。显式空值、格式错误、数字溢出或非法枚举均返回 400 VALIDATION_FAILED。每个已定义参数仅允许出现一次，不接受重复 sort 形成多个排序条件，也不接受逗号分隔的多个状态或 status=ALL。

校验全部参数有效后，再检查角色是否允许该状态筛选。认证过滤器先于业务参数及权限检查，未认证请求仍按现有认证链处理。

深分页限制：以 long 运算检查 `page × size <= 10000`。等于 10000 允许，超过返回 400 VALIDATION_FAILED。先校验 size 和数字范围，不使用 int 乘法，避免溢出绕过上限。

请求示例：

```http
GET /api/v1/tracks?status=DRAFT&page=0&size=20&sort=title,asc
Authorization: Bearer <ACCESS_TOKEN>
```

## 4. 权限与状态

| 登录身份 | 未传 status | PUBLISHED | DRAFT | ARCHIVED |
| --- | --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 | 401 |
| 已认证非管理员，含上传者本人 | 仅 PUBLISHED | 允许 | 403 FORBIDDEN | 403 FORBIDDEN |
| 以 ADMIN 角色登录的管理员 | 全部状态 | 仅该状态 | 仅该状态 | 仅该状态 |

管理员以 USER 角色登录按非管理员处理。角色只能取自验证后的认证上下文，不接受前端用户 ID、角色或管理员标记。共享曲库列表不按创建者归属筛选。

Service 在查询前确定有效过滤范围。被拒绝的状态筛选不查询歌曲内容或计数；非法状态对所有已登录角色都是 400，不误当成权限错误。

分页内容和 totalElements 必须使用相同的数据库状态谓词。不能先查询全部歌曲、在内存剔除隐藏状态，再返回包含隐藏记录数量的总数。

本接口是集合查询，无单首歌曲不存在的 404 分支。空曲库、筛选无结果或请求页超出当前结果范围均返回 200，items 为空；超出结果范围的页仍返回过滤后的实际总数。

## 5. 排序

白名单仅包括 id、title、artistName、createdAt，分别映射到受控持久化字段；asc、desc 严格小写。非法字段、方向、表达式、关联路径、多条件、缺失字段或方向返回 400。

默认 `id,desc`。非 ID 排序自动追加同方向 id 作为第二排序键，例如 `title,asc` 实际排序为 title ASC、id ASC。前端不能自行添加第二排序字段。

排序完全在数据库完成，不能在分页后对本页重新排序。字符串排序沿用数据库现有 utf8mb4_0900_ai_ci 排序规则，不另做 Java 拼音或大小写排序。

追加 id 保障同值记录在给定数据集内顺序确定，不保证多次请求之间的快照不变。并发插入、删除或状态变更仍可能使 offset 分页跨页移动；本次不提供游标或跨请求一致性快照。

## 6. 成功响应 DTO

200 OK，application/json，Cache-Control: no-store。使用明确分页 DTO，不直接序列化 Spring Page 或 JPA Entity。

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

列表项：id 为 Long；title、artistName、albumName、status 为 String；durationMs 为 Long。albumName、durationMs 可空；无封面引用时 cover 明确为 null，不省略。

封面结构与详情一致：url 是固定同源路由与歌曲 ID 构造的字符串；contentType 是已验证 MIME；sizeBytes 为 Long。允许 JPEG、PNG、WebP。不返回资产 ID、bucket、对象键、原始文件名、摘要、原始 ID3 或审计字段。

不返回曲序和碟号。排序可使用 createdAt，但不因此向列表增加时间字段。

分页字段：page、size 为 Integer；totalElements、totalPages 为 Long；hasNext 为 boolean。页数由过滤后的 totalElements 与 size 向上整除计算，不使用可能溢出的总数加法或 Spring Page 的 int 页数强制转换。

totalElements=0 时 totalPages=0、hasNext=false。items 不是 null。hasNext 根据请求页与真实页数计算；空页与越界页不误报下一页。

该分页结构作为后续可复用的最小 DTO 定义；不借机调整现有其他接口的返回结构。

## 7. 封面异常语义

没有封面引用是正常数据，返回 cover=null。有引用但资产记录缺失、kind 非 COVER 或资产元信息非法时，整页返回 500 INTERNAL_ERROR，不静默降级为 null。

本地有效性与现有封面读取规则对齐：MIME 位于白名单、sizeBytes 正数、sha256 为 64 位十六进制、bucket 与 objectKey 非空。所需校验字段可在批量资产读取时取得，只用于内部校验，不返回客户端。

只检查本页可见歌曲引用的资产，隐藏状态或其他页的异常不影响当前页。若多首歌曲共用封面资产，查询 ID 去重，但每个封面 URL 仍由各自歌曲 ID 构造。

列表不调用 MinIO HEAD/GET。数据库资产存在但对象已丢失时，本接口仍可返回其元信息与 URL；实际读取封面时由已有封面接口返回 404 COVER_NOT_FOUND 或相应 502/503。列表不包含 COVER_NOT_FOUND、COVER_STORAGE_INCONSISTENT 或 MEDIA_STORAGE_UNAVAILABLE 的对象存储分支。

## 8. 查询、分层与事务

Controller 仅绑定及校验参数、取得可信主体、调用 Service 并构造 HTTP 响应。Service 确定角色过滤范围、受控排序、分页边界、封面校验与 DTO 组装。Repository 只进行数据访问。

列表查询使用字段投影，仅读取 id、title、artistName、albumName、durationMs、status、coverAssetId 及查询确需的排序字段，不加载整条 Entity 中的原始 ID3 JSON。

每次请求的业务查询最多三条：

1. 带数据库状态过滤和 LIMIT/OFFSET 的歌曲投影查询。
2. 使用同一状态条件的总数查询。
3. 将本页去重封面 ID 用一次有界 IN 查询批量读取，最多 100 个 ID。

空页或无封面 ID 时跳过资产查询。框架能正确推导总数时可减少 count，但必须保证越界页总数准确；不以减少查询为由返回错误计数。禁止逐首 findById 读取封面。

SQL 数量限制仅指上述业务查询；既有认证状态读取 MySQL 的查询另计，不能伪称认证查询已经移除。测试分别统计业务与认证路径。

Service 层使用短只读事务；同次内容、总数和资产查询采用 MySQL REPEATABLE_READ 快照语义，避免一页响应内部混用不同时刻的内容与总数。必要时使用独立查询 Bean，使事务创建/执行异常在外层转换为 503；不将事务放到 Controller。

不拼接原始 sort/status 到 SQL。区分“全部状态”和“指定单状态”的受控查询，避免不必要的可空 OR 条件损害索引选择。

## 9. 索引与迁移边界

当前有主键 id、单列 status、title、artist_name、album_name 索引，没有 created_at 索引；不得声称全部允许排序已获现有索引完整覆盖。

实现阶段必须在真实 MySQL 8.4 上，对管理员全部状态与单状态过滤两类查询、四个白名单排序记录 EXPLAIN。使用具有重复标题、艺术家、时间与不同状态的代表性数据，不能仅凭一条测试记录判断查询成本。分页深度上限约束返回位置，不等于保证所有查询不扫描或不排序。精确 COUNT 仍需统计全部匹配记录，成本随授权范围内的数据量增长；应核对其索引访问计划，不将 LIMIT 或深分页上限误称为计数成本上限。

优先复用既有索引。若查询计划表明缺少必要索引导致允许的列表访问需要无界表扫描或全量排序，新增本接口所需的最少排序/状态组合索引，记录前后执行计划与索引写入成本；不删除既有索引，不凭所有字段组合预建大量索引。

目前最新迁移 V7；若实现时确认需新增索引，使用下一可用版本（当前为 V8）新增迁移，并在审核材料明确实际字段和证据。不能修改 V1—V7 或使用 ddl-auto=update。索引具体选择属于执行计划驱动的实现决策，不改变本接口已确认参数或权限契约。

## 10. 错误与缓存

| 场景 | HTTP | code |
| --- | --- | --- |
| 未知参数，参数格式、范围、重复、空值、排序或状态非法，深分页超限 | 400 | VALIDATION_FAILED |
| 未提供认证 | 401 | UNAUTHORIZED |
| 认证必要声明无效 | 401 | INVALID_TOKEN |
| Token 版本不符、用户不存在或登录角色失效 | 401 | TOKEN_REVOKED |
| 非管理员筛选 DRAFT 或 ARCHIVED | 403 | FORBIDDEN |
| 本页封面资产一致性故障 | 500 | INTERNAL_ERROR |
| 业务数据库读取/事务故障 | 503 | TRACK_STORE_UNAVAILABLE |
| 认证状态存储不可用 | 503 | AUTH_STATE_STORE_UNAVAILABLE |

业务错误使用 RFC 9457 ProblemDetail、application/problem+json、稳定 code、urn:problem:music:* 与现有 traceId 机制，不泄漏 SQL、堆栈、凭据或存储位置。

JWT 签名、有效期、issuer、audience 等解码失败沿用既有资源服务器 401/WWW-Authenticate，可能没有业务 ProblemDetail；统一认证错误仍由任务书 O06 单独处理。

成功和业务错误响应均采用 no-store；本次不实现列表 ETag、缓存或条件请求。缓存规则仅限本列表，不改已有封面 private, no-cache。

没有新增业务错误码需求。复用 TRACK_STORE_UNAVAILABLE 时不顺手修改已登记 O07 的预存错误文案。

## 11. 测试与交付

- Controller：默认值、完整响应、null 字段、空列表、200/no-store；所有非法参数、未知及重复参数、状态大小写、size 上限、深分页乘积边界、整数溢出、未认证和 403；验证 ProblemDetail code 与媒体类型。
- Service：角色 × 状态矩阵，含管理员 USER 登录与上传者本人；拒绝筛选时无业务 Repository 调用；过滤和总数相同；四种排序正反方向及 ID 次排序；空页和越界页统计；资产共享 ID 去重、无封面、错误类型/元信息/缺失引用；数据库及事务异常转换。
- 真实 MySQL/Redis 与 JWT：同时准备三种状态，断言内容、总数、页数只反映授权范围；多页同值排序确定；不同请求并发数据变化不被误描述为跨页快照保证。
- SQL 回归：多首歌曲各有封面时，对不同 size 统计业务语句，断言最多三条且不随歌曲数量线性增加；确认没有逐首封面查询、投影 SQL 不读取 id3_metadata、没有 MinIO 调用。认证查询单独标识。
- 数据库性能：实际排序/筛选 EXPLAIN 及必要新增索引由真实 MySQL 验证；如有迁移，验证执行和既有数据保留。
- 存储回归：虽列表不访问存储，仍按 track 模块要求设置 MINIO_INTEGRATION_TESTS=true 运行完整 `.\gradlew.bat check`，必要时 cleanTest 强制重跑。必要容器测试被跳过不得宣称完成。

新增或更新完整 OpenAPI，新增中文 `docs/api/track/list-tracks.md`，包含认证/权限矩阵、四个参数、排序、深分页、字段、空值、错误、示例和占位 Token 的 curl。

实现阶段更新任务书 T02 状态及 T05 重叠范围，代码审核 accepted 前不标记已完成。按 AGENTS.md 提供含 untracked 的完整变更文件、实际测试命令与通过/失败/跳过数量、迁移和基线。

## 12. 工程默认项

未知参数、显式空值与重复已定义参数按 400 处理；排序方向使用小写；分页计数使用 Long；不引入拼音排序、无限滚动、游标、搜索、专用限流或 Redis 缓存。这些默认项不改变已确认的单接口范围。
