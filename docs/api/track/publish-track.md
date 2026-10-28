# 发布歌曲

## 接口信息

- 方法：`POST`
- 路径：`/api/v1/tracks/{trackId}/publish`
- 权限：已登录且具有 `ADMIN` 角色
- Content-Type：不需要
- 请求 Body：无

本接口将 `DRAFT` 状态歌曲发布为 `PUBLISHED`。只有 `PUBLISHED` 状态歌曲满足未来普通用户歌曲查询的必要可见条件。

## 路径参数

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `trackId` | integer | 是 | 歌曲 ID，必须为正整数 |

## 成功响应

发布成功返回 `200 OK`：

```json
{
  "id": 42,
  "status": "PUBLISHED",
  "version": 4,
  "updatedAt": "2026-10-27T13:20:30Z"
}
```

`updatedAt` 使用 UTC 和 ISO-8601。响应不包含 JWT、用户信息、对象存储 bucket、对象键、原始 ID3 元数据或存储凭据。

发布操作只更新：

- `status`：从 `DRAFT` 更新为 `PUBLISHED`。
- `updatedAt`：更新为发布时间。
- `updatedBy`：更新为执行发布的管理员用户 ID。
- `version`：由 JPA 乐观锁自动递增。

## 状态约束

```text
DRAFT     -> PUBLISHED   允许，返回 200
PUBLISHED -> PUBLISHED   拒绝，返回 409 TRACK_NOT_DRAFT
ARCHIVED  -> PUBLISHED   拒绝，返回 409 TRACK_NOT_DRAFT
```

重复发布不会修改 `version`、`updatedAt` 或 `updatedBy`。

## 并发控制

请求不提供 `version`。实体使用 JPA `@Version` 执行带版本条件的更新，防止并发编辑或发布覆盖其他操作。

如果其他操作先修改歌曲，本接口返回 `409 TRACK_VERSION_CONFLICT`。该请求没有客户端版本号，因此响应不包含 `expectedVersion` 或 `actualVersion`：

```json
{
  "type": "urn:problem:music:track_version_conflict",
  "title": "歌曲版本冲突",
  "status": 409,
  "detail": "歌曲已被其他操作修改，请重新读取后重试",
  "instance": "/api/v1/tracks/42/publish",
  "code": "TRACK_VERSION_CONFLICT"
}
```

客户端应重新读取歌曲后决定是否再次发布。

## 错误响应

所有错误均使用 RFC 9457 ProblemDetail，并包含稳定 `code` 字段。

| 状态码 | 错误码 | 说明 |
| ---: | --- | --- |
| 400 | `VALIDATION_FAILED` | `trackId` 格式无效或不是正整数 |
| 401 | `UNAUTHORIZED` | 需要登录 |
| 403 | `FORBIDDEN` | 需要管理员角色 |
| 404 | `TRACK_NOT_FOUND` | 歌曲不存在 |
| 409 | `TRACK_NOT_DRAFT` | 歌曲不是 `DRAFT` 状态 |
| 409 | `TRACK_VERSION_CONFLICT` | 歌曲已被并发修改 |
| 503 | `TRACK_STORE_UNAVAILABLE` | MySQL 歌曲存储不可用 |

## curl 示例

```bash
curl -X POST 'http://localhost:8080/api/v1/tracks/42/publish' \
  -H 'Authorization: Bearer 你的管理员JWT'
```
