# 管理员删除归档歌曲接口实现计划

日期：2026-10-29

设计文档：`docs/superpowers/specs/2026-10-29-admin-delete-archived-track-design.md`

## 1. 目标

只实现一个 HTTP 接口：

- `DELETE /api/v1/tracks/{trackId}`

管理员物理删除 `ARCHIVED` 歌曲，包括歌曲记录、关联媒体资产记录和 MinIO/S3 音频、封面对象。保留 `media_imports` 导入历史，其 `track_id` 通过现有外键规则设置为 `NULL`。

## 2. 实施约束

- Java 21、Spring Web、Spring Security、Spring Data JPA、MySQL、AWS SDK S3、JUnit 5、Testcontainers。
- 只允许 `ARCHIVED` 歌曲删除。
- 不实现批量删除、恢复删除、重新发布或普通用户歌曲接口。
- 不修改已执行迁移，不使用 `ddl-auto=update`。
- 不新增数据库迁移、依赖、Redis 幂等表或后台任务。
- 不删除 `media_imports` 记录。
- 先删除 MinIO/S3 对象，再删除数据库记录。
- MinIO/S3 对象删除必须幂等，缺失对象视为成功。
- 对象删除失败时数据库保持不变；数据库删除失败时事务整体回滚。
- Controller 不访问 Repository 或 AudioStorage，Service 不向 Controller 返回 Entity。
- 不记录对象键、bucket、密钥、Token 或敏感请求数据。

## 3. 实施步骤

### 阶段 1：删除领域异常和数据库访问

文件：

- 新增 `src/main/java/org/example/music/track/exception/TrackNotArchivedException.java`
- 新增 `src/main/java/org/example/music/track/exception/TrackMediaCleanupFailedException.java`
- 新增 `src/main/java/org/example/music/track/exception/TrackDeleteFailedException.java`
- 修改 `src/main/java/org/example/music/track/repository/TrackRepository.java`

实现：

1. `TrackNotArchivedException` 表达 `DRAFT/PUBLISHED` 不能删除。
2. `TrackMediaCleanupFailedException` 表达 MinIO/S3 清理失败。
3. `TrackDeleteFailedException` 表达对象清理完成但数据库删除失败。
4. `TrackRepository` 增加按 ID 和 `ARCHIVED` 状态条件删除的方法，返回受影响行数：

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("delete from TrackEntity t where t.id = :trackId and t.status = :status")
int deleteArchived(@Param("trackId") Long trackId, @Param("status") TrackStatus status);
```

5. 通过受影响行数区分成功删除和并发重复删除，失败方得到 `TrackNotFoundException`。

### 阶段 2：对象清理和数据库删除服务

文件：

- 新增 `src/main/java/org/example/music/track/service/TrackDeleteService.java`
- 新增 `src/main/java/org/example/music/track/service/TrackDeleteTransactionService.java`

实现：

1. `TrackDeleteService` 按 ID 查找歌曲，数据库读取失败映射为 `TrackStoreUnavailableException`。
2. 歌曲状态不是 `ARCHIVED` 时抛出 `TrackNotArchivedException`。
3. 读取 `audio_asset_id` 和可选 `cover_asset_id` 对应的 `media_assets`。
4. 对音频和封面分别调用 `AudioStorage.delete(bucket, objectKey)`。
5. 对象清理采用完整 best-effort：一个对象失败后仍尝试其他对象，最后抛出 `TrackMediaCleanupFailedException`。
6. 对象清理全部成功或缺失对象视为成功后，调用 `TrackDeleteTransactionService`。
7. `TrackDeleteTransactionService` 在一个 `@Transactional` 方法中：
   - 条件删除 `ARCHIVED` 歌曲记录。
   - 删除受影响的 `media_assets` 记录。
   - 删除行数为 0 时抛出 `TrackNotFoundException`。
8. 数据库删除异常映射为 `TrackDeleteFailedException`，事务整体回滚。
9. 不捕获或吞掉异常，不返回 Entity。

### 阶段 3：Controller 和全局错误处理

文件：

- 新增 `src/main/java/org/example/music/track/controller/TrackDeleteController.java`
- 修改 `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`

实现：

1. 定义 `DELETE /api/v1/tracks/{trackId}`，无 Body，`trackId` 使用 `@Positive`。
2. 使用 `@PreAuthorize("hasRole('ADMIN')")`。
3. 调用 `TrackDeleteService.delete(trackId)`。
4. 成功返回 `ResponseEntity.noContent().build()`。
5. 新增错误码映射：
   - `TRACK_NOT_ARCHIVED` → 409
   - `TRACK_MEDIA_CLEANUP_FAILED` → 503
   - `TRACK_DELETE_FAILED` → 503
6. 保持现有 `TRACK_NOT_FOUND`、`TRACK_STORE_UNAVAILABLE` 映射。
7. 更新完整 OpenAPI 注解：`@Tag`、`@Operation`、`@ApiResponses`、`@Parameter`，明确 204 无响应体。

### 阶段 4：单元和 Controller 测试

文件：

- 新增 `src/test/java/org/example/music/track/service/TrackDeleteServiceTest.java`
- 新增 `src/test/java/org/example/music/track/controller/TrackDeleteControllerTest.java`

Service 测试：

- `ARCHIVED` 歌曲删除成功。
- `DRAFT/PUBLISHED` 返回 `TrackNotArchivedException`。
- 歌曲不存在返回 `TrackNotFoundException`。
- 音频或封面对象删除失败映射为 `TrackMediaCleanupFailedException`。
- 一个对象失败后仍尝试其他对象。
- 数据库删除失败映射为 `TrackDeleteFailedException`。
- 数据库读取失败映射为 `TrackStoreUnavailableException`。

Controller 测试：

- 管理员删除成功返回 204 且无响应体。
- 非法 `trackId` 返回 400 `VALIDATION_FAILED`。
- 无认证返回 401。
- 普通用户返回 403。
- 歌曲不存在返回 404。
- 非归档歌曲返回 409 `TRACK_NOT_ARCHIVED`。
- 对象清理失败返回 503 `TRACK_MEDIA_CLEANUP_FAILED`。
- 数据库删除失败返回 503 `TRACK_DELETE_FAILED`。
- 使用 `AuthenticatedUserPrincipal` 主体形态验证管理员身份，不绕过真实过滤器语义。

### 阶段 5：MySQL/MinIO 集成测试

文件：

- 新增 `src/test/java/org/example/music/track/TrackDeleteIntegrationTest.java`

实现：

1. 使用 Testcontainers MySQL 8.4 和真实 MinIO。
2. 使用真实 Bearer Token 穿越完整 Spring Security 过滤器链。
3. 创建 `DRAFT`、`PUBLISHED`、`ARCHIVED` 歌曲和真实媒体对象。
4. 覆盖：
   - `ARCHIVED` 删除成功后 `tracks`、音频资产、封面资产记录不存在。
   - MinIO/S3 音频和封面对象不存在。
   - `media_imports` 记录保留且 `track_id` 为 `NULL`。
   - `DRAFT`、`PUBLISHED` 删除返回 409 且数据库和对象保持不变。
   - 重复删除返回 404。
   - 对象不存在时删除仍成功。
   - 对象删除失败时数据库保持不变并返回 503。
   - 数据库删除失败时数据库保持不变，可重试完成删除。
   - 未认证返回 401、普通用户返回 403。
   - 真实 Token 请求正确传递管理员 ID。
5. 集成测试必须设置 `MINIO_INTEGRATION_TESTS=true`。

### 阶段 6：API 文档

文件：

- 新增 `docs/api/track/delete-track.md`

文档必须包含：

- `DELETE /api/v1/tracks/{trackId}`。
- 管理员权限。
- 无 Body，`trackId` 正整数。
- 仅 `ARCHIVED` 可删除。
- 204 成功语义。
- `media_imports` 保留且 `track_id` 置空。
- MinIO/S3 对象删除范围。
- 409 `TRACK_NOT_ARCHIVED` 响应示例。
- 503 `TRACK_MEDIA_CLEANUP_FAILED` 响应示例。
- 503 `TRACK_DELETE_FAILED` 响应示例。
- 404 `TRACK_NOT_FOUND`、400、401、403 错误表。
- 可执行 curl 示例，Token 使用占位符。

## 4. 测试矩阵

| 场景 | 期望 |
| --- | --- |
| 管理员删除 ARCHIVED | 204 |
| 删除 DRAFT | 409 `TRACK_NOT_ARCHIVED` |
| 删除 PUBLISHED | 409 `TRACK_NOT_ARCHIVED` |
| 重复删除 | 404 `TRACK_NOT_FOUND` |
| trackId 非法 | 400 `VALIDATION_FAILED` |
| 未认证 | 401 `UNAUTHORIZED` |
| 普通用户 | 403 `FORBIDDEN` |
| MinIO/S3 删除失败 | 503 `TRACK_MEDIA_CLEANUP_FAILED`，数据库不变 |
| 数据库删除失败 | 503 `TRACK_DELETE_FAILED`，数据库事务回滚 |
| 对象不存在 | 删除成功 |
| 删除成功后 media_imports | 记录保留，`track_id = NULL` |
| 删除成功后 media_assets | 音频、封面记录均不存在 |
| 删除成功后 MinIO/S3 | 音频、封面对象均不存在 |

## 5. 验收条件

- 只实现 `DELETE /api/v1/tracks/{trackId}`，无越界改动。
- `.\gradlew.bat classes` 通过。
- `MINIO_INTEGRATION_TESTS=true .\gradlew.bat check` 通过。
- 测试报告通过、失败和跳过数量；Testcontainers 或 MinIO 测试未执行时不得写成通过。
- 无 TODO、FIXME、假实现、占位实现。
- 无硬编码密钥、密码、Token 或对象存储凭据。
- 无敏感日志。
- 不新增迁移、依赖、配置或后台任务。
- 删除失败不产生无法关联的孤儿对象。
- OpenAPI 注解与 `docs/api/track/delete-track.md` 与实现一致。
- 数据删除范围、状态约束、失败补偿语义与设计文档一致。
