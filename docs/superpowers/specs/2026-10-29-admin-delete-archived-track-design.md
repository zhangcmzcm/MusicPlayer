# 管理员删除归档歌曲接口设计

日期：2026-10-29

状态：已确认

## 1. 目标与范围

本次只实现一个 HTTP 接口：

- `DELETE /api/v1/tracks/{trackId}`

接口允许管理员物理删除已经归档的歌曲。只有 `ARCHIVED` 状态歌曲可以删除；`DRAFT` 和 `PUBLISHED` 必须先通过独立归档接口进入 `ARCHIVED`。

本次覆盖：

- 管理员鉴权。
- `ARCHIVED` 状态前置校验。
- 删除 `tracks` 歌曲记录。
- 删除歌曲关联的 `media_assets` 音频和封面记录。
- 删除 MinIO/S3 中对应的音频和封面对象。
- 对象删除与数据库删除的失败补偿语义。
- 单元测试、Controller 测试、MySQL/MinIO 集成测试、OpenAPI 和 API 文档。

本次不覆盖：

- 删除 `media_imports` 导入历史。
- 恢复、取消删除、重新发布或批量删除。
- 普通用户歌曲详情、列表、搜索或播放接口。
- 音频转码、备份、归档到冷存储。
- 后台清理任务、删除队列或两阶段删除状态机。
- 修改现有上传、导入、发布或归档接口。

## 2. 已确认决策

- 接口采用 `DELETE /api/v1/tracks/{trackId}`。
- 仅已认证且具有 `ADMIN` 角色的用户可调用。
- 仅允许删除 `ARCHIVED` 歌曲。
- `DRAFT`、`PUBLISHED` 删除返回 `409 TRACK_NOT_ARCHIVED`。
- 歌曲不存在或已经删除后重复调用返回 `404 TRACK_NOT_FOUND`。
- 成功返回 `204 No Content`，不返回 DTO。
- 删除是物理删除：删除歌曲记录、媒体资产记录和 MinIO/S3 对象。
- 保留 `media_imports` 导入审计历史；删除歌曲后其 `track_id` 通过现有 `ON DELETE SET NULL` 外键规则设置为 `NULL`。
- MinIO/S3 对象删除是幂等操作，不存在的对象视为删除成功。
- 采用先删除对象、后删除数据库的顺序。
- 对象删除失败时数据库保持不变，返回可重试错误。
- 数据库删除失败时事务整体回滚并保持数据不变，返回可重试错误。
- 不新增数据库迁移、依赖、Redis 幂等键或后台任务。

## 3. HTTP 契约

### 3.1 请求

- 方法：`DELETE`
- 路径：`/api/v1/tracks/{trackId}`
- 权限：已认证且具有 `ADMIN` 角色
- Content-Type：不需要
- Body：无

路径参数：

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `trackId` | integer | 是 | 歌曲 ID，必须为正整数 |

### 3.2 成功响应

状态码：`204 No Content`

成功后：

- `tracks` 记录不存在。
- 歌曲关联的 `media_assets` 记录不存在。
- MinIO/S3 音频和封面对象不存在。
- `media_imports` 记录仍存在，`track_id` 为 `NULL`。

### 3.3 状态约束

```text
ARCHIVED  → 删除     允许，返回 204
DRAFT     → 删除     拒绝，返回 409 TRACK_NOT_ARCHIVED
PUBLISHED → 删除     拒绝，返回 409 TRACK_NOT_ARCHIVED
已删除    → 再次删除 返回 404 TRACK_NOT_FOUND
```

删除不修改歌曲状态或审计字段，直接移除歌曲和媒体资产数据。

## 4. 删除范围与数据关系

### 4.1 删除的数据

- `tracks` 中目标歌曲记录。
- `media_assets` 中该歌曲引用的音频资产记录。
- `media_assets` 中该歌曲引用的可选封面资产记录。
- MinIO/S3 中音频对象。
- MinIO/S3 中封面对象。

当前导入流程为每首歌曲创建独立的音频资产和可选封面资产，媒体资产由单首歌曲独占。若未来引入媒体资产共享，必须重新评估资产删除策略，不能直接删除仍被其他歌曲引用的资产。

### 4.2 保留的数据

- `media_imports` 导入历史保留。
- `media_imports.track_id` 通过外键 `ON DELETE SET NULL` 变为 `NULL`。
- 用户、角色历史和其他审计数据保持不变。
- 不存在软删除标记或墓碑记录。

## 5. 一致性与执行顺序

采用“先对象、后数据库”：

1. 按 `trackId` 查找歌曲；不存在时返回 `404 TRACK_NOT_FOUND`。
2. 检查歌曲状态必须是 `ARCHIVED`。
3. 读取歌曲引用的音频和封面 `media_assets`。
4. 调用 `AudioStorage.delete()` 删除 MinIO/S3 音频和封面对象。
5. 对象删除使用 best-effort 完整尝试；如果任一对象删除失败，记录首个异常并抛出清理异常。
6. 对象删除全部成功或视为成功后，在一个数据库事务中：
   - 删除 `tracks` 记录。
   - 删除关联的 `media_assets` 记录。
   - `media_imports.track_id` 自动设置为 `NULL`。
7. 返回 `204 No Content`。

### 5.1 对象删除失败

如果 MinIO/S3 删除失败：

- 返回 `503 TRACK_MEDIA_CLEANUP_FAILED`。
- `tracks` 和 `media_assets` 保持不变。
- 已成功删除的对象保留删除结果。
- 后续重试允许对不存在对象再次执行删除。
- 不允许在数据库已删除后才发现对象删除失败。

### 5.2 数据库删除失败

如果对象已经删除但数据库删除失败：

- 返回 `503 TRACK_DELETE_FAILED`。
- 数据库事务整体回滚，`tracks` 和 `media_assets` 保持不变。
- 重试会再次执行幂等对象删除，然后完成数据库删除。
- 不允许删除数据库记录后把对象留在 MinIO/S3 无法关联的位置。

### 5.3 并发删除

- 两个请求同时删除同一歌曲时，数据库条件删除保证只有胜者删除记录和资产。
- 失败方返回 `404 TRACK_NOT_FOUND`。
- 对象删除是幂等的，失败方已执行的对象删除不会造成业务数据不一致。
- 由于没有取消归档或重新发布接口，`ARCHIVED` 状态在删除前置校验后不会合法地变回其他状态。

## 6. 架构与组件

### 6.1 `TrackDeleteController`

职责仅限 HTTP 层：

- 定义 `DELETE /api/v1/tracks/{trackId}`。
- 使用 `@PreAuthorize("hasRole('ADMIN')")`。
- 使用 `@PathVariable @Positive Long trackId`。
- 调用 `TrackDeleteService`。
- 成功返回 `ResponseEntity<Void>` 的 `204 No Content`。
- 不直接访问 Repository 或 AudioStorage。

### 6.2 `TrackDeleteService`

职责：

- 查找歌曲并校验 `ARCHIVED` 状态。
- 读取关联音频和封面资产位置。
- 删除 MinIO/S3 对象并处理部分失败。
- 调用事务服务删除数据库记录。
- 将异常映射为稳定业务异常。
- 不返回 JPA Entity 给 Controller。

### 6.3 `TrackDeleteTransactionService`

职责：

- 使用 `@Transactional` 定义数据库删除事务。
- 条件删除 `ARCHIVED` 歌曲记录。
- 删除关联 `media_assets` 记录。
- 确认歌曲删除行数；删除行数为 0 时返回 `TrackNotFoundException`。
- 不执行 MinIO/S3 远程调用。
- 不保留软删除状态。

### 6.4 异常

新增：

- `TrackNotArchivedException`
- `TrackMediaCleanupFailedException`
- `TrackDeleteFailedException`

复用：

- `TrackNotFoundException`
- `TrackStoreUnavailableException`

错误响应均使用 RFC 9457 `ProblemDetail` 和稳定 `code` 字段。

## 7. 错误处理

| 场景 | 状态码 | 错误码 |
| --- | ---: | --- |
| `trackId` 格式无效 | 400 | `VALIDATION_FAILED` |
| 未认证 | 401 | `UNAUTHORIZED` |
| 已认证但不是管理员 | 403 | `FORBIDDEN` |
| 歌曲不存在或已删除 | 404 | `TRACK_NOT_FOUND` |
| 歌曲不是 `ARCHIVED` | 409 | `TRACK_NOT_ARCHIVED` |
| MinIO/S3 对象删除失败 | 503 | `TRACK_MEDIA_CLEANUP_FAILED` |
| 数据库删除失败 | 503 | `TRACK_DELETE_FAILED` |
| 歌曲读取数据库失败 | 503 | `TRACK_STORE_UNAVAILABLE` |

状态冲突响应示例：

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

对象清理失败响应示例：

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

日志只记录 traceId、trackId、异常类型和非敏感清理上下文，不记录 JWT、对象存储凭据、对象键或请求体。

## 8. 安全

- 继续使用现有 JWT/OAuth2 Resource Server 和 Token Version 过滤器。
- Controller 使用方法级 `@PreAuthorize("hasRole('ADMIN')")`。
- 普通用户即使知道 `trackId` 也不能删除歌曲。
- 不新增 Security 配置规则。
- 删除只针对服务端校验的 `ARCHIVED` 歌曲，不信任客户端传入状态。
- 不输出对象存储 bucket、对象键、密钥或凭据。

## 9. 测试计划

### 9.1 Service 单元测试

- `ARCHIVED` 歌曲删除成功。
- `DRAFT`、`PUBLISHED` 返回 `TrackNotArchivedException`。
- 歌曲不存在返回 `TrackNotFoundException`。
- 音频或封面删除失败映射为 `TrackMediaCleanupFailedException`。
- 一个对象删除失败时仍尝试删除其他对象。
- 数据库删除失败映射为 `TrackDeleteFailedException`。
- 数据库读取失败映射为 `TrackStoreUnavailableException`。

### 9.2 Controller 测试

- 管理员删除成功返回 204 且无响应体。
- 非法 `trackId` 返回 400。
- 未认证返回 401。
- 普通用户返回 403。
- 歌曲不存在返回 404。
- `DRAFT` 或 `PUBLISHED` 返回 409 `TRACK_NOT_ARCHIVED`。
- 对象清理失败返回 503 `TRACK_MEDIA_CLEANUP_FAILED`。
- 数据库删除失败返回 503 `TRACK_DELETE_FAILED`。
- 使用 `AuthenticatedUserPrincipal` 主体形态验证管理员身份。

### 9.3 MySQL/MinIO 集成测试

使用 Testcontainers MySQL 8.4 和真实 MinIO：

- 删除 `ARCHIVED` 歌曲后 `tracks`、音频资产、封面资产记录不存在。
- MinIO/S3 音频和封面对象不存在。
- `media_imports` 记录保留且 `track_id` 为 `NULL`。
- `DRAFT` 删除返回 409 且数据库、对象保持不变。
- `PUBLISHED` 删除返回 409 且数据库、对象保持不变。
- 重复删除返回 404。
- 对象不存在时删除仍成功。
- 对象删除失败时数据库保持不变并返回 503。
- 数据库删除失败时对象已删除但数据库保持不变，可重试完成删除。
- 未认证返回 401，普通用户返回 403。
- 真实 Bearer Token 穿越完整过滤器链并传递管理员用户 ID。

集成测试必须设置 `MINIO_INTEGRATION_TESTS=true`；未执行 MinIO 测试不得声称完成。

## 10. API 文档

OpenAPI 注解必须完整，包括：

- `@Tag`
- `@Operation`
- `@ApiResponses`
- `@ApiResponse`
- `@Parameter`
- 删除结果无响应体时明确 204。

新增 API 文档：

- `docs/api/track/delete-track.md`

文档必须包含：

- 方法和路径。
- 权限。
- `ARCHIVED` 前置条件。
- 删除范围和 `media_imports` 保留规则。
- 204 成功语义。
- 409 `TRACK_NOT_ARCHIVED` 响应示例。
- 503 `TRACK_MEDIA_CLEANUP_FAILED` 和 `TRACK_DELETE_FAILED` 响应示例。
- 可直接执行的 curl 示例。

## 11. 验证命令

Windows PowerShell：

```powershell
.\gradlew.bat classes
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat check
```

完成定义还要求：

- 代码可编译。
- 测试全部通过，跳过项如实汇报。
- 无 TODO、FIXME、假实现或占位实现。
- 无硬编码密钥、密码、Token 或对象存储凭据。
- 无敏感日志。
- 不新增未使用代码。
- 不新增依赖或配置。
- 不新增数据库迁移。
- 删除失败时不得产生无法关联的孤儿对象。
- OpenAPI 与 API 文档和实现一致。

## 12. 预计变更文件

生产代码：

- `src/main/java/org/example/music/track/controller/TrackDeleteController.java`
- `src/main/java/org/example/music/track/service/TrackDeleteService.java`
- `src/main/java/org/example/music/track/service/TrackDeleteTransactionService.java`
- `src/main/java/org/example/music/track/exception/TrackNotArchivedException.java`
- `src/main/java/org/example/music/track/exception/TrackMediaCleanupFailedException.java`
- `src/main/java/org/example/music/track/exception/TrackDeleteFailedException.java`
- `src/main/java/org/example/music/track/repository/TrackRepository.java`
- `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`

测试代码：

- `src/test/java/org/example/music/track/service/TrackDeleteServiceTest.java`
- `src/test/java/org/example/music/track/controller/TrackDeleteControllerTest.java`
- `src/test/java/org/example/music/track/TrackDeleteIntegrationTest.java`

文档：

- `docs/api/track/delete-track.md`
- `docs/superpowers/specs/2026-10-29-admin-delete-archived-track-design.md`

## 13. 明确默认项

- `media_imports` 保留历史记录，不作为歌曲删除范围的一部分。
- 当前媒体资产由单首歌曲独占；未来若引入共享媒体资产，需要增加引用计数或归属校验后才能复用本删除策略。
- 删除接口成功返回 204，不返回删除确认 DTO。
- 已删除歌曲重复请求返回 404，而不是 204。
- MinIO/S3 对象不存在视为删除成功，确保失败恢复和重复请求幂等。
- 对象删除先于数据库删除；数据库删除失败时可重试完成清理。
