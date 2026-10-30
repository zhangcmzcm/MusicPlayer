# 获取歌曲封面

## 接口

`GET /api/v1/tracks/{trackId}/cover`：鉴权后从私有 MinIO/S3 返回完整封面图片，与歌曲详情 cover.url 一致。

需要 Authorization: Bearer Token，无请求体。trackId 为 Long 正整数（1 至 9223372036854775807）；非法值、溢出、小数均返回 400 VALIDATION_FAILED。

| 登录身份 | DRAFT | PUBLISHED | ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| 非管理员，含上传者本人 | 404 | 200/304 或封面/存储错误 | 404 |
| ADMIN 登录角色 | 200/304 或封面/存储错误 | 200/304 或封面/存储错误 | 200/304 或封面/存储错误 |

管理员以 USER 角色登录时按非管理员处理。歌曲不存在与不可见统一返回 TRACK_NOT_FOUND；检查通过后才读取封面资产和存储。不按上传者归属限制管理员访问。

## 请求头与响应

可选 If-None-Match：支持单标签、多个标签、弱标签 W/ 和通配符 *。无效条件不触发 304。

200 响应体是原始图片字节，不是 JSON。Content-Type 为 image/jpeg、image/png 或 image/webp，Content-Length 为验证后的完整长度。ETag 为数据库与对象存储一致性校验通过的 SHA-256 小写摘要加双引号。

200 和 304 均包含：

```http
Cache-Control: private, no-cache
Vary: Authorization
ETag: "<已验证的64位十六进制摘要>"
```

Vary 合并保留已有项。304 无响应体、不设置 Content-Length，不打开内容流；但仍完成认证、可见性、资产及存储 HEAD 检查。对象缺失、不一致或 MinIO 不可用时，即使标签匹配也返回错误。

Range 被忽略；正常响应完整 200，不返回 206、Content-Range 或声明 Accept-Ranges: bytes。If-None-Match 匹配时仍返回 304。不提供 Last-Modified、If-Modified-Since、缩略图或格式转换。

浏览器可以保存私有副本，但使用前需重新请求验证。已下载或已展示的图片不能由后端撤回；不支持共享代理/CDN 缓存。前端应使用能携带 Authorization 的请求获取图片，不将 Token 放到 URL。

一致性校验比较大小、MIME 和摘要元信息，不是每次下载重新计算内容摘要；依赖上传验证及对象不可原地修改约束。数据库事务在存储 IO 前结束，图片通过固定缓冲区传输。

## 错误

业务错误为 application/problem+json，具有稳定 code、urn:problem:music:* 类型及 Cache-Control: no-store，不包含存储位置或内部异常。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | trackId 格式或范围无效 |
| 401 | UNAUTHORIZED | 未提供认证 |
| 401 | INVALID_TOKEN | subject、role、tokenVersion 等必要声明无效 |
| 401 | TOKEN_REVOKED | 用户不存在、Token 版本变化或角色被撤销 |
| 404 | TRACK_NOT_FOUND | 歌曲不存在或不可见 |
| 404 | COVER_NOT_FOUND | 无封面或封面对象不存在 |
| 500 | INTERNAL_ERROR | 封面引用悬空、非 COVER 或本地资产元信息非法 |
| 502 | COVER_STORAGE_INCONSISTENT | 存储对象元信息缺失、非法或与数据库不一致 |
| 503 | MEDIA_STORAGE_UNAVAILABLE | 存储连接失败、超时或发送前读取失败 |
| 503 | TRACK_STORE_UNAVAILABLE | 业务数据库/事务无法读取 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态数据库不可用 |

JWT 签名、过期、issuer/audience 等解码失败沿用既有资源服务器 401 + WWW-Authenticate，可能不含业务 ProblemDetail；不在本接口修改认证公共行为。

```json
{
  "type": "urn:problem:music:cover_not_found",
  "title": "Cover not found",
  "status": 404,
  "detail": "Cover not found",
  "instance": "/api/v1/tracks/42/cover",
  "code": "COVER_NOT_FOUND"
}
```

无封面由前端展示默认图片。资产存在但对象丢失时记录含 trackId、traceId 的脱敏 warning；不向响应或该 warning 写入 bucket/key。

发送前失败返回上述错误；响应提交后的读取中断或客户端断开仅中止传输并关闭流，不能改变状态码或追加 JSON。客户端应将短于 Content-Length 的响应视为失败，不能据此保存完整图片。

## curl 示例

POSIX shell；ACCESS_TOKEN 由已有登录流程获取，ETAG 保存上次响应头 ETag 的完整值（包括双引号）。

```sh
curl --request GET 'http://localhost:8080/api/v1/tracks/42/cover' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --dump-header cover.headers --output cover.bin

curl --request GET 'http://localhost:8080/api/v1/tracks/42/cover' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "If-None-Match: ${ETAG}" \
  --dump-header conditional.headers --output conditional.body
```

接口只读，无分页、写入幂等键、专用限流或服务端图片缓存。304 减少内容传输，但不会消除认证和存储元信息查询。
