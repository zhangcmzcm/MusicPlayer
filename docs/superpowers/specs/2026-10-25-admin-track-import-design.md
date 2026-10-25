# 管理员音频上传与导入接口设计

日期：2026-10-25

状态：已确认

## 1. 目标与范围

本次只实现一个 HTTP 接口：

- `POST /api/v1/track-imports`

接口允许管理员上传 MP3 或 FLAC 音频，完成文件校验、SHA-256 计算、ID3/FLAC 元数据提取、内嵌封面提取、MinIO 写入，并创建 `DRAFT` 歌曲草稿。

本次覆盖：

- `media_imports` 的持久化实体、Repository 和状态编排。
- MP3、FLAC 标签、时长和内嵌封面提取。
- 音频和封面媒体资产创建。
- 同步导入的幂等、失败补偿、权限、限流和错误处理。
- 单元测试、集成测试、OpenAPI 和 API 文档。

本次不包含：

- 修改歌曲草稿。
- 确认或发布歌曲。
- 歌曲详情、列表或播放接口。
- 外部封面上传。
- 转码、歌词、搜索、艺术家或专辑管理。
- 异步导入状态查询接口。

## 2. 已确认决策

- 使用同步单接口导入，不返回 `202`，不增加状态查询接口。
- 请求只包含客户端生成的 `uploadId` 和音频 `file`。
- 标题、艺术家和专辑只从音频标签提取，当前接口不接受覆盖字段。
- 初始格式仅支持 MP3、FLAC。
- 音频最大 200 MiB，可由 `UPLOAD_MAX_AUDIO_BYTES` 调整。
- 标题缺失时使用文件名，不包含扩展名。
- 艺术家缺失时使用 `Unknown Artist`。
- 专辑缺失时保持为空。
- 封面缺失时保持为空，并返回警告。
- 只有可解析出有效时长的音频才能导入。
- 文件导入使用受控本地临时文件，不把完整音频加载进 JVM 堆。
- MinIO 对象写入后不在原位置修改。
- 成功导入生成 `DRAFT` 歌曲，不对普通用户可见。

## 3. HTTP 契约

### 3.1 请求

- 方法：`POST`
- 路径：`/api/v1/track-imports`
- 权限：已认证且具有 `ADMIN` 角色
- Content-Type：`multipart/form-data`

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `uploadId` | UUID 字符串 | 是 | 客户端生成，作为导入幂等键 |
| `file` | multipart 文件 | 是 | MP3 或 FLAC，最大 200 MiB |

请求不包含标题、艺术家、专辑或其他歌曲编辑字段。

### 3.2 成功响应

首次成功导入返回 `201 Created`：

```json
{
  "importId": 18,
  "uploadId": "36bd81f4-2fa2-4d1c-a7c3-b6423690a3fa",
  "status": "READY",
  "track": {
    "id": 42,
    "title": "Example Song",
    "artistName": "Example Artist",
    "albumName": "Example Album",
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

`cover` 可以为 `null`。响应不包含 bucket、对象键、临时文件路径、MinIO 凭据或 JWT。

### 3.3 元数据警告

`metadataWarnings` 使用稳定枚举值：

| 值 | 含义 |
| --- | --- |
| `TITLE_FALLBACK` | 标签没有标题，使用文件名 |
| `ARTIST_FALLBACK` | 标签没有艺术家，使用 `Unknown Artist` |
| `COVER_MISSING` | 音频没有内嵌封面 |
| `COVER_TOO_LARGE` | 内嵌封面超过 20 MiB，未保存 |
| `TITLE_TRUNCATED` | 标题超过 255 字符并被截断 |
| `ARTIST_TRUNCATED` | 艺术家超过 255 字符并被截断 |
| `ALBUM_TRUNCATED` | 专辑超过 255 字符并被截断 |
| `TRACK_NUMBER_INVALID` | 曲序不是正整数，保存为空 |
| `DISC_NUMBER_INVALID` | 碟片序号不是正整数，保存为空 |

### 3.4 幂等响应

同一个 `uploadId` 的处理规则：

| 已有状态 | 文件摘要和文件名 | 行为 |
| --- | --- | --- |
| `READY` | 一致 | 返回已有草稿，HTTP `200` |
| `READY` | 不一致 | `409 IMPORT_CONFLICT` |
| `FAILED` | 一致 | 使用原导入记录重新处理 |
| `FAILED` | 不一致 | `409 IMPORT_CONFLICT` |
| `UPLOADING`、`VALIDATING`、`EXTRACTING` | 一致或不一致 | `409 IMPORT_IN_PROGRESS` |

数据库 `media_imports.upload_id` 唯一约束负责阻止并发重复创建。

### 3.5 错误响应

所有错误使用 RFC 9457 ProblemDetail，并包含稳定 `code`。

| 场景 | 状态码 | 错误码 |
| --- | ---: | --- |
| 参数缺失、UUID 无效 | `400` | `VALIDATION_FAILED` |
| 未认证 | `401` | `UNAUTHORIZED` |
| 非管理员 | `403` | `FORBIDDEN` |
| 相同 `uploadId` 用于不同文件 | `409` | `IMPORT_CONFLICT` |
| 相同导入正在处理 | `409` | `IMPORT_IN_PROGRESS` |
| 文件超过配置上限 | `413` | `UPLOAD_TOO_LARGE` |
| 扩展名、MIME 或魔数不支持 | `415` | `UNSUPPORTED_AUDIO_FORMAT` |
| 音频损坏或没有有效时长 | `422` | `AUDIO_METADATA_INVALID` |
| 导入限流 | `429` | `TRACK_IMPORT_RATE_LIMITED`，响应包含 `Retry-After` |
| MinIO 不可用 | `503` | `MEDIA_STORAGE_UNAVAILABLE` |
| MySQL 或导入状态存储不可用 | `503` | `MEDIA_IMPORT_STORE_UNAVAILABLE` |
| 补偿删除对象失败 | `503` | `MEDIA_OBJECT_CLEANUP_FAILED` |

## 4. 文件与元数据规则

### 4.1 文件校验

校验顺序：

1. `uploadId` 必须是合法 UUID。
2. `file` 必须存在且非空。
3. 文件大小不得超过 `UPLOAD_MAX_AUDIO_BYTES`。
4. 扩展名必须是 `.mp3` 或 `.flac`。
5. 声明 MIME 必须与格式相符：
   - MP3：`audio/mpeg`
   - FLAC：`audio/flac` 或 `audio/x-flac`
6. 文件魔数必须匹配：
   - MP3：ID3 标签头或 MPEG 音频帧头。
   - FLAC：`fLaC`。
7. 音频解析器必须能识别实际格式并读取正数时长。

文件名只用于展示和标题回退，不参与对象路径拼接，防止路径穿越。

### 4.2 元数据映射

| 来源字段 | 歌曲字段 | 处理 |
| --- | --- | --- |
| 标签标题 | `title` | 空白时使用文件名；最大 255 字符 |
| 标签艺术家 | `artist_name` | 空白时使用 `Unknown Artist`；最大 255 字符 |
| 标签专辑 | `album_name` | 空白时保持为空；最大 255 字符 |
| 音频时长 | `duration_ms` | 必须大于 0 |
| 标签曲序 | `track_number` | 非正整数时保持为空 |
| 标签碟片序号 | `disc_number` | 非正整数时保持为空 |
| 内嵌封面 | `cover_asset_id` | 最大 20 MiB；超限时不保存 |

原始可序列化标签保存到 `tracks.id3_metadata` JSON。封面二进制内容不保存在 JSON 或数据库中。

## 5. 数据处理流程

### 5.1 方案选择

采用“受控临时文件优先”：

| 方案 | 结论 |
| --- | --- |
| 受控临时文件优先 | 采用：内存稳定、解析器兼容、MinIO 写入前可完整验证 |
| MinIO 优先再读回解析 | 不采用：增加完整网络读取，失败补偿更复杂 |
| multipart 流直接解析 | 不采用：解析库通常需要可定位文件，边读边解析不可靠 |

### 5.2 同步处理步骤

1. Controller 校验 multipart 参数并取得管理员 ID。
2. Service 检查 `uploadId` 幂等状态。
3. 创建或重置 `media_imports`，状态为 `UPLOADING`。
4. `TemporaryImportFileStore` 创建受控临时音频文件。
5. 流式复制上传内容并计算 SHA-256。
6. 校验大小、扩展名、MIME、魔数和实际格式，状态更新为 `VALIDATING`。
7. `AudioMetadataReader` 提取标签、时长和封面，状态更新为 `EXTRACTING`。
8. 应用字段回退、截断和警告规则。
9. 将音频和可选封面上传到 MinIO。
10. 在一个数据库事务中创建 `media_assets`、`tracks`，并把 `media_imports` 更新为 `READY`。
11. 删除临时文件并返回响应。

MinIO 对象键由后端生成：

```text
audio/{uploadId}/{sha256}.{format}
covers/{uploadId}/{sha256}.{extension}
```

对象键包含内部 `uploadId`，但响应不返回对象键。

## 6. 持久化

本次使用现有 `V2__create_song_storage_tables.sql`，不修改已执行迁移。

### 6.1 `media_imports`

保存：

- `upload_id`
- `status`
- `original_filename`
- `content_type`
- `size_bytes`
- `sha256`
- `track_id`
- `failure_code`
- `failure_reason`
- 创建、更新时间和管理员 ID

状态仅使用：

```text
UPLOADING → VALIDATING → EXTRACTING → READY
                                  └──→ FAILED
```

### 6.2 `media_assets`

音频和封面各创建一条记录：

- `AUDIO`：音频对象。
- `COVER`：可选封面对象。
- bucket、对象键、内容类型、原始文件名、大小和 SHA-256。

### 6.3 `tracks`

创建 `DRAFT` 歌曲，保存：

- 回退后的标题、艺术家、专辑。
- 时长、曲序、碟片序号。
- 音频和可选封面资产 ID。
- 原始标签 JSON。
- 创建、更新时间和管理员 ID。

### 6.4 最终事务

最终事务只执行：

1. 插入音频 `media_assets`。
2. 插入可选封面 `media_assets`。
3. 插入 `tracks`。
4. 更新 `media_imports` 为 `READY` 并关联 `track_id`。

数据库事务不包含文件复制、元数据解析或 MinIO 网络请求。

## 7. 组件边界

| 组件 | 职责 |
| --- | --- |
| `TrackImportController` | HTTP、multipart 参数、OpenAPI、响应 DTO |
| `MediaImportService` | 幂等、状态编排、文件校验、MinIO 和补偿 |
| `TrackImportTransactionService` | 最终数据库事务 |
| `AudioMetadataReader` | 标签、时长和封面提取抽象 |
| `JaudiotaggerAudioMetadataReader` | 使用 `jaudiotagger` 解析 MP3、FLAC |
| `TemporaryImportFileStore` | 受控临时音频和封面文件生命周期 |
| `TrackImportRateLimiter` | 管理员维度导入限流 |
| `MediaImportRepository` | `media_imports` 数据访问 |
| `MediaAssetRepository` | `media_assets` 数据访问 |
| `TrackRepository` | `tracks` 数据访问 |
| `AudioStorage` | MinIO 对象上传和删除 |
| `GlobalExceptionHandler` | 稳定错误码和 RFC 9457 映射 |

Controller 不处理导入业务；Service 不返回 JPA Entity；Repository 不处理权限。

## 8. 安全与限制

- 使用 `@PreAuthorize("hasRole('ADMIN')")` 和方法级授权。
- 每个管理员每分钟最多 10 次导入请求。
- multipart 文件上限 200 MiB，请求体上限 205 MiB。
- 提取封面上限 20 MiB。
- bucket 保持私有，不返回对象键。
- 临时文件目录可通过 `IMPORT_TEMP_DIR` 配置，默认位于系统临时目录下。
- 日志只记录 `importId`、`uploadId`、`trackId`、状态、耗时和错误码。
- 不记录文件内容、MinIO Secret、JWT 或完整 multipart 请求。

## 9. 失败与补偿

| 失败点 | 处理 |
| --- | --- |
| multipart 在进入 Service 前被容器拒绝 | 不创建 `media_imports`，返回 `400` 或 `413` |
| `uploadId` 无效或缺少文件 | 不创建 `media_imports`，返回 `400 VALIDATION_FAILED` |
| 大小、扩展名、MIME 或魔数校验失败 | 创建或重置的导入记录标记为 `FAILED`，返回 `413` 或 `415` |
| 临时文件写入失败 | 删除未完成临时文件 |
| 元数据提取失败 | 删除临时文件，状态 `FAILED`，错误码 `AUDIO_METADATA_INVALID` |
| 音频上传 MinIO 失败 | 删除已上传对象和临时文件，导入标记为 `FAILED MEDIA_STORAGE_UNAVAILABLE` |
| 封面上传失败 | 删除音频对象和临时文件，导入标记为 `FAILED MEDIA_STORAGE_UNAVAILABLE` |
| 最终数据库事务失败 | 删除 MinIO 对象和临时文件；数据库可用时以独立事务标记 `FAILED MEDIA_IMPORT_STORE_UNAVAILABLE` |
| 补偿删除失败 | 状态保持 `FAILED`，记录 `MEDIA_OBJECT_CLEANUP_FAILED`；数据库不可用时只记录应用日志和指标 |
| 数据库提交成功后临时文件删除失败 | 歌曲不回滚，记录清理警告 |

## 10. 可观测性

关键日志字段：

- `importId`
- `uploadId`
- `trackId`
- `status`
- `failureCode`
- `durationMs`
- `traceId`

关键指标：

- 导入请求数、成功数、失败数。
- 音频字节数和封面字节数。
- 文件校验、元数据提取、MinIO 上传、数据库提交耗时。
- 幂等命中数、冲突数、限流数。
- 补偿删除成功数和失败数。

## 11. 依赖

新增：

- `net.jthink:jaudiotagger:3.0.1`

用途：解析 MP3 ID3、FLAC 标签、音频时长和内嵌封面。

选择理由：覆盖当前 MP3、FLAC 范围，比 Apache Tika 依赖轻。该依赖只由 `JaudiotaggerAudioMetadataReader` 使用，不泄漏到 Controller、DTO、Service 事务层或数据库模型。

## 12. 测试计划

### 12.1 Controller 测试

- 管理员导入成功返回 `201`。
- 相同 `uploadId` 和摘要重试返回 `200`。
- 相同 `uploadId` 不同摘要返回 `409 IMPORT_CONFLICT`。
- 正在处理的 `uploadId` 返回 `409 IMPORT_IN_PROGRESS`。
- 缺少文件或 UUID 无效返回 `400`。
- 未登录返回 `401`。
- 普通用户返回 `403`。
- 文件超过上限返回 `413`。
- 扩展名、MIME 或魔数不匹配返回 `415`。
- 音频损坏或没有有效时长返回 `422`。
- 超过限流返回 `429`。
- MinIO 不可用返回 `503`。

### 12.2 服务测试

- MP3、FLAC 标签、时长和封面提取。
- 标题、艺术家、封面缺失时的回退和警告。
- 字段超长截断和无效曲序处理。
- 临时文件成功或失败后都会清理。
- MinIO 音频上传失败时没有遗留封面对象。
- 数据库失败时删除 MinIO 对象。
- 补偿删除失败时记录稳定错误码。
- 并发相同 `uploadId` 只创建一个歌曲草稿。

### 12.3 集成测试

- MySQL Testcontainers 验证 `media_imports`、`media_assets`、`tracks` 事务。
- MinIO Testcontainers 验证音频和封面对象。
- 使用真实的小型 MP3、FLAC 测试样本，不 mock 音频解析器。
- 测试命名使用 `should_<result>_when_<condition>`。

## 13. API 文档

新增：

- `docs/api/track/import-track.md`

文档必须包含：

- 方法、路径和权限。
- multipart 字段。
- 成功和幂等响应。
- 所有错误码。
- 元数据警告码。
- 可执行 curl 示例。

OpenAPI 使用 `@Operation`、`@ApiResponse` 和请求/响应 Schema，不暴露 bucket 或对象键。

## 14. 已确认决策

- 只实现 `POST /api/v1/track-imports`。
- 管理员同步上传一个 MP3 或 FLAC 文件。
- 请求包含客户端生成的 `uploadId` 和 `file`。
- 标题、艺术家、专辑由音频标签提取，不允许本次覆盖。
- 标题回退到文件名，艺术家回退到 `Unknown Artist`。
- 封面缺失不影响导入。
- 相同 `uploadId` 和相同内容返回已有成功结果。
- 相同 `uploadId` 不同内容返回 `409`。
- 受控临时文件用于校验、摘要和元数据解析。
- MinIO 写入成功后，在单个数据库事务中创建草稿和媒体资产。
- 成功结果是 `DRAFT`，不自动发布。
- `jaudiotagger:3.0.1` 只用于音频元数据解析。
