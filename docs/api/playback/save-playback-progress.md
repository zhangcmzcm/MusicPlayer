# 保存本设备播放进度

## 方法、路径与用途

`PUT /api/v1/users/me/playback-progress/{deviceId}`

保存当前登录用户在指定逻辑设备上的最近歌曲与整秒位置。每个用户、每个设备对应一份状态，切歌后保存新歌曲。H02 的读取进度接口另行实施。

## 认证与权限

需要有效 Bearer JWT，用户身份来自服务端认证主体。USER 可保存 PUBLISHED 歌曲，ADMIN 登录角色可保存 DRAFT、PUBLISHED、ARCHIVED。管理员以 USER 登录时遵循 USER 规则。

歌曲不存在或当前登录角色不可见，统一返回 404 `TRACK_NOT_FOUND`。当前账号与设备 UUID 联合定位状态，相同设备 UUID 在不同账号下分别保存。

设备 UUID 是客户端的逻辑分组标识。当前账号认证的持有者可为该账号的指定设备 UUID 保存状态。

## 请求

请求头使用 `Content-Type: application/json`，支持媒体参数，例如 `application/json;charset=UTF-8`。正文由 JSON 字节流解析器读取。

| 字段 | 位置 | 类型与校验 |
| --- | --- | --- |
| deviceId | 路径 | 标准 36 字符 UUID，格式为 8-4-4-4-12 十六进制，接收大小写并规范化为小写 |
| trackId | JSON | 必填整数，1 至 9223372036854775807 |
| positionSeconds | JSON | 必填整数，0 至 9223372036854775807，并执行有效时长上限校验 |
| sequence | JSON | 必填整数，1 至 9007199254740991，同账号同设备持久递增 |

请求体恰好包含三个业务字段，字段顺序自由。缺失、null、重复、未知字段、数字字符串、小数、科学计数法、溢出、数组和尾随 JSON 均返回 400 `VALIDATION_FAILED`。

```json
{
  "trackId": 123,
  "positionSeconds": 83,
  "sequence": 42
}
```

数据库中的 `durationMs > 0` 时，位置上限为 `durationMs / 1000` 向下取整。例如 215430 毫秒允许 0 至 215 秒，900 毫秒允许 0 秒。时长为空或 0 时，允许请求类型范围内的非负整数秒。

## 200 成功响应

首次保存、更新和当前相同事件的重复请求均为 200。响应包含 `Cache-Control: no-store`。

```json
{
  "deviceId": "bcf04f9c-3876-4a77-8f6d-632ebd78db2e",
  "trackId": 123,
  "positionSeconds": 83,
  "sequence": 42,
  "updatedAt": "2026-11-15T00:00:00.123456Z"
}
```

`updatedAt` 是服务端最近保存新事件的 UTC 时间，精确到微秒。相同当前序号、歌曲和位置的重试返回原结果，保留审计字段和更新时间。

## 序号、并发与重试

| 请求序号与已保存序号关系 | 结果 |
| --- | --- |
| 首次保存 | 创建状态，200 |
| 更大 | 保存新事件，200，位置可减小 |
| 相等且歌曲、位置相同 | 幂等成功，200 |
| 相等且歌曲或位置不同 | 409 PLAYBACK_PROGRESS_SEQUENCE_CONFLICT |
| 更小 | 409 PLAYBACK_PROGRESS_STALE_SEQUENCE |

两种 409 均包含 `currentSequence`，其值来自本次事务持锁比较时的记录。后续并发请求可继续推进序号。

网络失败或 503 后，客户端可使用原内容和原序号重试。数据库提交确认失败时，原事件可能已经保存，重试按照相同序号规则处理。若已有更新事件保存成功，旧事件重试返回过期 409。

每次请求都会检查歌曲当前可见性和时长。原歌曲删除或对当前角色隐藏后，原请求重试返回 404。歌曲物理删除将歌曲关联置空并保留设备最后序号，旧号上报其他可见歌曲仍被拒绝。新号可保存新歌曲。账号物理删除时级联清理进度。

## 客户端接入约定

- 首次启动生成随机 UUID，随本地设备数据持久保存。进程重启和重新登录继续使用，清除数据或重装后生成新 UUID。
- 浏览器的逻辑设备以本站点的持久化存储环境区分，不同浏览器或配置文件分别保存 UUID。
- 按当前账号与设备分配并持久保存递增序号，切歌、重启和重新登录继续递增，允许跳号。
- 发送前持久保存事件与序号，重试复用原事件。多个标签页共享 UUID 时原子分配序号，最终按最大的成功保存序号确定状态。
- 正常播放目标为每 5 秒保存，暂停、拖动完成、切歌和自然结束立即产生保存事件。实际送达受后台调度、进程和网络影响。
- 播放位置向下取整为秒。自然结束时保留歌曲并用更大序号保存 0 秒，接近结尾暂停时保存实际整秒位置。
- 收到 409 后丢弃对应旧事件，分配器以本地序号和 currentSequence 较大值为基础，为后续当前有效状态分配新序号。
- 序号达到协议上限时，生成新设备 UUID 并从序号 1 开始。

## 限流与处理顺序

账号所有设备和登录角色共享保存额度，默认从首个有效请求起 60 秒内最多 120 次，配置为 `app.playback-progress.rate-limit-per-minute`，环境变量为 `PLAYBACK_PROGRESS_RATE_LIMIT_PER_MINUTE`。

处理顺序为认证、路径与请求体基础校验、额度检查、数据库内歌曲可见性与时长检查、序号判定、保存与提交。

基础校验通过的尝试消费额度，包括幂等、409、歌曲不可见、时长超限和数据库失败。请求基础校验与媒体类型失败发生在额度消费前。认证授权状态检查发生在保存额度检查前。

429 携带 `Retry-After`，表示当前固定窗口剩余秒数向上取整，至少 1 秒。拒绝请求保持原窗口到期时间。

## 错误响应

业务错误使用 `application/problem+json`，携带稳定 `code` 与 `urn:problem:music:*` 类型。新增业务异常响应包含 `Cache-Control: no-store`。

| HTTP 状态 | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | UUID、请求结构、字段或时长上限校验失败 |
| 401 | UNAUTHORIZED、INVALID_TOKEN、TOKEN_REVOKED | 缺少认证、认证声明错误、账号版本或登录角色失效 |
| 403 | FORBIDDEN | 现有方法授权拒绝访问 |
| 404 | TRACK_NOT_FOUND | 歌曲不存在或不可见 |
| 409 | PLAYBACK_PROGRESS_STALE_SEQUENCE | 请求事件序号较旧，含 currentSequence |
| 409 | PLAYBACK_PROGRESS_SEQUENCE_CONFLICT | 同号对应不同内容，含 currentSequence |
| 415 | UNSUPPORTED_MEDIA_TYPE | 缺少或不支持的 Content-Type |
| 429 | PLAYBACK_PROGRESS_RATE_LIMITED | 账号额度耗尽，含 Retry-After |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 现有认证链无法读取账号授权状态 |
| 503 | PLAYBACK_PROGRESS_RATE_LIMIT_UNAVAILABLE | Redis 额度检查失败或计数状态异常 |
| 503 | PLAYBACK_PROGRESS_STORE_UNAVAILABLE | 数据库访问、锁等待、死锁或事务失败 |
| 500 | INTERNAL_ERROR | 未预期的服务器错误 |

JWT 解码阶段的签名或过期错误沿用资源服务器既有 401 响应。上表的认证声明及撤销错误由现有认证状态过滤器处理。

```json
{
  "type": "urn:problem:music:playback_progress_stale_sequence",
  "title": "播放进度上报已过期",
  "status": 409,
  "detail": "该设备已保存更新的播放状态",
  "instance": "/api/v1/users/me/playback-progress/bcf04f9c-3876-4a77-8f6d-632ebd78db2e",
  "code": "PLAYBACK_PROGRESS_STALE_SEQUENCE",
  "currentSequence": 43
}
```

## 可执行 curl

以下使用 PowerShell 7。通过环境变量 `ACCESS_TOKEN` 提供有效 Token，将歌曲 ID 改为当前账号可见歌曲。示意设备 UUID 在重复请求中保持一致。

```powershell
curl.exe --include --request PUT `
  "http://localhost:8080/api/v1/users/me/playback-progress/bcf04f9c-3876-4a77-8f6d-632ebd78db2e" `
  --header "Authorization: Bearer $env:ACCESS_TOKEN" `
  --header "Content-Type: application/json" `
  --data-raw '{"trackId":123,"positionSeconds":83,"sequence":42}'
```
