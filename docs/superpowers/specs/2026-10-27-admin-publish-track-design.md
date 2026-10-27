# 管理员发布歌曲接口设计

日期：2026-10-27

状态：已确认

## 1. 目标与范围

本次只实现一个 HTTP 接口：

- `POST /api/v1/tracks/{trackId}/publish`

接口允许管理员将歌曲从 `DRAFT` 发布为 `PUBLISHED`。只有 `PUBLISHED` 状态的歌曲满足未来普通用户歌曲查询的可见条件。

本次覆盖：

- 管理员鉴权。
- `DRAFT → PUBLISHED` 状态迁移。
- 更新审计字段和 JPA 乐观锁版本。
- 发布结果 DTO、错误响应和异常映射。
- 单元测试、Controller 测试、MySQL 集成测试、OpenAPI 和 API 文档。

本次不覆盖：

- 普通用户歌曲详情、列表、搜索或播放接口。
- 歌曲归档、撤回发布或任意状态切换接口。
- 上传、替换、删除音频或封面。
- 歌曲文本元数据编辑。
- 发布审批意见、定时发布、灰度发布。
- 额外的幂等表、Redis 幂等键或请求去重机制。

## 2. 已确认决策

- 接口采用 `POST /api/v1/tracks/{trackId}/publish`。
- 请求无 Body，成功返回 `200 OK`。
- 仅已认证且具有 `ADMIN` 角色的用户可调用。
- 仅允许 `DRAFT → PUBLISHED`。
- 已发布或已归档歌曲重复调用时返回 `409 TRACK_NOT_DRAFT`。
- 使用现有 JPA `@Version` 乐观锁处理并发编辑或发布。
- 不建设额外幂等基础设施；状态条件和乐观锁足以防止重复发布产生脏数据。
- 发布后的 `PUBLISHED` 状态是未来普通用户歌曲查询的必要可见性条件。
- 本次不提前生成尚未使用的用户查询代码。
- 响应仅包含发布状态确认所需的轻量字段。

## 3. HTTP 契约

### 3.1 请求

- 方法：`POST`
- 路径：`/api/v1/tracks/{trackId}/publish`
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
  "status": "PUBLISHED",
  "version": 4,
  "updatedAt": "2026-10-27T13:20:30Z"
}
```

`updatedAt` 使用 UTC 和 ISO-8601。响应不包含 JWT、用户信息、对象存储 bucket、对象键、原始 ID3 元数据或存储凭据。

### 3.3 状态迁移

```text
DRAFT     → PUBLISHED   允许，返回 200
PUBLISHED → PUBLISHED   拒绝，返回 409 TRACK_NOT_DRAFT
ARCHIVED  → PUBLISHED   拒绝，返回 409 TRACK_NOT_DRAFT
```

发布成功时：

- `status` 设置为 `PUBLISHED`。
- `updatedAt` 更新为当前 UTC 时间。
- `updatedBy` 更新为执行发布的管理员用户 ID。
- `version` 由 JPA 乐观锁自动递增。
- `createdAt`、`createdBy`、音频、封面、时长、ID3 元数据和文本元数据保持不变。

### 3.4 可见性约束

`status = PUBLISHED` 是未来普通用户歌曲查询的必要条件。未来实现普通用户歌曲接口时：

- 列表、详情、搜索和播放入口必须排除 `DRAFT` 与 `ARCHIVED`。
- 管理接口可以读取非发布状态，但不得成为普通用户绕过状态过滤的入口。
- 本次只建立发布状态，不新增普通用户查询接口。

## 4. 架构与组件

### 4.1 `TrackPublishController`

职责仅限 HTTP 层：

- 定义 `POST /api/v1/tracks/{trackId}/publish`。
- 使用 `@PreAuthorize("hasRole('ADMIN')")`。
- 从已验证 JWT 读取管理员用户 ID。
- 调用 `TrackPublishService` 并返回 DTO。
- 不包含状态迁移或持久化逻辑。

### 4.2 `TrackPublishService`

职责：

- 按 `trackId` 查找歌曲。
- 检查歌曲是否存在且处于 `DRAFT`。
- 调用事务服务完成状态迁移。
- 将实体映射为 `TrackPublishResponse`。
- 将数据访问异常映射为稳定业务异常。

### 4.3 `TrackPublishTransactionService`

职责：

- 使用 `@Transactional` 定义原子事务边界。
- 调用 `TrackEntity.publish(userId, updatedAt)`。
- 使用 `saveAndFlush` 持久化并触发乐观锁检查。
- 不返回 Entity 给 Controller。

### 4.4 `TrackEntity`

增加 `publish(Integer userId, LocalDateTime updatedAt)` 领域方法：

- 非 `DRAFT` 状态抛出 `TrackNotDraftException`。
- 设置状态为 `PUBLISHED`。
- 更新 `updatedAt` 与 `updatedBy`。
- 复用现有 `@Version` 字段，不手动维护版本号。

### 4.5 DTO

新增 `TrackPublishResponse`：

```java
public record TrackPublishResponse(
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
- `TrackNotDraftException`
- `TrackStoreUnavailableException`

并发提交由 JPA 抛出 `ObjectOptimisticLockingFailureException`，由全局异常处理器映射为 `409 TRACK_VERSION_CONFLICT`。Service 捕获其他 `DataAccessException` 时映射为 `503 TRACK_STORE_UNAVAILABLE`，但不得吞掉乐观锁冲突。

## 5. 数据与事务

现有 `tracks.status`、`tracks.updated_at`、`tracks.updated_by` 和 `tracks.version` 字段已经满足本接口需求，因此：

- 不新增数据库迁移。
- 不新增索引或约束。
- 不修改已执行迁移。
- 不使用 `ddl-auto=update`。

事务范围只覆盖读取、状态检查、状态迁移和持久化，不包含 HTTP、日志或外部对象存储操作。状态迁移必须整体成功或整体回滚。

## 6. 错误处理

所有错误使用 RFC 9457 `ProblemDetail`，并包含稳定 `code` 字段。

| 场景 | 状态码 | 错误码 |
| --- | ---: | --- |
| `trackId` 格式无效 | 400 | `VALIDATION_FAILED` |
| 未认证 | 401 | `UNAUTHORIZED` |
| 已认证但不是管理员 | 403 | `FORBIDDEN` |
| 歌曲不存在 | 404 | `TRACK_NOT_FOUND` |
| 歌曲不是 `DRAFT` | 409 | `TRACK_NOT_DRAFT` |
| 并发版本冲突 | 409 | `TRACK_VERSION_CONFLICT` |
| 数据库不可用 | 503 | `TRACK_STORE_UNAVAILABLE` |

日志仅记录 traceId、异常类型和非敏感上下文，不记录 JWT、密码、对象存储凭据或其他敏感数据。

## 7. 安全

- 继续使用现有 JWT/OAuth2 Resource Server 和 Token Version 过滤器。
- Controller 使用方法级 `@PreAuthorize("hasRole('ADMIN')")`。
- 普通用户即使知道 `trackId` 也不能发布歌曲。
- 不新增 Security 配置规则；现有配置要求除公开认证接口外的所有请求先认证。
- 接口不接收或输出密钥、Token、密码或对象存储凭据。

## 8. 测试计划

### 8.1 领域单元测试

- `DRAFT` 歌曲可发布。
- 发布后状态为 `PUBLISHED`。
- 发布更新 `updatedAt` 和 `updatedBy`。
- `PUBLISHED` 歌曲不可再次发布。
- `ARCHIVED` 歌曲不可发布。
- 失败时不修改状态或审计字段。

### 8.2 Service 单元测试

- 发布成功并返回轻量 DTO。
- 歌曲不存在抛出 `TrackNotFoundException`。
- 非草稿抛出 `TrackNotDraftException`。
- JPA 乐观锁冲突不被转换为 503。
- 其他数据访问异常转换为 `TrackStoreUnavailableException`。

### 8.3 Controller 测试

- 管理员发布成功返回 200 和正确 JSON。
- 非法 `trackId` 返回 400。
- 未认证返回 401。
- 普通用户返回 403。
- 歌曲不存在返回 404。
- 歌曲不是草稿返回 409。
- 版本冲突返回 409。

### 8.4 MySQL 集成测试

使用 Testcontainers 启动 MySQL，不使用 H2：

- `DRAFT` 发布成功后状态、版本、更新时间和更新人正确持久化。
- 重复发布 `PUBLISHED` 歌曲返回 409，且版本和审计字段不变。
- 发布 `ARCHIVED` 歌曲返回 409。
- 并发修改或发布触发乐观锁冲突。
- 普通用户无法发布。
- 未认证请求无法访问。

本接口不依赖 Redis 或 MinIO，不启动无关测试容器。

## 9. API 文档

OpenAPI 注解必须完整，包括：

- `@Operation`
- `@ApiResponses`
- `@ApiResponse`
- `@Schema`
- `@Parameter`

新增 API 文档：

- `docs/api/track/publish-track.md`

文档必须包含：

- 方法和路径。
- 权限。
- 请求说明。
- 成功响应示例。
- 错误码表。
- 可直接执行的 curl 示例。

## 10. 验证命令

Windows PowerShell：

```powershell
.\gradlew.bat test
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

- `src/main/java/org/example/music/track/controller/TrackPublishController.java`
- `src/main/java/org/example/music/track/service/TrackPublishService.java`
- `src/main/java/org/example/music/track/service/TrackPublishTransactionService.java`
- `src/main/java/org/example/music/track/domain/TrackEntity.java`
- `src/main/java/org/example/music/track/dto/TrackPublishResponse.java`
- `src/main/java/org/example/music/track/dto/TrackPublishResponseMapper.java`

测试代码：

- `src/test/java/org/example/music/track/controller/TrackPublishControllerTest.java`
- `src/test/java/org/example/music/track/service/TrackPublishServiceTest.java`
- `src/test/java/org/example/music/track/TrackPublishIntegrationTest.java`
- `src/test/java/org/example/music/track/domain/TrackEntityTest.java`

文档：

- `docs/api/track/publish-track.md`
- `docs/superpowers/specs/2026-10-27-admin-publish-track-design.md`

## 12. 明确默认项

- 没有请求版本号，因此并发冲突由实体 `@Version` 保证。
- 发布成功状态码使用 200，而不是 201 或 204，因为歌曲资源已存在且返回了发布结果。
- `updatedAt` 作为本次发布后的最后修改时间；不新增 `published_at` 字段。
- 重复发布不视为成功，返回稳定错误码 `TRACK_NOT_DRAFT`。

