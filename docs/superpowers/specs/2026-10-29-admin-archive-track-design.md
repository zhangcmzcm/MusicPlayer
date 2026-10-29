# 管理员归档歌曲接口设计

日期：2026-10-29

状态：已确认

## 1. 目标与范围

本次只实现一个 HTTP 接口：

- `POST /api/v1/tracks/{trackId}/archive`

接口允许管理员将歌曲归档为 `ARCHIVED`，停止通过普通用户歌曲入口继续对外提供。当前项目没有普通用户歌曲查询、搜索或播放接口，本次只建立归档状态和未来查询必须遵守的可见性约束。

本次覆盖：

- 管理员鉴权。
- `DRAFT → ARCHIVED` 和 `PUBLISHED → ARCHIVED` 状态迁移。
- 更新审计字段和 JPA 乐观锁版本。
- 归档结果 DTO、错误响应和异常映射。
- 单元测试、Controller 测试、MySQL 集成测试、OpenAPI 和 API 文档。

本次不覆盖：

- 删除歌曲数据库记录、音频、封面或 MinIO/S3 对象。
- 恢复发布、取消归档或重新发布。
- 普通用户歌曲详情、列表、搜索或播放接口。
- 歌曲文本元数据编辑。
- 审批意见、定时归档、灰度下线。
- 额外的幂等表、Redis 幂等键或请求去重机制。

后续应使用独立的管理员删除接口处理 `ARCHIVED` 歌曲的物理数据清理，不与归档操作合并。

## 2. 已确认决策

- 接口采用 `POST /api/v1/tracks/{trackId}/archive`。
- 请求无 Body，成功返回 `200 OK`。
- 仅已认证且具有 `ADMIN` 角色的用户可调用。
- `DRAFT` 和 `PUBLISHED` 均可归档。
- 已归档歌曲重复调用时返回 `409 TRACK_ALREADY_ARCHIVED`。
- 归档采用逻辑下线，数据库记录、媒体资产记录、音频和封面对象全部保留。
- 未来普通用户歌曲接口只能返回 `PUBLISHED` 状态歌曲，必须排除 `DRAFT` 与 `ARCHIVED`。
- 管理接口可以读取归档歌曲，但不得成为普通用户绕过状态过滤的入口。
- 使用现有 JPA `@Version` 乐观锁处理并发编辑或归档。
- 不建设额外幂等基础设施；状态条件和乐观锁足以防止重复归档产生脏数据。
- 响应仅包含归档状态确认所需的轻量字段。

## 3. HTTP 契约

### 3.1 请求

- 方法：`POST`
- 路径：`/api/v1/tracks/{trackId}/archive`
- 权限：已认证且具有 `ADMIN` 角色
- Content-Type：不需要
- Body：无

路径参数：

| 参数 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `trackId` | integer | 是 | 歌曲 ID，必须为正整数 |

### 3.2 成功响应

状态码：`200 OK`

```json
{
  "id": 42,
  "status": "ARCHIVED",
  "version": 5,
  "updatedAt": "2026-10-29T10:30:00Z"
}
```

`updatedAt` 使用 UTC 和 ISO-8601。响应不包含 JWT、用户信息、对象存储 bucket、对象键、原始 ID3 元数据或存储凭据。

### 3.3 状态迁移

```text
DRAFT     → ARCHIVED   允许，返回 200
PUBLISHED → ARCHIVED   允许，返回 200
ARCHIVED  → ARCHIVED   拒绝，返回 409 TRACK_ALREADY_ARCHIVED
```

归档成功时：

- `status` 设置为 `ARCHIVED`。
- `updatedAt` 更新为当前 UTC 时间。
- `updatedBy` 更新为执行归档的管理员用户 ID。
- `version` 由 JPA 乐观锁自动递增。
- `createdAt`、`createdBy`、音频、封面、时长、ID3 元数据和文本元数据保持不变。

### 3.4 归档后的数据处理

归档是逻辑状态迁移，不执行物理删除：

- 保留 `tracks` 数据库记录。
- 保留 `media_assets` 数据库记录。
- 保留 MinIO/S3 中的音频和封面对象。
- 不清空对象键、哈希、大小或元数据。
- 普通用户歌曲列表、详情、搜索和播放接口必须排除 `DRAFT` 与 `ARCHIVED`。
- 管理员仍可通过管理接口查看归档歌曲。
- 后续删除接口只能处理 `ARCHIVED` 歌曲，并独立处理数据库与对象存储的一致性。

## 4. 架构与组件

### 4.1 `TrackArchiveController`

职责仅限 HTTP 层：

- 定义 `POST /api/v1/tracks/{trackId}/archive`。
- 使用 `@PreAuthorize("hasRole('ADMIN')")`。
- 通过 `@AuthenticationPrincipal AuthenticatedUserPrincipal actor` 读取执行管理员 ID，并以 `actor.userId()` 传入 Service。
- 禁止使用 `@AuthenticationPrincipal Jwt`；真实链路中的认证主体是 `AuthenticatedUserPrincipal`。
- 调用 `TrackArchiveService` 并返回 DTO。
- 不包含状态迁移或持久化逻辑。

### 4.2 `TrackArchiveService`

职责：

- 按 `trackId` 查找歌曲。
- 检查歌曲存在且当前状态允许归档。
- 调用事务服务完成状态迁移。
- 将实体映射为 `TrackArchiveResponse`。
- 将数据访问异常映射为稳定业务异常。

### 4.3 `TrackArchiveTransactionService`

职责：

- 使用 `@Transactional` 定义原子事务边界。
- 调用 `TrackEntity.archive(userId, updatedAt)`。
- 使用 `saveAndFlush` 持久化并触发乐观锁检查。
- 不执行远程对象存储操作。
- 不返回 Entity 给 Controller。

### 4.4 `TrackEntity`

增加 `archive(Integer userId, LocalDateTime updatedAt)` 领域方法：

- `DRAFT` 和 `PUBLISHED` 允许迁移。
- `ARCHIVED` 抛出 `TrackAlreadyArchivedException`。
- 设置状态为 `ARCHIVED`。
- 更新 `updatedAt` 与 `updatedBy`。
- 复用现有 `@Version` 字段，不手动维护版本号。

### 4.5 DTO

新增 `TrackArchiveResponse`：

```java
public record TrackArchiveResponse(
        Long id,
        String status,
        Long version,
        Instant updatedAt
) {
}
```

使用独立 Mapper 将 Entity 转换为 DTO，并把数据库中按 UTC 保存的 `LocalDateTime` 映射为带 UTC 时刻语义的 `Instant`。Controller 和 Service 均不返回 Entity。

### 4.6 异常

复用现有异常：

- `TrackNotFoundException`
- `TrackStoreUnavailableException`

新增：

- `TrackAlreadyArchivedException`

并发提交由 JPA 抛出 `ObjectOptimisticLockingFailureException`，必须保持原样传播并由 `GlobalExceptionHandler` 映射为 `409 TRACK_VERSION_CONFLICT`。由于 `ObjectOptimisticLockingFailureException` 是 `DataAccessException` 的子类，Service 的异常处理必须采用以下顺序：

```java
try {
    return trackArchiveTransactionService.archive(track, userId);
} catch (ObjectOptimisticLockingFailureException exception) {
    throw exception;
} catch (DataAccessException exception) {
    throw new TrackStoreUnavailableException("歌曲归档暂时不可用", exception);
}
```

禁止只捕获 `DataAccessException` 后统一映射为 `TrackStoreUnavailableException`。本接口不构造 `expectedVersion` 或 `actualVersion`，请求没有客户端版本号。

本接口的 `409 TRACK_VERSION_CONFLICT` 响应体不含 `expectedVersion`、`actualVersion`：

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

重复归档响应体：

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

## 5. 数据与事务

现有 `tracks.status`、`tracks.updated_at`、`tracks.updated_by` 和 `tracks.version` 字段已经满足本接口需求，因此：

- 不新增数据库迁移。
- 不新增索引或约束。
- 不修改已执行迁移。
- 不使用 `ddl-auto=update`。

事务范围只覆盖读取、状态检查、状态迁移和持久化，不包含 HTTP、日志、MinIO 或 S3 操作。状态迁移必须整体成功或整体回滚。

## 6. 错误处理

所有错误使用 RFC 9457 `ProblemDetail`，并包含稳定 `code` 字段。

| 场景 | 状态码 | 错误码 |
| --- | ---: | --- |
| `trackId` 格式无效 | 400 | `VALIDATION_FAILED` |
| 未认证 | 401 | `UNAUTHORIZED` |
| 已认证但不是管理员 | 403 | `FORBIDDEN` |
| 歌曲不存在 | 404 | `TRACK_NOT_FOUND` |
| 歌曲已经归档 | 409 | `TRACK_ALREADY_ARCHIVED` |
| 并发版本冲突 | 409 | `TRACK_VERSION_CONFLICT` |
| 数据库不可用 | 503 | `TRACK_STORE_UNAVAILABLE` |

日志仅记录 traceId、异常类型和非敏感上下文，不记录 JWT、密码、对象存储凭据或其他敏感数据。

## 7. 安全

- 继续使用现有 JWT/OAuth2 Resource Server 和 Token Version 过滤器。
- Controller 使用方法级 `@PreAuthorize("hasRole('ADMIN')")`。
- 普通用户即使知道 `trackId` 也不能归档歌曲。
- 不新增 Security 配置规则。
- 接口不接收或输出密钥、Token、密码或对象存储凭据。
- 归档后必须通过状态过滤保证普通用户入口不可见，不依赖前端隐藏链接。

## 8. 测试计划

### 8.1 领域单元测试

- `DRAFT` 歌曲可归档。
- `PUBLISHED` 歌曲可归档。
- 归档后状态为 `ARCHIVED`。
- 归档更新 `updatedAt` 和 `updatedBy`。
- `ARCHIVED` 歌曲不可重复归档。
- 失败时不修改状态或审计字段。

### 8.2 Service 单元测试

- 归档成功并返回轻量 DTO。
- 歌曲不存在抛出 `TrackNotFoundException`。
- 已归档歌曲抛出 `TrackAlreadyArchivedException`。
- JPA 乐观锁冲突保持原样传播，不被转换为 503。
- 其他数据访问异常转换为 `TrackStoreUnavailableException`。

### 8.3 Controller 测试

- 管理员归档成功返回 200 和正确 JSON。
- 非法 `trackId` 返回 400。
- 未认证返回 401。
- 普通用户返回 403。
- 歌曲不存在返回 404。
- 歌曲已经归档返回 409 `TRACK_ALREADY_ARCHIVED`。
- 版本冲突返回 409，并断言响应体包含 `type`、`title`、`status`、`detail`、`instance`、`code`，且不包含 `expectedVersion` 或 `actualVersion`。
- 使用真实链路替换后的 `AuthenticatedUserPrincipal` 主体形态验证管理员 ID 正确传入 Service。
- 不得仅注入 `JwtAuthenticationToken` 后绕过 `TokenVersionAuthenticationFilter`。

### 8.4 MySQL 集成测试

使用 Testcontainers 启动 MySQL，不使用 H2：

- `DRAFT` 归档成功后状态、版本、更新时间和更新人正确持久化。
- `PUBLISHED` 归档成功后状态、版本、更新时间和更新人正确持久化。
- 重复归档 `ARCHIVED` 歌曲返回 409，且版本和审计字段不变。
- 并发修改触发乐观锁冲突时返回统一 409 响应体，且冲突后的 `version`、`updatedAt`、`updatedBy` 均保持不变。
- 普通用户无法归档。
- 未认证请求无法访问。
- 真实 Bearer Token 穿越完整过滤器链后，Controller 能从 `AuthenticatedUserPrincipal` 读取管理员 ID。

本接口不依赖 Redis 或 MinIO，不启动无关测试容器。

## 9. API 文档

OpenAPI 注解必须完整，包括：

- `@Operation`
- `@ApiResponses`
- `@ApiResponse`
- `@Schema`
- `@Parameter`

新增 API 文档：

- `docs/api/track/archive-track.md`

文档必须包含：

- 方法和路径。
- 权限。
- 请求说明。
- 归档后的数据保留规则。
- 成功响应示例。
- 错误码表。
- `409 TRACK_ALREADY_ARCHIVED` 响应示例。
- `409 TRACK_VERSION_CONFLICT` 响应示例，明确不含 `expectedVersion` 和 `actualVersion`。
- 可直接执行的 curl 示例。

## 10. 验证命令

Windows PowerShell：

```powershell
.\gradlew.bat test
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat check
```

完成定义还要求：

- 代码可编译。
- 测试全部通过。
- 无 TODO、FIXME 或假实现。
- 无硬编码密钥。
- 无敏感信息日志。
- 不返回 Entity。
- 不新增未使用代码。
- 不新增依赖。
- 不新增数据库迁移。

## 11. 预计变更文件

生产代码：

- `src/main/java/org/example/music/track/controller/TrackArchiveController.java`
- `src/main/java/org/example/music/track/service/TrackArchiveService.java`
- `src/main/java/org/example/music/track/service/TrackArchiveTransactionService.java`
- `src/main/java/org/example/music/track/domain/TrackEntity.java`
- `src/main/java/org/example/music/track/dto/TrackArchiveResponse.java`
- `src/main/java/org/example/music/track/dto/TrackArchiveResponseMapper.java`
- `src/main/java/org/example/music/track/exception/TrackAlreadyArchivedException.java`
- `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`

测试代码：

- `src/test/java/org/example/music/track/controller/TrackArchiveControllerTest.java`
- `src/test/java/org/example/music/track/service/TrackArchiveServiceTest.java`
- `src/test/java/org/example/music/track/TrackArchiveIntegrationTest.java`
- `src/test/java/org/example/music/track/domain/TrackEntityTest.java`

文档：

- `docs/api/track/archive-track.md`
- `docs/superpowers/specs/2026-10-29-admin-archive-track-design.md`

## 12. 明确默认项

- 没有请求版本号，因此并发冲突由实体 `@Version` 生成的条件更新保证；成功响应返回递增后的版本号。
- 归档接口的乐观锁冲突直接由全局 `ObjectOptimisticLockingFailureException` 处理器输出，不转换为携带版本字段的 `TrackVersionConflictException`。
- 归档成功状态码使用 `200`，而不是 `204`，因为歌曲资源已存在且返回归档结果。
- `updatedAt` 作为本次归档后的最后修改时间；不新增 `archived_at` 字段。
- 重复归档不视为成功，返回稳定错误码 `TRACK_ALREADY_ARCHIVED`。
- 归档不删除数据库或对象存储数据；物理删除由后续独立删除接口负责。
