# 本人收藏分页列表

## 接口与权限

`GET /api/v1/users/me/favorites`，需要 `Authorization: Bearer <ACCESS_TOKEN>`，无请求体。查询本人收藏中的可见歌曲及精确分页统计。

| 登录角色 | 不传 status | PUBLISHED | DRAFT / ARCHIVED |
| --- | --- | --- | --- |
| USER，包括上传者本人 | 仅已发布 | 允许 | 403 FORBIDDEN |
| ADMIN | 本人收藏全部状态 | 仅已发布 | 仅该状态 |

管理员以 USER 角色登录时按 USER 处理。归属和角色只取验证后的认证主体，不接受请求中的身份声明。隐藏歌曲不占页内名额、不计入总数，但保留收藏关系；歌曲物理删除后收藏由外键级联删除。

## 查询参数

| 参数 | 类型 | 默认 | 约束 |
| --- | --- | --- | --- |
| page | Integer | 0 | 从 0 开始，非负 |
| size | Integer | 20 | 1—100，超限拒绝 |
| sort | String | favoritedAt,desc | 一个字段与方向，见下文 |
| status | String | 按角色决定 | 严格大写 DRAFT / PUBLISHED / ARCHIVED |

仅接受以上四项。未知参数（包括 userId、q）、重复参数（即使同值）、空值、空白、溢出和格式错误均返回 400 VALIDATION_FAILED。附带正文与自定义身份头不参与业务；不支持 status=ALL、多个状态或多个排序。

数字沿用歌曲列表 Integer.parseInt 语义，不 trim；解码后的 +1、01 可接受，小数、前后空格及 Integer 溢出拒绝。使用 long 计算限制 `page × size ≤ 10000`，100×100 允许，101×100 拒绝。

sort 字段支持 id、title、artistName、createdAt、favoritedAt，方向严格 asc/desc，大小写敏感、不 trim。createdAt 是歌曲创建时间，favoritedAt 是本人当前收藏记录时间。非 id 排序追加同方向歌曲 id，例如默认按收藏时间 DESC、歌曲 id DESC。字符串沿用 MySQL utf8mb4_0900_ai_ci，不提供拼音排序。

认证先于参数校验；全部参数合法后才检查状态权限。

```http
GET /api/v1/users/me/favorites?page=0&size=20&sort=favoritedAt,desc
Authorization: Bearer <ACCESS_TOKEN>
```

## 成功响应

200 OK，application/json，Cache-Control: no-store。

```json
{
  "items": [{
    "id": 42,
    "title": "歌曲标题",
    "artistName": "艺术家",
    "albumName": null,
    "durationMs": null,
    "status": "PUBLISHED",
    "cover": {
      "url": "/api/v1/tracks/42/cover",
      "contentType": "image/png",
      "sizeBytes": 125600
    },
    "favoritedAt": "2026-11-12T08:30:00.123456Z"
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| items | Array | 本页数据，空为 [] |
| items[].id | Long | 歌曲 ID |
| title / artistName | String | 标题、艺术家 |
| albumName | String 或 null | 专辑 |
| durationMs | Long 或 null | 毫秒时长 |
| status | String | 歌曲状态 |
| cover | Object 或 null | 无封面明确返回 null |
| cover.url | String | 同源鉴权封面地址 |
| cover.contentType / sizeBytes | String / Long | JPEG、PNG、WebP MIME / 字节数 |
| favoritedAt | String | 非空 UTC ISO-8601 收藏时间，保留数据库微秒精度 |
| page / size | Integer | 请求页码、页大小 |
| totalElements / totalPages | Long | 同一归属与状态范围的总数、向上整除页数 |
| hasNext | Boolean | 是否还有下一页 |

albumName、durationMs、cover 缺失均输出 null。无结果时 totalElements=0、totalPages=0、hasNext=false；合法越界页保留请求页码及真实统计，items=[]、hasNext=false。集合无结果不返回 404。

重复 PUT 保留首次收藏时间。取消后重新收藏创建新记录，favoritedAt 与默认排序随之更新。歌曲 createdAt 仅为排序字段，不返回该字段。不暴露用户 ID、其他审计字段、资产 ID、原始 ID3、存储位置、摘要或凭据。

## 封面、一致性与成本

仅批量读取本页去重封面元信息，不发送 MinIO HEAD/GET。本页引用不存在、非 COVER 或元信息非法的资产时整页 500，不静默隐藏。其他页、其他用户或不可见歌曲的坏资产不影响本页。

元信息合法而实际对象丢失时列表仍返回封面 URL，随后调用[封面接口](../track/get-track-cover.md)按其既有契约处理。

内容、count、封面查询在同一 REPEATABLE_READ 短只读事务中执行，普通快照读取，不主动获取收藏写操作的歌曲行锁。并发取消、归档、删除提交后，本次响应仍可反映旧快照，下次请求反映变化；封面后续请求会重新鉴权。不承诺跨请求分页快照，翻页期间的变更可能引起条目移动、重复或遗漏。

最多三条业务 SQL，认证 SQL 另计。空页或无封面时跳过资产查询。V10 增加 `(user_id, created_at, track_id)` 索引，原迁移保持不变。查询以本人收藏驱动关联，避免稀疏收藏从全曲库扫描；其他歌曲字段可能需在本人收藏范围排序。精确 count、部分状态筛选与排序成本仍随候选量增长，offset 上限不等于恒定成本。索引增加写入、存储及部署建索引开销，不承诺固定延迟 SLA。

本接口不增加搜索、独立限流、Redis 列表缓存、ETag 或游标分页。

## 错误响应

业务错误使用 application/problem+json、Cache-Control: no-store、稳定 code 与既有 urn 类型，保留 traceId 机制。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 参数校验失败 |
| 401 | UNAUTHORIZED | 未提供认证 |
| 401 | INVALID_TOKEN | 必要声明非法 |
| 401 | TOKEN_REVOKED | 账户不存在、版本撤销或登录角色失效 |
| 403 | FORBIDDEN | USER 筛选隐藏状态 |
| 500 | INTERNAL_ERROR | 本页封面一致性故障或未预期异常 |
| 503 | FAVORITE_STORE_UNAVAILABLE | 内容、count、封面数据库查询或事务边界故障 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态存储故障 |

JWT 签名、过期、issuer/audience 等解码失败沿用资源服务器 401 和 WWW-Authenticate，可能没有业务 ProblemDetail。收藏存储故障沿用 title“收藏存储不可用”、detail“歌曲收藏暂时无法完成”及 `urn:problem:music:favorite_store_unavailable`，不泄漏数据库信息，不返回部分页或伪造空页。

```json
{
  "type": "urn:problem:music:validation_failed",
  "title": "请求校验失败",
  "status": 400,
  "detail": "收藏列表查询参数无效",
  "instance": "/api/v1/users/me/favorites",
  "code": "VALIDATION_FAILED"
}
```

## curl

POSIX shell，ACCESS_TOKEN 由已有登录流程获取，第二例需 ADMIN 登录角色。

```sh
curl --get 'http://localhost:8080/api/v1/users/me/favorites' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'page=0' --data-urlencode 'size=20' \
  --data-urlencode 'sort=favoritedAt,desc'

curl --get 'http://localhost:8080/api/v1/users/me/favorites' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'status=ARCHIVED' --data-urlencode 'sort=title,asc'
```
