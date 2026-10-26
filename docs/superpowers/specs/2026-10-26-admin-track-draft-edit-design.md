# 管理员歌曲草稿编辑接口设计

日期：2026-10-26

状态：已确认

## 1. 目标与范围

本次只实现一个 HTTP 接口：

- `PATCH /api/v1/tracks/{trackId}/draft`

接口允许管理员修改歌曲草稿中的文本元数据。歌曲必须处于 `DRAFT` 状态，修改后继续保持 `DRAFT`。

本次覆盖：

- 歌曲草稿标题、艺术家、专辑、曲序和碟片序号的局部更新。
- 请求校验、状态检查、权限检查和错误响应。
- 更新审计字段和响应 DTO。
- 单元测试、MySQL 集成测试、OpenAPI 和中文 API 文档。

本次不包含：

- 上传、替换或删除音频文件。
- 上传、替换或删除封面。
- 修改歌曲时长。
- 修改原始 ID3 元数据。
- 歌曲发布、归档或状态流转。
- 歌曲详情、列表、搜索或播放接口。
- 使用数据库乐观锁防止并发编辑覆盖。

## 2. 已确认决策

- 使用 `PATCH /api/v1/tracks/{trackId}/draft`。
- `tracks/{trackId}` 继续表示歌曲主资源，`draft` 表示草稿编辑子资源。
- 仅允许 `ADMIN` 角色调用。
- 仅允许状态为 `DRAFT` 的歌曲调用。
- 请求使用 `application/json`。
- 只修改标题、艺术家、专辑、曲序和碟片序号。
- 使用局部更新语义：字段缺失表示保持原值。
- 可空字段显式传入 `null` 表示清空。
- 空 JSON `{}` 是合法请求，返回未修改的草稿。
- 并发编辑使用 JPA `@Version` 乐观锁。请求必须携带当前 `version`，版本不一致时返回 `409 TRACK_VERSION_CONFLICT`。
- 发布由后续独立接口处理。
- Request、Response 和 Mapper 使用独立 Java 文件，不定义在 Controller 中。
- `TrackDraftRequest` 必须区分字段缺失和显式 JSON `null`，因此使用带字段存在标记的 DTO 类，不使用无法区分这两种情况的普通 record。

## 3. HTTP 契约

### 3.1 请求

- 方法：`PATCH`
- 路径：`/api/v1/tracks/{trackId}/draft`
- 权限：已认证且具有 `ADMIN` 角色
- Content-Type：`application/json`

请求字段：

| 字段 | 类型 | 必填 | 缺失时 | `null` 时 | 校验规则 |
| --- | --- | --- | --- | --- | --- |
| `title` | string | 否 | 保持原值 | 不允许 | 非空，最大 255 字符 |
| `artistName` | string | 否 | 保持原值 | 不允许 | 非空，最大 255 字符 |
| `albumName` | string/null | 否 | 保持原值 | 清空 | 最大 255 字符 |
| `trackNumber` | integer/null | 否 | 保持原值 | 清空 | 提供时必须大于 0 |
| `discNumber` | integer/null | 否 | 保持原值 | 清空 | 提供时必须大于 0 |
| `version` | integer | 是 | 不允许 | 不允许 | 必须等于当前歌曲版本号 |

示例请求：

```json
{
  "title": "修正后的标题",
  "artistName": "修正后的艺术家",
  "albumName": null,
  "trackNumber": 3,
  "discNumber": null
}
```

### 3.2 成功响应

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

响应不包含 bucket、对象键、临时路径、原始 ID3 JSON 或存储凭据。

### 3.3 错误响应

所有错误使用 RFC 9457 ProblemDetail，并包含稳定 `code`。

| 场景 | 状态码 | 错误码 |
| --- | ---: | --- |
| `trackId` 格式错误或请求字段无效 | `400` | `VALIDATION_FAILED` |
| 未认证 | `401` | `UNAUTHORIZED` |
| 非管理员 | `403` | `FORBIDDEN` |
| 歌曲不存在 | `404` | `TRACK_NOT_FOUND` |
| 歌曲不是 `DRAFT` | `409` | `TRACK_NOT_DRAFT` |
| 请求版本号与当前歌曲版本号不一致 | `409` | `TRACK_VERSION_CONFLICT` |
| MySQL 不可用 | `503` | `TRACK_STORE_UNAVAILABLE` |

## 4. 字段更新规则

### 4.1 标题和艺术家

- 字段缺失时保持原值。
- 字符串仅包含空白字符时返回 `400 VALIDATION_FAILED`。
- 字符串长度超过 255 时返回 `400 VALIDATION_FAILED`。
- 保存前去除首尾空白。
- 不允许显式传入 `null`。

### 4.2 专辑

- 字段缺失时保持原值。
- 显式传入 `null` 时清空专辑。
- 非空字符串去除首尾空白。
- 仅包含空白字符的字符串等价于 `null`，保存为空。
- 非空值最大 255 字符。

### 4.3 曲序和碟片序号

- 字段缺失时保持原值。
- 显式传入 `null` 时清空字段。
- 其他值必须是大于 0 的整数。
- 0、负数和其他非法值返回 `400 VALIDATION_FAILED`。

### 4.4 版本号

`version` 是并发控制前置条件，不是可编辑的歌曲字段。请求必须提供该字段，且必须与数据库中的当前版本号一致。更新成功后版本号加 1，响应返回新版本号。

### 4.5 字段存在性

JSON 字段缺失和显式 `null` 具有不同语义，因此 `TrackDraftRequest` 不能使用无法区分这两种情况的普通 record。

请求 DTO 使用字段存在标记：

- 字段未出现在 JSON 中：该字段不参与更新。
- 字段显式设置为 JSON `null`：该字段参与更新；只有可空字段允许清空。
- 字段设置为字符串或整数：该字段参与更新并执行校验。

`TrackDraftRequest` 仍然使用独立文件，字段存在标记只存在于 DTO 内部，不暴露给 Controller 或响应。
### 4.4 不可变字段

以下字段不接受业务修改：

- `durationMs`
- `audioAssetId`
- `coverAssetId`
- `id3Metadata`
- `status`
- `createdAt`
- `createdBy`
- `version` 仅作为并发前置条件校验，更新由 JPA 乐观锁自动递增

更新成功时只修改：

- `title`
- `artistName`
- `albumName`
- `trackNumber`
- `discNumber`
- `updatedAt`
- `updatedBy`

## 5. 数据流

1. Controller 接收路径变量 `trackId` 和 JSON 请求。
2. Bean Validation 校验字段类型和长度。
3. `@PreAuthorize("hasRole('ADMIN')")` 校验管理员权限。
4. `TrackDraftService` 根据 `trackId` 查询歌曲。
5. 歌曲不存在时抛出 `TrackNotFoundException`。
6. 歌曲状态不是 `DRAFT` 时抛出 `TrackNotDraftException`。
7. 根据字段是否缺失、是否为 `null` 应用局部更新规则。
8. 比较请求 `version` 和歌曲当前版本号，不一致时返回 `TRACK_VERSION_CONFLICT`。
9. `TrackEntity.updateDraft` 更新允许字段以及 `updatedAt`、`updatedBy`。
10. `TrackDraftTransactionService` 在单个数据库事务中保存歌曲，由 JPA `@Version` 递增版本号。
11. 查询音频和可选封面媒体资产。
12. Mapper 将实体和资产映射为 `TrackDraftResponse`。
13. 返回 `200 OK`。

数据库事务不包含 JSON 解析、媒体对象读取或 MinIO 网络请求。

## 6. 组件边界

实际 DTO 和 Mapper 使用独立文件，不写在 Controller 中。

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| `TrackDraftController` | `track/controller/TrackDraftController.java` | HTTP 参数绑定、调用 Service、返回 DTO |
| `TrackDraftRequest` | `track/dto/TrackDraftRequest.java` | 带字段存在标记的 JSON 请求 DTO 和校验注解 |
| `TrackDraftResponse` | `track/dto/TrackDraftResponse.java` | 草稿响应结构 |
| `TrackDraftResponseMapper` | `track/dto/TrackDraftResponseMapper.java` | Entity 和媒体资产映射为 DTO |
| `TrackDraftService` | `track/service/TrackDraftService.java` | 状态检查、局部更新、结果组装 |
| `TrackDraftTransactionService` | `track/service/TrackDraftTransactionService.java` | 数据库事务中的歌曲更新 |
| `TrackRepository` | `track/repository/TrackRepository.java` | 歌曲查询和保存 |
| `MediaAssetRepository` | `track/repository/MediaAssetRepository.java` | 音频和封面资产查询 |
| `TrackEntity` | `track/domain/TrackEntity.java` | 草稿字段更新和审计字段维护 |
| `GlobalExceptionHandler` | `common/exception/GlobalExceptionHandler.java` | 稳定错误码和 ProblemDetail 映射 |

Controller 不定义 DTO，不修改 Entity，不负责字段映射；Service 不返回 JPA Entity 给 Controller；Repository 不处理权限。

## 7. 持久化

本次新增一个数据库迁移：

- `V3__add_track_version.sql`

迁移仅在 `tracks` 表增加 `version BIGINT NOT NULL DEFAULT 0`。

`tracks` 表新增 `version` 后包含：

- `title`
- `artist_name`
- `album_name`
- `duration_ms`
- `track_number`
- `disc_number`
- `audio_asset_id`
- `cover_asset_id`
- `id3_metadata`
- `status`
- `updated_at`
- `updated_by`
- `version`

现有 `media_assets` 表用于响应中的音频和封面信息。`V2__create_song_storage_tables.sql` 不修改。

## 8. 并发处理

本接口使用 JPA `@Version` 乐观锁：

- `TrackEntity` 增加 `@Version` 字段，数据库列由 `V3__add_track_version.sql` 增加。
- 请求必须携带编辑前的 `version`。
- 版本号不一致时返回 `409 TRACK_VERSION_CONFLICT`。
- 更新成功后由 JPA 自动递增版本号。

## 9. 安全

- 使用 `@PreAuthorize("hasRole('ADMIN')")`。
- 接口要求 Bearer JWT。
- 不接受音频、封面或其他二进制内容。
- 响应不暴露 MinIO bucket、对象键或原始 ID3 JSON。
- 日志记录 `trackId`、更新字段名称、操作用户、结果和耗时。
- 不记录请求中的完整敏感内容或存储凭据。

## 10. 测试计划

### 10.1 Controller 测试

- 修改单个字段成功返回 `200`。
- 同时修改多个字段成功返回 `200`。
- `albumName` 为 `null` 时清空。
- `trackNumber` 为 `null` 时清空。
- `discNumber` 为 `null` 时清空。
- 请求中未提供的字段保持原值。
- `version` 缺失或非法返回 `400 VALIDATION_FAILED`。
- 空 JSON `{}` 返回未修改的草稿。
- 标题为空白返回 `400 VALIDATION_FAILED`。
- 艺术家为空白返回 `400 VALIDATION_FAILED`。
- 字符串超过 255 字符返回 `400 VALIDATION_FAILED`。
- 曲序或碟片序号为 0、负数时返回 `400 VALIDATION_FAILED`。
- 未登录返回 `401 UNAUTHORIZED`。
- 普通用户返回 `403 FORBIDDEN`。
- 歌曲不存在返回 `404 TRACK_NOT_FOUND`。
- `PUBLISHED` 或 `ARCHIVED` 歌曲返回 `409 TRACK_NOT_DRAFT`。
- 过期版本号返回 `409 TRACK_VERSION_CONFLICT`。
- 数据库不可用返回 `503 TRACK_STORE_UNAVAILABLE`。

### 10.2 Service 测试

- 字段缺失时保持原值。
- 显式 `null` 正确清空可空字段。
- 过期版本号阻止覆盖并返回版本冲突。
- 标题和艺术家去除首尾空白。
- 更新 `updatedBy` 和 `updatedAt`。
- 不修改音频、封面、时长、原始 ID3 JSON 或状态。
- 状态检查发生在数据库更新之前。
- Mapper 不返回 bucket 或对象键。

### 10.3 集成测试

- 使用 MySQL Testcontainers 验证真实更新、版本递增和并发冲突。
- 验证修改后音频和封面资产 ID 保持不变。
- 验证 `DRAFT` 状态保持不变。
- 本接口不访问 MinIO，不需要 MinIO Testcontainers。

测试命名使用 `should_<result>_when_<condition>`。

## 11. API 文档

新增中文文档：

- `docs/api/track/update-track-draft.md`

文档必须包含：

- 方法、路径和权限。
- JSON 字段和局部更新规则。
- 成功响应。
- 错误码。
- 可执行 curl 示例。
- 不可修改字段说明。

OpenAPI 使用中文 `@Tag`、`@Operation`、`@ApiResponse` 和 `@Schema` 描述。

## 12. 已确认决策

- 接口路径为 `PATCH /api/v1/tracks/{trackId}/draft`。
- 只修改 `DRAFT` 歌曲，修改后仍为 `DRAFT`。
- 只修改标题、艺术家、专辑、曲序和碟片序号。
- 使用局部更新语义。
- `null` 清空专辑、曲序和碟片序号。
- 标题和艺术家不允许为空。
- 不修改音频、封面、时长、原始 ID3 或状态。
- Request、Response、Mapper 使用独立文件。
- 使用 JPA `@Version` 乐观锁，版本不一致返回 `409 TRACK_VERSION_CONFLICT`。
- 使用 MySQL 集成测试，不需要 MinIO 测试。
- API 文档和 OpenAPI 描述统一使用中文。
