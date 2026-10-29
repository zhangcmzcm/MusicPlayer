# 删除归档歌曲

## 接口信息

- 方法：`DELETE`
- 路径：`/api/v1/tracks/{trackId}`
- 权限：已登录且具有 `ADMIN` 角色
- Content-Type：不需要
- 请求 Body：无

本接口物理删除 `ARCHIVED` 状态歌曲。只有已归档歌曲可以删除，`DRAFT` 或 `PUBLISHED` 歌曲必须先归档。

## 路径参数

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `trackId` | integer | 是 | 歌曲 ID，必须为正整数 |

## 成功响应

删除成功返回 `204 No Content`，没有响应体。

成功后：

- `tracks` 歌曲记录被删除。
- 歌曲引用的音频和封面 `media_assets` 记录被删除。
- MinIO/S3 中对应音频和封面对象被删除。
- `media_imports` 导入历史保留，`track_id` 变为 `NULL`。

## 状态约束

```text
ARCHIVED  -> 删除成功   返回 204
DRAFT     -> 删除失败   返回 409 TRACK_NOT_ARCHIVED
PUBLISHED -> 删除失败   返回 409 TRACK_NOT_ARCHIVED
已删除    -> 再次删除   返回 404 TRACK_NOT_FOUND
```

## 删除范围

物理删除：

- 歌曲数据库记录。
- 音频媒体资产记录。
- 封面媒体资产记录（如有）。
- MinIO/S3 音频对象。
- MinIO/S3 封面对象（如有）。

保留：

- `media_imports` 导入历史。
- `media_imports.track_id` 由数据库外键规则设置为 `NULL`。
- 用户和其他审计数据。

MinIO/S3 对象不存在视为删除成功，保证删除请求可以安全重试。

## 失败与重试

删除采用先清理对象、后删除数据库记录的顺序：

1. 先删除 MinIO/S3 音频和封面对象。
2. 对象全部删除或视为已删除后，在数据库事务中删除歌曲和媒体资产记录。
3. 如果对象删除失败，数据库保持不变，可以稍后重试。
4. 如果数据库删除失败，事务回滚并保持数据库记录，可以重试完成删除。
5. 重试时不存在的 MinIO/S3 对象不会导致失败。

## 错误响应

所有错误均使用 RFC 9457 ProblemDetail，并包含稳定 `code` 字段。

| 状态码 | 错误码 | 说明 |
| ---: | --- | --- |
| 400 | `VALIDATION_FAILED` | `trackId` 格式无效或不是正整数 |
| 401 | `UNAUTHORIZED` | 需要登录 |
| 403 | `FORBIDDEN` | 需要管理员角色 |
| 404 | `TRACK_NOT_FOUND` | 歌曲不存在或已经删除 |
| 409 | `TRACK_NOT_ARCHIVED` | 歌曲不是 `ARCHIVED` 状态 |
| 503 | `TRACK_MEDIA_CLEANUP_FAILED` | MinIO/S3 媒体对象删除失败 |
| 503 | `TRACK_DELETE_FAILED` | 数据库删除失败 |
| 503 | `TRACK_STORE_UNAVAILABLE` | 歌曲或媒体资产读取失败 |

非归档歌曲响应示例：

```json
{
  "type": "urn:problem:music:track_not_archived",
  "title": "歌曲未归档",
  "status": 409,
  "detail": "只有 ARCHIVED 状态的歌曲可以删除",
  "instance": "/api/v1/tracks/42",
  "code": "TRACK_NOT_ARCHIVED"
}
```

媒体清理失败响应示例：

```json
{
  "type": "urn:problem:music:track_media_cleanup_failed",
  "title": "歌曲媒体清理失败",
  "status": 503,
  "detail": "歌曲媒体对象无法删除，请稍后重试",
  "instance": "/api/v1/tracks/42",
  "code": "TRACK_MEDIA_CLEANUP_FAILED"
}
```

数据库删除失败响应示例：

```json
{
  "type": "urn:problem:music:track_delete_failed",
  "title": "歌曲删除失败",
  "status": 503,
  "detail": "歌曲记录无法删除，请稍后重试",
  "instance": "/api/v1/tracks/42",
  "code": "TRACK_DELETE_FAILED"
}
```

## curl 示例

```bash
curl -X DELETE 'http://localhost:8080/api/v1/tracks/42' \
  -H 'Authorization: Bearer 你的管理员JWT'
```
