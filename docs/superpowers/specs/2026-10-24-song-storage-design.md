# 歌曲存储设计

日期：2026-10-24

状态：已确认

## 1. 目标与范围

本设计确定歌曲二进制文件、歌曲元数据和播放读取方式，为后续歌曲信息获取接口和歌曲播放接口提供稳定的数据基础。

本设计覆盖：

- 管理员上传 MP3 和 FLAC 音频文件后的存储方式。
- 音频、封面和歌曲元数据的职责边界。
- ID3 元数据自动提取和管理员确认流程。
- MySQL 与 MinIO 之间的最终一致性处理。
- HTTP Range 流式播放的数据读取方式。
- 安全、备份、可观测性和测试要求。

本设计不直接实现任何 HTTP 接口。歌曲上传、歌曲详情、歌曲列表和歌曲播放必须在后续按单接口工作流分别实现。

以下内容不在本次范围内：

- 歌曲转码和多码率播放。
- 歌词、评论、推荐和搜索。
- CDN、公网对象存储和跨区域复制。
- 艺术家和专辑管理接口。
- 歌曲上传 HTTP 接口的最终请求和响应契约。

## 2. 已确认背景

- 项目是个人音乐播放器后端，不是大规模商业平台。
- 歌曲文件由管理员上传，初始支持 MP3 和 FLAC。
- Spring Boot API、MySQL、Redis 和 MinIO 全部部署在无公网 IP 的服务器。
- 阿里云服务器只负责 TLS 和反向代理，不保存核心数据。
- 歌曲信息和歌曲播放都需要登录。
- 管理员上传、确认和发布歌曲需要 `ADMIN` 角色。
- 客户端通过后端播放接口访问歌曲，MinIO 不直接对客户端开放。
- 上传后自动提取 ID3 信息，管理员确认或修改后才能发布。

## 3. 方案选择

### 3.1 候选方案

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| 私有 MinIO + MySQL 元数据 | 支持流式和 Range 读取，备份边界清晰，可平滑迁移到 S3 兼容服务 | 增加 MinIO 和 S3 SDK | 采用 |
| 本地文件系统 + MySQL 元数据 | 组件少，初始实现简单 | 绑定单机磁盘，迁移、恢复和多实例部署复杂 | 不采用 |
| MySQL BLOB | 只有一个存储组件 | 数据库膨胀，备份慢，流式和 Range 实现复杂 | 不采用 |

### 3.2 核心决策

- MySQL 是歌曲业务数据的唯一事实来源。
- MinIO 只保存不可变的二进制对象。
- 业务层通过 `AudioStorage` 接口访问对象存储，不依赖 MinIO 专用 API。
- MinIO 适配器使用 AWS S3 SDK，未来可替换为阿里云 OSS 等 S3 兼容服务。
- Redis 只用于验证码、限流或可选缓存，不保存歌曲事实数据。

## 4. 总体架构

```text
管理员/客户端
    │ HTTPS
    ▼
阿里云反向代理
    │ 隧道或安全连接
    ▼
Spring Boot API ─────────► MySQL：歌曲、媒体资产、导入状态
    │
    ├─────────────────────► MinIO：音频、封面
    └─────────────────────► Redis：验证码、限流、可选缓存
```

组件职责：

| 组件 | 职责 |
| --- | --- |
| Spring Boot API | 认证、授权、元数据管理、上传编排、Range 代理 |
| MySQL | 歌曲业务数据、对象引用、状态、审计和导入结果 |
| MinIO | 音频和封面对象，私有 bucket |
| Redis | 非事实性缓存、限流或会话辅助 |
| 阿里云反向代理 | TLS、域名和请求转发，不承载歌曲业务 |

## 5. 数据模型

### 5.1 `tracks`

`tracks` 表保存可检索的歌曲业务信息：

| 字段 | 含义 |
| --- | --- |
| `id` | `BIGINT` 自增 ID，与当前 `users.id` 类型一致 |
| `title` | 歌曲标题 |
| `artist_name` | 主艺术家名称 |
| `album_name` | 可选专辑名称 |
| `duration_ms` | 歌曲时长，单位毫秒 |
| `track_number` | 可选专辑曲序 |
| `disc_number` | 可选碟片序号 |
| `audio_asset_id` | 主音频媒体资产 ID |
| `cover_asset_id` | 可选封面媒体资产 ID |
| `id3_metadata` | 自动提取的原始标签 JSON，仅用于追溯 |
| `status` | `DRAFT`、`PUBLISHED`、`ARCHIVED` |
| `created_at` | UTC 创建时间 |
| `updated_at` | UTC 更新时间 |
| `created_by` | 创建用户 ID |
| `updated_by` | 最后修改用户 ID |

标题、艺术家、专辑、时长等可检索信息必须保存在结构化列中，不能只保存在 `id3_metadata` 中。

ID3 自动提取的艺术家和专辑先以字符串保存，避免歌曲导入依赖尚未设计的艺术家和专辑管理模块。未来建立艺术家和专辑实体时，可通过独立迁移把名称关联迁移为 ID 关联；媒体资产设计不因此改变。

### 5.2 `media_assets`

`media_assets` 表描述 MinIO 中的不可变对象：

| 字段 | 含义 |
| --- | --- |
| `id` | `BIGINT` 自增 ID |
| `kind` | `AUDIO` 或 `COVER` |
| `bucket` | MinIO bucket 名称 |
| `object_key` | MinIO 对象键 |
| `content_type` | 如 `audio/mpeg`、`image/jpeg` |
| `original_filename` | 管理员上传时的原始文件名 |
| `size_bytes` | 对象字节数 |
| `sha256` | 文件 SHA-256 摘要 |
| `created_at` | UTC 创建时间 |
| `created_by` | 创建用户 ID |

约束：

- `bucket` 与 `object_key` 组合唯一。
- `sha256` 必须是完整的 64 位十六进制字符串。
- `size_bytes` 必须大于零。
- 媒体对象写入后不原地修改。
- 替换音频或封面时创建新的 `media_assets` 记录，然后更新 `tracks` 引用。

### 5.3 `media_imports`

`media_imports` 表记录上传和元数据提取过程：

| 字段 | 含义 |
| --- | --- |
| `id` | `BIGINT` 自增 ID |
| `upload_id` | 客户端为每次导入生成的 UUID，作为唯一导入幂等键 |
| `status` | `UPLOADING`、`VALIDATING`、`EXTRACTING`、`READY`、`FAILED` |
| `original_filename` | 原始文件名 |
| `content_type` | 声明的文件类型 |
| `size_bytes` | 已接收字节数 |
| `sha256` | 文件摘要 |
| `track_id` | 导入成功后创建的歌曲 ID |
| `failure_code` | 稳定失败错误码 |
| `failure_reason` | 面向管理员的失败说明，不保存敏感信息 |
| `created_at` | UTC 创建时间 |
| `updated_at` | UTC 更新时间 |
| `created_by` | 发起导入的管理员 ID |

`upload_id` 必须唯一。重复提交同一 `upload_id` 时返回已有导入状态，不重复创建对象和歌曲。

## 6. MinIO 对象布局

创建两个私有 bucket：

```text
music-audio/
  audio/{uploadId}/{sha256}.{format}

music-covers/
  covers/{uploadId}/{sha256}.{extension}
```

规则：

- `uploadId` 是客户端为每次导入生成的 UUID，不作为歌曲等公开 API 资源 ID。
- 对象键由后端生成，客户端不能指定 bucket 或完整对象键。
- 对象键包含 `sha256`，用于完整性核对和问题排查。
- bucket 禁止匿名读取和匿名写入。
- MinIO 管理端口不通过阿里云反向代理暴露。
- 初始音频格式仅允许 MP3 和 FLAC；初始封面格式仅允许 JPEG、PNG 和 WebP。
- 默认音频文件上限为 200 MiB，封面文件上限为 20 MiB；两个上限通过配置调整。
- 初始设计不允许 bucket 版本化；不可变对象键和数据库软删除足以满足当前需求。
- 删除数据库引用后仍存在的对象由清理任务处理。

## 7. 上传与元数据流程

### 7.1 状态流

```text
UPLOADING → VALIDATING → EXTRACTING → READY
                                  └──→ FAILED
```

`READY` 表示音频对象、媒体资产和 `DRAFT` 歌曲记录均已创建。只有管理员确认后的 `PUBLISHED` 歌曲对普通用户可见。

### 7.2 处理步骤

1. 管理员提交音频和 `uploadId`。
2. 后端创建 `media_imports` 记录，状态为 `UPLOADING`。
3. 后端校验文件大小、扩展名、MIME 和文件魔数。
4. 后端流式写入 MinIO，同时计算 SHA-256，不把完整音频加载进 JVM 堆内存。
5. 写入完成后将状态更新为 `VALIDATING`，核对文件大小、摘要和实际音频格式。
6. 提取标题、艺术家、专辑、时长、曲序和内嵌封面，状态更新为 `EXTRACTING`。
7. 在一个数据库事务中创建 `media_assets`、`tracks` 草稿，并将导入状态更新为 `READY`。
8. 管理员确认或修改提取结果后，将歌曲状态从 `DRAFT` 更新为 `PUBLISHED`。

上传和元数据提取不能占用数据库长事务。数据库事务只覆盖数据库状态和记录写入。

### 7.3 失败与补偿

MySQL 与 MinIO 无法进行原子事务，因此采用补偿处理：

- 上传中断：删除不完整对象，并将导入标记为 `FAILED`。
- 格式或魔数验证失败：删除对象，并记录稳定失败错误码。
- 元数据提取失败：保留失败原因，歌曲不得发布。
- 最终数据库事务失败：异步删除已写入对象。
- 补偿删除失败：保留待清理记录，由定时任务重试。
- 定时任务删除超过 24 小时且没有 `media_assets` 引用的对象。
- 服务重启后，`UPLOADING`、`VALIDATING` 和 `EXTRACTING` 状态统一标记为 `FAILED`，错误码为 `IMPORT_INTERRUPTED`，并清理未确认对象；客户端需要使用新的 `uploadId` 重新导入。

## 8. 歌曲获取与 Range 播放

后续歌曲读取分为两个关注点：

- 歌曲信息读取：从 MySQL 获取歌曲业务字段。
- 歌曲流读取：根据 `audio_asset_id` 从 MinIO 读取对象。

歌曲流数据流：

1. 校验用户已登录。
2. 校验歌曲存在且状态为 `PUBLISHED`。
3. 查询 `media_assets`，获得 bucket、对象键、大小和内容类型。
4. 解析客户端 `Range` 请求头。
5. 使用 S3 范围读取只获取所需字节区间。
6. 将对象输入流直接写入 HTTP 响应，不把整个文件读入内存。

响应要求：

| 场景 | 状态码 | 响应要求 |
| --- | --- | --- |
| 无 `Range` | `200 OK` | 完整对象长度 |
| 有效单字节区间 | `206 Partial Content` | `Content-Range`、区间长度 |
| 无效或越界区间 | `416 Range Not Satisfiable` | `RANGE_NOT_SATISFIABLE` |
| 歌曲或对象不存在 | `404 Not Found` | `TRACK_NOT_FOUND` |
| MinIO 不可用 | `503 Service Unavailable` | `MEDIA_STORAGE_UNAVAILABLE` |

所有歌曲流响应必须包含：

- `Accept-Ranges: bytes`
- 正确的 `Content-Type`
- 正确的 `Content-Length`
- `206` 时的 `Content-Range`
- 以 `sha256` 为基础生成的强 `ETag`
- `Last-Modified`
- `Cache-Control: private`

阿里云反向代理必须关闭歌曲流响应缓冲，并设置足够长的读取超时，避免代理先缓存完整文件再返回。

## 9. 组件边界

| 组件 | 职责 |
| --- | --- |
| `AudioStorage` | 对象存在判断、流式写入、范围读取、删除和对象信息查询 |
| `S3AudioStorage` | 使用 AWS S3 SDK 实现 MinIO 访问 |
| `AudioMetadataReader` | 从音频流或受控临时文件提取元数据，不暴露具体音频库 |
| `MediaImportService` | 导入状态、幂等、校验、提取和补偿编排 |
| `TrackService` | 歌曲业务状态和媒体引用管理 |
| `TrackRepository` | 歌曲和媒体引用的数据访问 |
| `MediaAssetRepository` | 媒体资产的数据访问 |
| `MediaImportRepository` | 导入状态的数据访问 |
| 未来的 `TrackController` | 只处理 HTTP 契约，不承载存储逻辑 |
| 未来的 `TrackStreamController` | 只处理 Range 请求和流响应 |
| `GlobalExceptionHandler` | 统一映射稳定错误码和 RFC 9457 ProblemDetail |

业务层不得返回 JPA Entity；Repository 不处理权限；Controller 不直接调用 S3 SDK。

## 10. 安全要求

- 两个 MinIO bucket 均为私有。
- MinIO 凭据通过环境变量注入，不保存在 Git 中。
- 管理员上传接口需要 `ADMIN` 角色。
- 歌曲信息和歌曲播放接口需要已认证用户。
- 文件校验同时检查大小、扩展名、MIME 和魔数。
- 对象键由后端生成，防止路径穿越和跨 bucket 访问。
- 日志不得记录 MinIO Secret、JWT、密码或完整敏感请求。
- 删除歌曲先标记 `ARCHIVED`；只有不存在有效引用时才删除对象。
- 上传接口实现时必须增加限流，避免占用服务器带宽和磁盘。

## 11. 配置与部署

外部化配置至少包括：

```text
S3_ENDPOINT
S3_ACCESS_KEY
S3_SECRET_KEY
S3_AUDIO_BUCKET
S3_COVERS_BUCKET
S3_REGION
S3_CONNECT_TIMEOUT
S3_READ_TIMEOUT
UPLOAD_MAX_AUDIO_BYTES
UPLOAD_MAX_COVER_BYTES
```

`.env.example` 只保存占位值。Docker Compose 增加 MinIO 服务、持久化数据卷和健康检查，但不包含真实密钥。

部署要求：

- MinIO 数据卷与 MySQL 数据卷分别持久化。
- MinIO 仅允许应用服务器所在内部网络访问。
- 阿里云代理到 Spring Boot API，不代理 MinIO 管理接口。
- `local`、`test`、`prod` 配置使用不同 bucket 和凭据来源。

## 12. 备份与恢复

- MySQL 定期执行逻辑备份。
- MinIO 数据卷执行快照或对象级备份。
- `media_assets.sha256` 用于恢复后的对象完整性核对。
- 恢复时先恢复 MinIO 对象，再恢复 MySQL，并运行一致性核对任务。
- 一致性核对任务报告：数据库引用缺失对象、无数据库引用对象、大小或摘要不匹配对象。
- Redis 不纳入歌曲恢复流程。

## 13. 可观测性

日志使用 SLF4J，并延续当前 `traceId` 机制。关键日志字段包括：

- `trackId`
- `uploadId`
- `mediaAssetId`
- `bucket`
- `objectKey`
- `status`
- `failureCode`
- `durationMs`

关键指标：

- 上传请求数、成功数、失败数和字节数。
- 元数据提取成功数、失败数和耗时。
- 歌曲播放请求数、Range 请求数、错误率和耗时。
- MinIO 读写错误率和延迟。
- 孤儿对象数量和清理数量。
- MySQL 与 MinIO 健康状态。

Actuator 敏感端点不在公网暴露。

## 14. 测试策略

MySQL Testcontainers：

- `tracks`、`media_assets`、`media_imports` 映射和约束。
- 歌曲状态更新和媒体引用完整性。
- 相同 `uploadId` 的幂等行为。
- 审计字段和 UTC 时间写入。

MinIO Testcontainers：

- 流式上传和 SHA-256 校验。
- 完整读取和首、尾、中间字节区间读取。
- 对象不存在和 MinIO 不可用。
- 补偿删除和孤儿对象清理。

HTTP 集成测试：

- 未登录返回 `401`。
- 非管理员上传返回 `403`。
- 歌曲不存在返回 `404 TRACK_NOT_FOUND`。
- 无 `Range` 返回 `200`。
- 有效 `Range` 返回 `206` 和正确 `Content-Range`。
- 非法 `Range` 返回 `416`。
- MinIO 不可用返回 `503 MEDIA_STORAGE_UNAVAILABLE`。
- 响应不包含 bucket、对象键或存储凭据。

测试命名使用 `should_<result>_when_<condition>`。

## 15. 未来接口边界

本设计为以下接口提供基础，但不一次性实现它们：

- 管理员歌曲上传接口。
- 管理员歌曲确认或发布接口。
- 歌曲信息获取接口。
- 歌曲播放接口。

每个接口必须按照项目的单接口工作流独立完成契约、代码、迁移、测试、OpenAPI、API 文档和构建验证。

## 16. 已确认决策

- 使用私有 MinIO 保存音频和封面，使用 MySQL 保存歌曲和媒体引用。
- Spring Boot API、MySQL、Redis、MinIO 全部部署在无公网服务器。
- 阿里云服务器只做 TLS 和反向代理。
- 使用 AWS S3 SDK 对接 MinIO，通过 `AudioStorage` 隔离供应商。
- 歌曲信息和歌曲播放都要求登录。
- 管理员上传、确认和发布歌曲要求 `ADMIN`。
- 歌曲由管理员上传，自动提取 ID3，确认后发布。
- 使用后端 Range 代理播放，不向客户端公开 MinIO bucket。
- 使用 `media_imports` 处理上传幂等、状态和跨存储补偿。
- 文件对象不可原地修改，替换文件时创建新的媒体资产记录。
- 不把音频作为 MySQL BLOB 保存，不在 Redis 缓存完整歌曲。
