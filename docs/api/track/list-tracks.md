# 分页歌曲列表

## 接口与权限

`GET /api/v1/tracks`，需要 `Authorization: Bearer <ACCESS_TOKEN>`，无请求体。返回轻量歌曲列表与精确分页统计，不读取对象存储。

| 登录角色 | 未传 status | PUBLISHED | DRAFT / ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| 非管理员，含上传者本人 | 仅 PUBLISHED | 允许 | 403 FORBIDDEN |
| ADMIN 登录角色 | 所有状态 | 仅该状态 | 仅该状态 |

管理员以 USER 角色登录时按非管理员处理。身份来自服务端验证后的认证上下文，不接受请求中的用户 ID、角色或管理员标记。返回内容、计数、页数都只包含同一授权筛选范围。

## 查询参数

| 参数 | 类型 | 默认 | 约束 |
| --- | --- | --- | --- |
| page | Integer | 0 | 从 0 开始，非负整数 |
| size | Integer | 20 | 1—100，超限拒绝，不静默截断 |
| sort | String | id,desc | 单一字段与方向 |
| status | String | 按角色决定 | DRAFT / PUBLISHED / ARCHIVED，严格大写 |

未知参数、重复参数、空值、数字溢出、错误格式均返回 400 VALIDATION_FAILED。不能传 status=ALL、多个状态或搜索参数 q。认证先于参数验证；全部参数有效后才检查状态权限。

排序仅支持 id、title、artistName、createdAt；方向严格 asc/desc。非 ID 排序自动追加同方向 id，例 title,asc 实际按 title ASC、id ASC 排序。字符串按既有 MySQL utf8mb4_0900_ai_ci 规则排序，不提供拼音排序。

使用 long 运算限制 `page × size <= 10000`：page=100,size=100 允许，101,100 拒绝。请求结果之外的页是正常空页，不等于深分页参数一定合法。

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
| 400 | VALIDATION_FAILED | 未知/重复/空参数、类型范围、排序、状态或深分页非法 |
| 401 | UNAUTHORIZED | 未提供认证 |
| 401 | INVALID_TOKEN | 认证必要声明无效 |
| 401 | TOKEN_REVOKED | Token 版本不符、用户不存在或登录角色失效 |
| 403 | FORBIDDEN | 普通用户筛选 DRAFT / ARCHIVED |
| 500 | INTERNAL_ERROR | 当前页引用封面资产一致性故障 |
| 503 | TRACK_STORE_UNAVAILABLE | 业务数据库读取或事务故障 |
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
```

## 查询与维护说明

每次最多三条业务 SQL：歌曲投影、同范围 count、本页去重资产批次（最多 100 个 ID）。空页或无封面跳过资产查询。既有认证状态 MySQL 查询另计；本接口未实现认证缓存。

内容查询在同一 SQL 中先按索引分页取得 ID，再读取本页字段；不读取原始 ID3。深分页仍需跳过索引项，精确 count 成本仍随匹配数据增长，不把 offset 上限等同于恒定成本。

新增 V8__add_track_list_indexes.sql：created_at、(status,title)、(status,artist_name)、(status,created_at) 四个辅助索引。保留既有索引及迁移；索引增加写入和存储开销，部署时需安排建索引空间与元数据锁影响。真实 MySQL 执行计划见审核材料，不承诺特定环境延迟 SLA。

本次不含关键词搜索、播放、独立管理列表、专用限流、Redis 列表缓存、游标或 ETag 条件请求。
