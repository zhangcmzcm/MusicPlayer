# 编辑歌曲草稿

## 接口信息

- 方法：`PATCH`
- 路径：`/api/v1/tracks/{trackId}/draft`
- 权限：已登录且具有 `ADMIN` 角色
- Content-Type：`application/json`

本接口只修改 `DRAFT` 状态歌曲的文本元数据。修改完成后歌曲仍然保持 `DRAFT` 状态，发布由后续独立接口处理。

## 请求字段

| 字段 | 类型 | 必填 | 字段缺失时 | 字段为 `null` 时 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `title` | string | 否 | 保持原值 | 不允许 | 非空，最大 255 字符 |
| `artistName` | string | 否 | 保持原值 | 不允许 | 非空，最大 255 字符 |
| `albumName` | string/null | 否 | 保持原值 | 清空 | 最大 255 字符 |
| `trackNumber` | integer/null | 否 | 保持原值 | 清空 | 提供时必须大于 0 |
| `discNumber` | integer/null | 否 | 保持原值 | 清空 | 提供时必须大于 0 |
| `version` | integer | 是 | 不允许 | 不允许 | 编辑前的歌曲版本号 |

示例请求：

```json
{
  "title": "修正后的标题",
  "artistName": "修正后的艺术家",
  "albumName": null,
  "trackNumber": 3,
  "discNumber": null,
  "version": 3
}
```

字段缺失和显式 `null` 含义不同：

- 字段缺失：该字段不修改。
- 显式 `null`：清空对应的可空字段。
- `title`、`artistName` 显式传入 `null`：返回 `400 VALIDATION_FAILED`。
- 空请求 `{}` 需要携带 `version`，其余字段不变。

## 成功响应

成功修改后返回 `200 OK`：

```json
{
  "id": 42,
  "title": "修正后的标题",
  "artistName": "修正后的艺术家",
  "albumName": null,
  "durationMs": 215430,
  "trackNumber": 3,
  "discNumber": null,
  "version": 4,
  "status": "DRAFT",
  "audio": {
    "id": 51,
    "contentType": "audio/mpeg",
    "sizeBytes": 8230144,
    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  },
  "cover": {
    "id": 52,
    "contentType": "image/jpeg",
    "sizeBytes": 184220,
    "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
  }
}
```

响应不包含 MinIO bucket、对象键或原始 ID3 元数据。

## 乐观锁

每次成功修改后，歌曲 `version` 自动加 1。

客户端必须在请求中携带读取歌曲时获得的版本号。如果数据库中的版本号已经变化，接口返回：

```text
409 TRACK_VERSION_CONFLICT
```

客户端应重新读取歌曲草稿，合并自己的修改后使用最新版本号重试。

## 不可修改字段

本接口不会修改：

- 音频文件。
- 封面文件或封面资产。
- 歌曲时长。
- 原始 ID3 元数据。
- 歌曲状态。
- 创建人和创建时间。

## 错误响应

所有错误均使用 RFC 9457 ProblemDetail，并包含稳定 `code` 字段。

| 状态码 | 错误码 | 说明 |
| ---: | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段、版本号或请求格式无效 |
| 401 | `UNAUTHORIZED` | 需要登录 |
| 403 | `FORBIDDEN` | 需要管理员角色 |
| 404 | `TRACK_NOT_FOUND` | 歌曲不存在 |
| 409 | `TRACK_NOT_DRAFT` | 歌曲不是 `DRAFT` 状态 |
| 409 | `TRACK_VERSION_CONFLICT` | 版本号已过期 |
| 503 | `TRACK_STORE_UNAVAILABLE` | MySQL 歌曲存储不可用 |

## curl 示例

```bash
curl -X PATCH 'http://localhost:8080/api/v1/tracks/42/draft' \
  -H 'Authorization: Bearer 你的管理员JWT' \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "修正后的标题",
    "artistName": "修正后的艺术家",
    "albumName": null,
    "trackNumber": 3,
    "discNumber": null,
    "version": 3
  }'
```
