# 导入歌曲

## 接口信息

- 方法：`POST`
- 路径：`/api/v1/track-imports`
- 权限：已登录且具有 `ADMIN` 角色
- Content-Type：`multipart/form-data`

本接口同步完成音频文件校验、ID3/FLAC 元数据提取、内嵌封面提取、MinIO 媒体对象写入，并创建 `DRAFT` 状态的歌曲草稿。

## 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `uploadId` | UUID 字符串 | 是 | 客户端生成的幂等键 |
| `file` | multipart 文件 | 是 | MP3 或 FLAC 音频文件，最大 200 MiB |

本接口不接收标题、艺术家或专辑覆盖字段。这些信息从音频元数据中提取，由后续草稿编辑接口负责修改。

## 成功响应

首次导入成功返回 `201 Created`：

```json
{
  "importId": 18,
  "uploadId": "36bd81f4-2fa2-4d1c-a7c3-b6423690a3fa",
  "status": "READY",
  "track": {
    "id": 42,
    "title": "示例歌曲",
    "artistName": "示例艺术家",
    "albumName": "示例专辑",
    "durationMs": 215430,
    "trackNumber": 1,
    "discNumber": 1,
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
  },
  "metadataWarnings": []
}
```

当音频没有可用的内嵌封面时，`cover` 为 `null`。

## 幂等重试

成功导入后，如果再次提交相同的 `uploadId`、文件名和 SHA-256，本接口返回 `200 OK` 及已有导入结果。

如果相同 `uploadId` 被用于不同文件内容，本接口返回 `409 IMPORT_CONFLICT`。

失败的导入记录可以使用相同 `uploadId` 和相同文件内容重新处理。如果文件内容不同，则同样返回 `409 IMPORT_CONFLICT`。

## 元数据警告

| 值 | 说明 |
| --- | --- |
| `TITLE_FALLBACK` | 标签缺少标题，使用去除扩展名后的文件名 |
| `ARTIST_FALLBACK` | 标签缺少艺术家，使用 `Unknown Artist` |
| `COVER_MISSING` | 未找到内嵌封面 |
| `COVER_TOO_LARGE` | 内嵌封面超过 20 MiB，未保存封面 |
| `TITLE_TRUNCATED` | 标题超过 255 个字符并被截断 |
| `ARTIST_TRUNCATED` | 艺术家名称超过 255 个字符并被截断 |
| `ALBUM_TRUNCATED` | 专辑名称超过 255 个字符并被截断 |
| `TRACK_NUMBER_INVALID` | 曲序不是正整数 |
| `DISC_NUMBER_INVALID` | 碟片序号不是正整数 |

## 错误响应

所有错误均使用 RFC 9457 ProblemDetail，并包含稳定的 `code` 字段。

| 状态码 | 错误码 | 说明 |
| ---: | --- | --- |
| 400 | `VALIDATION_FAILED` | multipart 参数缺失或格式无效 |
| 401 | `UNAUTHORIZED` | 需要登录 |
| 403 | `FORBIDDEN` | 需要管理员角色 |
| 409 | `IMPORT_CONFLICT` | `uploadId` 已被不同文件内容使用 |
| 409 | `IMPORT_IN_PROGRESS` | 相同导入任务正在处理 |
| 413 | `UPLOAD_TOO_LARGE` | 音频文件超过大小限制 |
| 415 | `UNSUPPORTED_AUDIO_FORMAT` | 扩展名、MIME 或文件魔数不受支持 |
| 422 | `AUDIO_METADATA_INVALID` | 音频损坏或没有有效时长 |
| 429 | `TRACK_IMPORT_RATE_LIMITED` | 管理员导入频率超过限制 |
| 503 | `MEDIA_STORAGE_UNAVAILABLE` | MinIO 暂时不可用 |
| 503 | `MEDIA_IMPORT_STORE_UNAVAILABLE` | MySQL 或导入状态存储不可用 |
| 503 | `MEDIA_OBJECT_CLEANUP_FAILED` | 补偿删除媒体对象失败 |

## curl 示例

导入 MP3：

```bash
curl -X POST 'http://localhost:8080/api/v1/track-imports' \
  -H 'Authorization: Bearer 你的管理员JWT' \
  -F 'uploadId=36bd81f4-2fa2-4d1c-a7c3-b6423690a3fa' \
  -F 'file=@/path/to/song.mp3;type=audio/mpeg'
```

导入 FLAC：

```bash
curl -X POST 'http://localhost:8080/api/v1/track-imports' \
  -H 'Authorization: Bearer 你的管理员JWT' \
  -F 'uploadId=8f14e45f-ceea-467f-a1d7-1b0a4b8f8f6c' \
  -F 'file=@/path/to/song.flac;type=audio/flac'
```

## 注意事项

- 响应不会暴露 MinIO bucket、对象键、临时文件路径或存储凭据。
- 导入成功后的歌曲状态为 `DRAFT`，普通用户不可见。
- MinIO bucket 始终保持私有，只能通过后端存储适配器访问。
- 标题、艺术家和专辑由音频标签提取；缺失信息按照元数据警告规则处理。
