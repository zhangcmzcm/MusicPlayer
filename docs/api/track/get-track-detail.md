# 歌曲详情

## 请求与用途

`GET /api/v1/tracks/{trackId}`：读取歌曲标题、艺术家、专辑、时长及封面元信息。

需要 `Authorization: Bearer <ACCESS_TOKEN>`；无请求体。trackId 为正整数 Long，范围 1 至 9223372036854775807；非整数、溢出、零或负数返回 400。

| 角色 | DRAFT | PUBLISHED | ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| 已认证非管理员，含上传者本人 | 404 | 200 | 404 |
| 以 ADMIN 角色登录的管理员 | 200 | 200 | 200 |

管理员以 USER 角色登录时按非管理员处理。歌曲是共享资源，管理员可以读取其他人创建的歌曲。不接受请求中的用户 ID 或角色作为授权依据。

## 成功响应

200，Content-Type 为 application/json，Cache-Control 为 no-store。

```json
{
  "id": 42,
  "title": "歌曲标题",
  "artistName": "艺术家",
  "albumName": "专辑名称",
  "durationMs": 245000,
  "trackNumber": 3,
  "discNumber": 1,
  "status": "PUBLISHED",
  "cover": {
    "url": "/api/v1/tracks/42/cover",
    "contentType": "image/jpeg",
    "sizeBytes": 125600
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | Long | 歌曲 ID |
| title | String | 标题 |
| artistName | String | 艺术家 |
| albumName | String 或 null | 专辑 |
| durationMs | Long 或 null | 毫秒时长 |
| trackNumber | Integer 或 null | 曲序 |
| discNumber | Integer 或 null | 碟号 |
| status | String | DRAFT、PUBLISHED 或 ARCHIVED |
| cover | Object 或 null | 无封面时为 null |
| cover.url | String | 同源相对地址 |
| cover.contentType | String | 图片 MIME |
| cover.sizeBytes | Long | 图片字节数 |

缺失的可选字段明确返回 null。封面 URL 对应 [歌曲封面读取接口](get-track-cover.md)，请求须携带有效 Bearer Token。详情只读取数据库元信息，不读取或验证 MinIO 对象；封面接口负责对象校验与图片读取。

不返回存储 bucket、对象键、凭据、资产 ID、原始文件名、原始 ID3、摘要或内部审计字段。

## 错误

业务错误与认证状态过滤器错误使用 application/problem+json，含稳定 code 与 `urn:problem:music:*` 类型。JWT 解码失败保留既有资源服务器响应，例外见下表。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | trackId 非法 |
| 401 | UNAUTHORIZED | 未提供认证 |
| 401 | 无业务 code（现有认证链行为） | JWT 签名、有效期、issuer 或 audience 校验失败，WWW-Authenticate 提供 invalid_token |
| 401 | INVALID_TOKEN | Token 的必要 subject、role 或 tokenVersion 声明无效 |
| 401 | TOKEN_REVOKED | 用户已不存在、版本不符或登录角色已不被允许 |
| 404 | TRACK_NOT_FOUND | 不存在或对当前角色不可见 |
| 500 | INTERNAL_ERROR | 封面引用缺失、类型错误等内部故障 |
| 503 | TRACK_STORE_UNAVAILABLE | 歌曲或封面数据库读取失败 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态数据库不可用 |

签名等校验失败沿用 Spring Security 资源服务器既有响应，可能使用 Bearer WWW-Authenticate 头而非业务 ProblemDetail，具体与项目现有认证过滤器一致。

```json
{
  "type": "urn:problem:music:track_not_found",
  "title": "歌曲不存在",
  "status": 404,
  "detail": "Track not found: 42",
  "instance": "/api/v1/tracks/42",
  "code": "TRACK_NOT_FOUND"
}
```

不可见与不存在使用同一异常和响应格式，不披露歌曲状态、上传者或封面。

## 调用示例

POSIX shell，先通过已有登录流程设置 ACCESS_TOKEN：

```sh
curl --request GET 'http://localhost:8080/api/v1/tracks/42' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Accept: application/json'
```

接口只读，无写入、幂等键、分页、排序、专用限流或详情缓存。重复读取按请求时状态判定可见性。
