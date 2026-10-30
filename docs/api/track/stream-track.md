# 歌曲播放

## 接口与权限

`GET /api/v1/tracks/{trackId}/stream`，鉴权后从私有 S3/MinIO 流式返回原始 MP3 或 FLAC，不转码，无请求体。

`trackId` 为 Long 正整数（1 至 9223372036854775807）；0、负数、小数、非数字及溢出返回 400。必须使用 `Authorization: Bearer <Token>` 请求头，不接受 URL Token。本接口适用于可携带该请求头的客户端；原生浏览器 `audio.src` 的媒体授权方案另行设计。

| 登录身份 | DRAFT | PUBLISHED | ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| 非管理员（含上传者本人） | 404 | 可播放 | 404 |
| ADMIN 登录角色 | 可播放 | 可播放 | 可播放 |

管理员以 USER 角色登录按非管理员处理。资源不存在与不可见统一为 404 TRACK_NOT_FOUND，不向无权访问者暴露长度、摘要或状态。共享曲库按上述角色/状态判断，不限制已发布歌曲的上传者归属。

每次请求（包括 Range、条件请求和隐式 HEAD）重新认证，然后检查可见性、本地资产和存储 HEAD。数据库只读事务在存储 I/O 前结束；错误不会因缓存命中被绕过。不新增数据库迁移。

## 成功响应

200 返回完整音频，206 返回单个闭区间的精确原始字节；响应不是 JSON。

| 响应头 | 200 / 206 |
| --- | --- |
| Content-Type | audio/mpeg 或 audio/flac |
| Content-Length | 本次响应字节数；206 是区间长度 |
| Accept-Ranges | bytes |
| Content-Range | 仅 206：bytes start-end/total |
| ETag | 已验证 SHA-256 小写十六进制摘要，带双引号的强标签 |
| Last-Modified | 音频资产 createdAt 的 UTC HTTP-date，精确到秒 |
| Cache-Control | private, no-cache |
| Vary | 合并已有项并包含 Authorization |

资产时间若晚于请求时刻，则 Last-Modified 钳制到请求时刻，不能作为 If-Range 的可靠日期验证器。正常日期的强验证依赖当前音频引用不可原地替换的约束；后续支持替换音频时必须重新评估。摘要检查比较数据库和对象元信息，不是每次流式传输重新计算整首歌曲摘要。

304 无响应体，保留 ETag、Last-Modified、Cache-Control、Vary，不设置 Content-Length，也不打开内容流。它仍执行认证、权限及 HEAD 检查。浏览器保存的私有副本每次使用前需重新验证，不支持共享代理/CDN 缓存。已下载字节无法撤回；播放中途权限变化不追溯撤销当前已建立的响应，下次请求重新判断。

Spring 隐式支持 HEAD：鉴权、可见性、元信息与条件检查和 GET 一致；忽略 Range，成功返回完整资源响应头但不读取内容，也不创建独立 HEAD 业务接口。

## Range 与条件请求

支持单个 `Range: bytes=start-end`、`bytes=start-`、`bytes=-suffix`，范围端点包含在响应中。以总长 10 为例：

| Range | 结果 |
| --- | --- |
| bytes=0-0 | 206，bytes 0-0/10，长度 1 |
| bytes=5- | 206，bytes 5-9/10，长度 5 |
| bytes=-3 | 206，bytes 7-9/10，长度 3 |
| bytes=0-999 或 bytes=-999 | 206，bytes 0-9/10，长度 10 |
| bytes=10-、bytes=9-2、bytes=-0 | 416，Content-Range: bytes */10 |
| bytes=abc 等已识别 bytes 的非法单区间语法 | 本契约选择 416，不忽略 |
| 多区间、重复 Range 字段或未知范围单位 | 忽略整个 Range，正常完整 200 |

超 long 的 end/后缀先安全裁剪，超大 start 返回 416，不溢出成 500。合法覆盖全文件的范围仍为 206，不改为 200。不生成 multipart/byteranges。

在认证、可见性、资产及 HEAD 校验完成后，依次处理：

1. `If-Match`：强比较，支持标签列表和 `*`；不满足返回 412。弱标签不能满足。
2. 仅无 If-Match 时处理 `If-Unmodified-Since`：资源较新返回 412。
3. `If-None-Match`：弱比较，支持列表和 `*`；匹配返回 304。
4. 仅无 If-None-Match 时处理 `If-Modified-Since`：资源未更新返回 304。
5. `If-Range` 决定是否应用 Range，再解析 Range。强标签匹配或可靠 Last-Modified 日期精确匹配才应用；弱标签、坏值、重复字段或不匹配均忽略 Range，返回完整 200。

普通日期条件的无效/重复日期忽略。非法 If-Match 不满足而返回 412，非法 If-None-Match 不命中且抑制日期回退。304/412 优先于非法 Range；If-Range 不匹配也不会因该 Range 非法而返回 416。无 Range 时 If-Range 不影响完整响应。

## 错误与传输中断

业务错误使用 `application/problem+json`、稳定 code、`urn:problem:music:*` 类型、`Cache-Control: no-store`。错误不包含 bucket、对象键、内部路径或底层异常。416 保留 `Content-Range: bytes */N`，其他错误不保留音频响应头。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | trackId 格式或范围非法 |
| 401 | UNAUTHORIZED | 未提供认证 |
| 401 | INVALID_TOKEN | subject、role、tokenVersion 等必要声明非法 |
| 401 | TOKEN_REVOKED | 用户不存在、版本变化或角色被撤销 |
| 404 | TRACK_NOT_FOUND | 歌曲不存在、不可见，或其音频对象已丢失 |
| 412 | PRECONDITION_FAILED | If-Match / If-Unmodified-Since 不满足 |
| 416 | RANGE_NOT_SATISFIABLE | 非法或不可满足的单个 bytes 范围 |
| 500 | INTERNAL_ERROR | 本地音频资产缺失、类型或元信息非法 |
| 502 | AUDIO_STORAGE_INCONSISTENT | HEAD 元信息不一致，或实际 GET 状态/范围/长度/MIME/摘要/内容编码不一致 |
| 503 | MEDIA_STORAGE_UNAVAILABLE | 存储不可用、超时，或响应提交前读取失败（含提前 EOF） |
| 503 | TRACK_STORE_UNAVAILABLE | 业务数据库/事务无法读取 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态存储不可用 |

JWT 签名、过期、issuer/audience 等解码失败沿用既有资源服务器 401 + WWW-Authenticate，可能不含业务 ProblemDetail。本次不调整公共认证契约。无单独 403 分支。

```json
{
  "type": "urn:problem:music:range_not_satisfiable",
  "title": "Range not satisfiable",
  "status": 416,
  "detail": "Audio range is not satisfiable",
  "instance": "/api/v1/tracks/42/stream",
  "code": "RANGE_NOT_SATISFIABLE"
}
```

404 不等同于数据库行必然不存在；音频对象丢失时也返回该码，客户端可提示“歌曲暂不可播放”并刷新列表。对象缺失记录仅含 trackId/traceId 的脱敏 warning。

实际 GET 必须与 HEAD 及本次请求相符：上游忽略 Range 返回 200、返回错误 Content-Range 或在 HEAD/GET 间替换对象，均不会伪装成正确 206。上游对已验证的合法范围返回 416 归为 502，不混淆为客户端 416。

使用 8192 字节缓冲，不完整加载音频到内存或落盘；响应提交前失败清理音频头/缓冲后返回错误。提交后读失败或客户端断开，只中止传输并关闭/取消源流，不追加 JSON，也不能重写已经发出的状态。短于 Content-Length 的响应应视为失败；续传需发起新请求并重新鉴权。取消使用 SDK abort，不排空剩余音频。不自动重试已经开始的传输。

## curl 示例

POSIX shell；ACCESS_TOKEN 来自现有登录流程，ETAG 保存服务端 ETag 完整值（包括双引号）。

```sh
curl 'http://localhost:8080/api/v1/tracks/42/stream' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --dump-header stream.headers --output song.audio

curl 'http://localhost:8080/api/v1/tracks/42/stream' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Range: bytes=0-1023' \
  --dump-header range.headers --output first-1024.bin

curl 'http://localhost:8080/api/v1/tracks/42/stream' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Range: bytes=1024-' --header "If-Range: ${ETAG}" \
  --dump-header resume.headers --output remainder.bin

curl 'http://localhost:8080/api/v1/tracks/42/stream' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "If-None-Match: ${ETAG}" \
  --dump-header conditional.headers --output conditional.body

curl --head 'http://localhost:8080/api/v1/tracks/42/stream' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```

接口只读，无分页或写入幂等键。本次不增加专用 Redis 播放限流；使用有限存储连接池及容器连接/线程并在部署层设置并发保护。生产规模扩大前按 O01/O09 完成认证状态优化与压测，按结果独立设计限流。部署配置见 [歌曲存储部署](../../deployment/song-storage.md)。
