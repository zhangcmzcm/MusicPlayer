# 归档歌曲

## 接口信息

- 方法：`POST`
- 路径：`/api/v1/tracks/{trackId}/archive`
- 权限：已登录且具有 `ADMIN` 角色
- Content-Type：不需要
- 请求 Body：无

本接口将歌曲设置为 `ARCHIVED`，停止通过普通用户歌曲入口继续对外提供。归档是逻辑下线，不会删除数据库记录、音频、封面或 MinIO/S3 对象。

## 路径参数

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `trackId` | integer | 是 | 歌曲 ID，必须为正整数 |

## 状态约束

```text
DRAFT     -> ARCHIVED   允许，返回 200
PUBLISHED -> ARCHIVED   允许，返回 200
ARCHIVED  -> ARCHIVED   拒绝，返回 409 TRACK_ALREADY_ARCHIVED
```

归档成功时只更新：

- `status`：更新为 `ARCHIVED`。
- `updatedAt`：更新为归档时间。
- `updatedBy`：更新为执行归档的管理员用户 ID。
- `version`：由 JPA 乐观锁自动递增。

创建信息、音频、封面、时长、ID3 元数据和文本元数据保持不变。

## 成功响应

归档成功返回 `200 OK`：

```json
{
  "id": 42,
  "status": "ARCHIVED",
  "version": 5,
  "updatedAt": "2026-10-29T10:30:00Z"
}
```

`updatedAt` 使用 UTC 和 ISO-8601。响应不包含 JWT、用户信息、对象存储 bucket、对象键、原始 ID3 元数据或存储凭据。

## 归档后的数据处理

归档后：

- `tracks` 数据库记录保留。
- `media_assets` 数据库记录保留。
- MinIO/S3 中的音频和封面对象保留。
- 普通用户歌曲列表、详情、搜索和播放必须排除 `DRAFT` 与 `ARCHIVED`。
- 管理员仍可通过管理接口查看归档歌曲。
- 物理删除由后续独立的管理员删除接口处理，不属于本接口。

## 并发控制

请求不提供 `version`。实体使用 JPA `@Version` 执行带版本条件的更新，防止并发编辑或归档覆盖其他操作。

如果其他操作先修改歌曲，本接口返回 `409 TRACK_VERSION_CONFLICT`。该请求没有客户端版本号，因此响应不包含 `expectedVersion` 或 `actualVersion`：

```json
{
  "type": "urn:problem:music:track_version_conflict",
  "title": "歌曲版本冲突",
  "status": 409,
  "detail": "歌曲已被其他操作修改，请重新读取后重试",
  "instance": "/api/v1/tracks/42/archive",
  "code": "TRACK_VERSION_CONFLICT"
}
```

客户端应重新读取歌曲后决定是否再次归档。

## 重复归档

重复归档返回 `409 TRACK_ALREADY_ARCHIVED`，且不会修改版本和审计字段：

```json
{
  "type": "urn:problem:music:track_already_archived",
  "title": "歌曲已归档",
  "status": 409,
  "detail": "歌曲已经处于 ARCHIVED 状态",
  "instance": "/api/v1/tracks/42/archive",
  "code": "TRACK_ALREADY_ARCHIVED"
}
```

## 错误响应

所有错误均使用 RFC 9457 ProblemDetail，并包含稳定 `code` 字段。

| 状态码 | 错误码 | 说明 |
| ---: | --- | --- |
| 400 | `VALIDATION_FAILED` | `trackId` 格式无效或不是正整数 |
| 401 | `UNAUTHORIZED` | 需要登录 |
| 403 | `FORBIDDEN` | 需要管理员角色 |
| 404 | `TRACK_NOT_FOUND` | 歌曲不存在 |
| 409 | `TRACK_ALREADY_ARCHIVED` | 歌曲已经处于 `ARCHIVED` 状态 |
| 409 | `TRACK_VERSION_CONFLICT` | 歌曲已被并发修改 |
| 503 | `TRACK_STORE_UNAVAILABLE` | MySQL 歌曲存储不可用 |

## curl 示例

```bash
curl -X POST 'http://localhost:8080/api/v1/tracks/42/archive' \
  -H 'Authorization: Bearer 你的管理员JWT'
```
