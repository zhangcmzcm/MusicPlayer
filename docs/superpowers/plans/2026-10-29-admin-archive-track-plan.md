# 管理员归档歌曲接口实现计划

日期：2026-10-29

设计文档：`docs/superpowers/specs/2026-10-29-admin-archive-track-design.md`

## 1. 目标

只实现一个 HTTP 接口：

- `POST /api/v1/tracks/{trackId}/archive`

管理员将 `DRAFT` 或 `PUBLISHED` 歌曲设置为 `ARCHIVED`。归档只更新状态和审计字段，不删除数据库记录或 MinIO/S3 对象。普通用户未来歌曲接口必须排除 `DRAFT` 和 `ARCHIVED`。

## 2. 实施约束

- Java 21、Spring Boot、Spring Web、Spring Security、Spring Data JPA、MySQL、JUnit 5、Testcontainers。
- 不新增数据库迁移、依赖或配置。
- 不修改已执行迁移，不使用 `ddl-auto=update`。
- 不删除音频、封面或对象存储数据。
- 不实现删除、恢复发布、重新发布、普通用户歌曲查询接口。
- Controller 不写业务规则，Service 不向 Controller 返回 Entity。
- 执行者身份只使用 `AuthenticatedUserPrincipal.userId()`，禁止 `@AuthenticationPrincipal Jwt`。
- 所有错误使用 RFC 9457 `ProblemDetail` 和稳定 `code`。
- 只修改本接口所需文件，不重构发布接口或其他模块。

## 3. 实施步骤

### 阶段 1：领域行为与异常

文件：

- `src/main/java/org/example/music/track/domain/TrackEntity.java`
- 新增 `src/main/java/org/example/music/track/exception/TrackAlreadyArchivedException.java`

实现：

1. 在 `TrackEntity` 增加 `archive(Integer userId, LocalDateTime updatedAt)`。
2. `DRAFT` 和 `PUBLISHED` 允许归档；`ARCHIVED` 抛出 `TrackAlreadyArchivedException`。
3. 归档只修改 `status`、`updatedAt`、`updatedBy`，不修改媒体、元数据、创建审计字段。
4. `TrackAlreadyArchivedException` 仅表达重复归档冲突，不复用 `TrackNotDraftException`。

测试：

- `DRAFT` 归档成功。
- `PUBLISHED` 归档成功。
- `ARCHIVED` 重复归档抛出 `TrackAlreadyArchivedException`。
- 失败时不修改状态和审计字段。

### 阶段 2：DTO、Mapper、Service 和事务服务

文件：

- 新增 `src/main/java/org/example/music/track/dto/TrackArchiveResponse.java`
- 新增 `src/main/java/org/example/music/track/dto/TrackArchiveResponseMapper.java`
- 新增 `src/main/java/org/example/music/track/service/TrackArchiveService.java`
- 新增 `src/main/java/org/example/music/track/service/TrackArchiveTransactionService.java`

实现：

1. `TrackArchiveResponse` 只包含 `id`、`status`、`version`、`updatedAt`。
2. Mapper 使用 UTC 将 `LocalDateTime` 转换为 `Instant`，不暴露 Entity。
3. `TrackArchiveService` 按 `trackId` 查找歌曲，检查状态，调用事务服务，并映射响应。
4. `TrackArchiveTransactionService` 使用 `@Transactional`，调用 `TrackEntity.archive(userId, updatedAt)`，使用 `saveAndFlush` 触发乐观锁。
5. Service 异常顺序必须保持：

```java
catch (ObjectOptimisticLockingFailureException exception) {
    throw exception;
} catch (DataAccessException exception) {
    throw new TrackStoreUnavailableException("歌曲归档暂时不可用", exception);
}
```

6. 读取数据库失败映射为 `TrackStoreUnavailableException`。
7. `TrackAlreadyArchivedException` 和 `TrackNotFoundException` 原样传播到全局处理器。

测试：

- 成功映射 DTO。
- 歌曲不存在返回 `TrackNotFoundException`。
- 已归档歌曲返回 `TrackAlreadyArchivedException`。
- `ObjectOptimisticLockingFailureException` 原样传播。
- 其他 `DataAccessException` 映射为 `TrackStoreUnavailableException`。

### 阶段 3：Controller 与全局错误处理

文件：

- 新增 `src/main/java/org/example/music/track/controller/TrackArchiveController.java`
- 修改 `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`，新增归档冲突处理方法

实现：

1. 定义 `POST /api/v1/tracks/{trackId}/archive`，无 Body，`trackId` 使用 `@Positive`。
2. 使用 `@PreAuthorize("hasRole('ADMIN')")`。
3. 使用 `@AuthenticationPrincipal AuthenticatedUserPrincipal actor`，传入 `actor.userId()`。
4. 返回 `200 TrackArchiveResponse`。
5. `TrackAlreadyArchivedException` 映射为 `409`、`TRACK_ALREADY_ARCHIVED`。
6. 保持现有 `ObjectOptimisticLockingFailureException` 处理器输出，响应体不含 `expectedVersion/actualVersion`。
7. 更新完整 OpenAPI 注解：`@Tag`、`@Operation`、`@ApiResponses`、`@Parameter`、`@Schema`。

测试：

- 管理员成功归档返回 200 和正确 JSON。
- `trackId = 0` 返回 400 `VALIDATION_FAILED`。
- 无认证返回 401。
- 普通用户返回 403。
- 歌曲不存在返回 404。
- 重复归档返回 409 `TRACK_ALREADY_ARCHIVED`。
- 乐观锁返回 409 `TRACK_VERSION_CONFLICT`，并断言无 `expectedVersion/actualVersion`。
- 数据库不可用返回 503 `TRACK_STORE_UNAVAILABLE`。
- Controller 测试使用 `AuthenticatedUserPrincipal`，不得只注入 `JwtAuthenticationToken`。

### 阶段 4：MySQL 和真实认证链路测试

文件：

- 新增 `src/test/java/org/example/music/track/TrackArchiveIntegrationTest.java`

实现：

1. 使用 Testcontainers MySQL 8.4，不使用 H2。
2. 使用真实 Bearer Token 穿越完整 Spring Security 过滤器链。
3. 使用真实 `TrackRepository`、`MediaAssetRepository` 和 `UserRepository` 创建测试数据。
4. 覆盖：
   - `DRAFT → ARCHIVED` 成功并持久化 `status/version/updatedAt/updatedBy`。
   - `PUBLISHED → ARCHIVED` 成功并持久化 `status/version/updatedAt/updatedBy`。
   - 重复归档返回 `TRACK_ALREADY_ARCHIVED`，且 `version/updatedAt/updatedBy` 不变。
   - 未认证返回 401。
   - 普通用户返回 403。
   - 并发 stale-version 冲突返回统一 409 响应体，且冲突后状态和审计字段不变。
   - 真实 Token 请求能从 `AuthenticatedUserPrincipal` 取得管理员 ID。
5. 本接口不启动 Redis、MinIO 或 S3 容器。

### 阶段 5：API 文档

文件：

- 新增 `docs/api/track/archive-track.md`

文档必须包含：

- `POST /api/v1/tracks/{trackId}/archive`。
- 管理员权限。
- 无 Body，`trackId` 正整数。
- `DRAFT/PUBLISHED → ARCHIVED`，重复归档 `409 TRACK_ALREADY_ARCHIVED`。
- 归档保留数据库记录、音频、封面和 MinIO/S3 对象。
- 200 响应示例。
- 409 重复归档示例。
- 409 乐观锁示例，明确不含 `expectedVersion/actualVersion`。
- 400、401、403、404、503 错误表。
- 可执行 curl 示例，Token 使用占位符。

## 4. 测试矩阵

| 场景 | 期望 |
| --- | --- |
| 管理员归档 DRAFT | 200，`status=ARCHIVED`，版本递增 |
| 管理员归档 PUBLISHED | 200，`status=ARCHIVED`，版本递增 |
| 归档 ARCHIVED | 409 `TRACK_ALREADY_ARCHIVED` |
| 非法 trackId | 400 `VALIDATION_FAILED` |
| 未认证 | 401 `UNAUTHORIZED` |
| 普通用户 | 403 `FORBIDDEN` |
| 歌曲不存在 | 404 `TRACK_NOT_FOUND` |
| 并发修改 | 409 `TRACK_VERSION_CONFLICT` |
| 数据库不可用 | 503 `TRACK_STORE_UNAVAILABLE` |
| 重复归档后审计字段 | `version/updatedAt/updatedBy` 不变 |
| 并发冲突后审计字段 | `status/version/updatedAt/updatedBy` 不变 |
| 归档后媒体数据 | 数据库记录和对象存储内容均保留 |

## 5. 验收条件

- 只实现 `POST /api/v1/tracks/{trackId}/archive`，无越界改动。
- `.\gradlew.bat classes` 通过。
- `MINIO_INTEGRATION_TESTS=true .\gradlew.bat check` 通过。
- 测试结果必须报告通过、失败和跳过数量；Testcontainers 不执行时 skipped 不得写成通过。
- 无 TODO、FIXME、假实现、占位实现。
- 无硬编码密钥、密码、Token 或 S3 凭据。
- 无敏感日志，不返回 Entity。
- 无新增依赖、配置或数据库迁移。
- OpenAPI 注解与 `docs/api/track/archive-track.md` 与实现一致。
- 架构、状态机、数据保留规则和异常响应与设计文档一致。
