# 管理员授权安全加固设计

日期：2026-10-27

## 1. 目标与范围

本次重构管理员授权链路，目标是消除角色状态、Token 失效状态和审计写入跨存储、非原子造成的安全问题。

覆盖范围：

- `PATCH /api/v1/users/{id}/role`
- 管理员角色变更的数据库事务、并发控制和审计
- 登录签发 JWT 时的角色与授权版本快照
- 认证请求中的 Token Version 校验和权限生成
- 环境变量驱动的首个管理员初始化
- 与上述能力直接相关的迁移、错误协议、API 文档和测试

非目标：

- Refresh Token、无感刷新和独立 Token 吊销接口
- 用户删除、禁用、批量授权和角色管理界面
- 用户列表、资料修改和密码修改
- Redis 分布式限流、验证码、登录 Ticket 等既有非授权用途

## 2. 当前问题与目标不变量

当前实现依赖 `users.user_role` 和 Redis Token Version 两套状态，读写不原子，存在以下风险：

1. 角色校验和 Token Version 读取分步执行，并发登录可能签发“旧角色 + 新版本”的有效 JWT。
2. Redis key 不存在被当作版本 `0`，Redis 数据丢失会让已吊销 Token 复活。
3. 管理员可以互相降权，导致管理员数量降为 `0`。
4. Redis 版本递增早于数据库事务，数据库或审计写入失败会误吊销用户 Token。
5. 无乐观锁或条件更新时，并发变更会生成不真实的审计记录。
6. Controller 自行解析 JWT claims，异常 Token 可能进入 `500`。
7. 客户端 `X-Trace-Id` 未校验且可超过数据库列长，既可伪造也可触发事务回滚。

目标不变量：

- MySQL 是角色和授权版本的唯一权威状态。
- 角色变更和 Token Version 递增具有原子性。
- 角色变更提交后，旧授权版本的 JWT 立即失效。
- 系统始终至少保留一名管理员。
- 审计记录只反映数据库中真实发生的变化。
- Redis、JWT claims 或客户端 TraceId 均不能单独授予或延长权限。

## 3. 核心架构

### 3.1 权威授权状态

`users` 表新增：

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `token_version` | `BIGINT` | `NOT NULL DEFAULT 0` | 用户授权版本；角色变更时递增 |

`users.user_role` 和 `users.token_version` 位于同一行，因此可以在同一个数据库事务中读取、锁定和更新。

JWT 保存签发时的 `sub`、`role`、`tokenVersion` 快照。只有 `sub` 和 `tokenVersion` 是认证必要 claims；`role` 是可选兼容快照，不参与认证或授权，可以缺失或被忽略。

最终授权主体的 `GrantedAuthority` 必须由同一次数据库授权状态查询返回的 `user_role` 生成，并替换 `SecurityContext` 中由 JWT converter 创建的临时 `Authentication`。`JwtAuthenticationConverterProvider` 不得再从 `role` claim 生成 `ROLE_` 权限。

Redis 从认证正确性关键路径移除。后续可以增加不影响安全的性能缓存，但本设计不依赖 Redis 保证授权正确性。

### 3.2 原子签发快照

登录或角色选择完成时，通过单条查询读取：

```text
userId + userRole + tokenVersion
```

JWT 直接使用该快照签发，签发过程中不得再次读取 `tokenVersion`。

登录签发使用普通一致性读，不需要额外持有用户行锁；正确性来自单条授权状态查询和后续版本比较：

- 快照在角色变更提交前生成：签发旧角色和旧版本；角色变更随后递增版本，该 JWT 在变更提交后立即失效。
- 快照在角色变更提交后生成：读取到新角色和新版本，签发新授权状态。
- 签发过程中不得再次读取角色或版本，避免组合出跨变更的授权状态。

## 4. 角色变更事务

`PATCH /api/v1/users/{id}/role` 的事务顺序：

1. 从已认证主体读取用户 ID；Controller 层规范化并校验目标用户 ID 和目标角色。
2. 将 `SELECT ... FROM user_role_change_guard WHERE id = 1 FOR UPDATE` 作为事务内第一个数据库交互，锁定单行 guard。
3. 从数据库读取操作者当前角色；操作者不是 `ADMIN` 时返回 `403 ADMIN_PRIVILEGE_REVOKED`。
4. 目标用户不存在时返回 `404 USER_NOT_FOUND`。
5. 目标用户等于操作者时返回 `403 CANNOT_MODIFY_SELF_ROLE`。
6. 如果目标角色等于当前角色，返回幂等 `200`，不递增版本、不修改用户、不写角色变更审计。
7. 如果目标当前是 `ADMIN` 且目标角色是 `USER`，使用锁定读 `SELECT id FROM users WHERE user_role = 'ADMIN' FOR UPDATE` 读取管理员行并按返回结果集计数；数量为 `1` 时返回 `409 LAST_ADMIN_CANNOT_BE_DEMOTED`。
8. 条件更新目标用户：

```sql
UPDATE users
SET user_role = :new_role,
    token_version = token_version + 1,
    updated_at = :changed_at
WHERE id = :target_user_id
  AND user_role = :previous_role
```

9. 条件更新影响行数为 `0` 时返回 `409 ROLE_UPDATE_CONFLICT`，不自动重试且不生成虚假审计。该分支仅防御绕过 guard 的直接数据写入；正常角色变更写入者都必须持有 guard 锁。
10. 在同一事务写入 `user_role_history`。
11. 提交后返回 `200` 和用户当前角色。

`user_role_change_guard` 用于串行化所有会改变角色的操作。guard 锁必须是事务内首个数据库交互，且管理员计数必须使用上述锁定读，不能使用普通一致性 `COUNT(*)`。角色变更频率低，使用单行 guard 加管理员行锁定读可可靠保护管理员数量不变量。

管理员数量规则：

- 允许管理员降级其他管理员，但必须至少保留一名管理员。
- 两名管理员互相并发降级时，guard 锁使请求串行；第一个成功后，第二个会因导致管理员清零而失败。
- 未来用户禁用、软删除或删除接口必须复用相同 guard 和最后管理员规则。
- 本设计不新增强制下线接口；相同角色请求只做幂等角色确认。

## 5. 登录与认证

### 5.1 登录

- 角色选择 Ticket 仍只保存登录上下文，不签发 JWT。
- 角色选择后读取授权状态快照，并确认请求角色不超过数据库当前角色。
- JWT 使用该快照中的角色和版本，不进行第二次 Redis 或数据库读取。
- 数据库不可用时登录失败，不签发缺少安全状态的 Token。

### 5.2 每次认证请求

认证过滤器执行：

1. 验证 Bearer JWT 签名、有效期和必要 claims。
2. `sub` 必须是有效正整数，`tokenVersion` 必须是非负整数；`role` claim 可选，不参与认证或授权。
3. 必要 claims 缺失或格式非法时返回 `401 INVALID_TOKEN`，不得进入 Controller 后转换为 `500`。
4. 通过单条 MySQL 查询同时读取 `user_role` 和 `token_version`，禁止拆成两次读取。
5. 用户不存在或 JWT 版本与同一行查询结果不相等时返回 `401 TOKEN_REVOKED`。
6. 数据库不可用时返回 `503 AUTH_STATE_STORE_UNAVAILABLE`，禁止跳过校验。
7. 使用同一查询快照中的 `user_role` 创建 `ROLE_` 权限，构建应用 `Authentication` 并替换 `SecurityContext` 中 JWT converter 生成的临时认证对象；JWT 的 `role` claim 永远不能覆盖数据库角色。
8. Controller 只接收替换后的认证主体 ID，不解析 JWT `role` claim。

该方案保证 Redis 重启、清空或完全不可用都不会让历史吊销 Token 复活。Redis 可继续服务于其他非授权功能。这里的“立即失效”以授权状态查询为线性化点：角色变更提交前已经完成授权查询的请求可以继续完成，但不能把旧权限用于后续请求；变更提交后的任何认证查询都必须拒绝旧版本。

## 6. 最后管理员保护

`user_role_change_guard` 为单行表，主键固定为 `1`。所有角色变更事务必须把 guard 的 `SELECT ... FOR UPDATE` 作为首个数据库交互，然后使用 `SELECT id FROM users WHERE user_role = 'ADMIN' FOR UPDATE` 锁定读取管理员行并按结果集计数。

保护规则：

- 当管理员数量为 `1` 且操作会把该管理员改成 `USER` 时拒绝操作。
- 当管理员数量大于 `1` 时允许降级任意非本人管理员。
- 删除管理员用户的未来接口必须先持有相同 guard 锁。
- bootstrap 创建首个管理员也持有该 guard 锁，避免多实例启动竞态。

## 7. 初始管理员初始化

### 7.1 配置

通过部署环境变量配置：

| 环境变量 | 必填 | 说明 |
| --- | --- | --- |
| `ADMIN_BOOTSTRAP_USERNAME` | 管理员数量为 `0` 时必填 | 首个管理员用户名 |
| `ADMIN_BOOTSTRAP_EMAIL` | 管理员数量为 `0` 时必填 | 首个管理员邮箱 |
| `ADMIN_BOOTSTRAP_PASSWORD` | 管理员数量为 `0` 时必填 | 首个管理员初始明文密码，仅存在于部署环境 |

部署使用需求指定的用户名、邮箱和密码值；密码不得写入 `.env.example`、迁移、源代码、日志、测试快照或 Git。正式部署前应更换已在沟通渠道出现过的初始密码。

### 7.2 初始化行为

`AdminBootstrapInitializer` 在数据库迁移完成后执行：

1. 查询并锁定 `user_role_change_guard`。
2. 如果已存在至少一名管理员，忽略 bootstrap 配置并结束。
3. 如果没有管理员且任一配置缺失，启动失败并报告缺失变量名。
4. 如果目标用户名或邮箱已被占用，启动失败并报告配置冲突，不把已有普通用户静默提升为管理员。
5. 使用 BCrypt `PasswordHasher` 对密码进行一次性加密。
6. 创建 `ADMIN` 用户，`token_version = 0`，审计时间使用 UTC。
7. 提交后只记录“bootstrap 管理员创建成功”和内部用户 ID；不记录密码、Token 或邮箱。

已有管理员时，即使继续提供 bootstrap 配置也不修改已有管理员的用户名、邮箱、密码或角色。

## 8. 审计与 TraceId

- 角色更新、`token_version` 递增和审计插入位于同一 MySQL 事务。
- 审计中的 `previous_role` 来自条件更新成功时的数据库状态。
- `changed_at` 使用服务端 UTC 时间。
- `TraceIdFilter` 仅接受匹配 `[A-Za-z0-9._-]{1,64}` 的客户端 `X-Trace-Id`。
- 非法或缺失输入使用服务端生成 UUID；服务端生成的值写入响应头、MDC 和审计。
- 审计不得记录密码、JWT、完整请求体或其他敏感信息。
- 新迁移增加 `target_user_id`、`actor_user_id` 外键并使用 `ON DELETE RESTRICT`。有角色审计记录的用户不可物理删除；未来删除能力必须实现软删除或用户 tombstone，并继续复用最后管理员保护。
- 若现有 `user_role_history` 存在悬挂用户 ID，迁移失败并中止部署；部署前需人工修复审计数据，禁止自动删除历史记录。

## 9. 数据库迁移

不修改已执行的 `V4__create_user_role_history.sql`。

新增：

### `V5__add_user_token_version.sql`

- 给 `users` 增加 `token_version BIGINT NOT NULL DEFAULT 0`。
- 给 `users.user_role` 增加用于管理员数量查询的索引。

### `V6__create_user_role_change_guard.sql`

- 创建 `user_role_change_guard`。
- 插入唯一 guard 行 `id = 1`。
- 对 guard 行数量增加数据库约束，确保只能有一个可锁定入口。

### `V7__harden_user_role_history.sql`

- 增加 `target_user_id` 到 `users.id` 的外键。
- 增加 `actor_user_id` 到 `users.id` 的外键。
- 外键使用 `ON DELETE RESTRICT`。
- 增加迁移前悬挂数据检查；存在异常时让迁移失败，保留审计数据并中止部署。

迁移不创建初始用户，不包含任何密码或密码哈希。

## 10. 错误协议

所有错误使用 RFC 9457 `ProblemDetail`，扩展字段 `code` 保持稳定。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 路径 ID 或角色值非法 |
| 401 | `INVALID_TOKEN` | JWT 缺少或错误解析认证必要 claims `sub`、`tokenVersion` |
| 401 | `UNAUTHORIZED` | Bearer JWT 缺失、签名或有效期非法 |
| 401 | `TOKEN_REVOKED` | 用户不存在或 Token Version 不匹配 |
| 403 | `FORBIDDEN` | 认证主体当前不是管理员 |
| 403 | `ADMIN_PRIVILEGE_REVOKED` | Service 事务内确认操作者已被降权 |
| 403 | `CANNOT_MODIFY_SELF_ROLE` | 操作者尝试修改自己的角色 |
| 404 | `USER_NOT_FOUND` | 目标用户不存在 |
| 409 | `LAST_ADMIN_CANNOT_BE_DEMOTED` | 变更会导致管理员清零 |
| 409 | `ROLE_UPDATE_CONFLICT` | 条件更新未命中，检测到绕过 guard 的并发或外部写入 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |
| 503 | `AUTH_STATE_STORE_UNAVAILABLE` | MySQL 授权状态读取失败 |

相同角色请求返回 `200` 幂等响应，不定义为错误。

## 11. 组件边界

| 组件 | 职责 |
| --- | --- |
| `UserRoleController` | HTTP 输入、Bean Validation、OpenAPI |
| `UserRoleAuthorizationService` | 角色变更事务、并发控制、最后管理员规则、审计 |
| `UserAuthorizationStateService` | 读取角色与 Token Version 快照、认证时读取权威状态 |
| `AdminBootstrapInitializer` | 环境变量驱动的首个管理员创建 |
| `JwtAuthenticationConverterProvider` | 仅完成签名后的 JWT 临时认证转换，不从 `role` claim 生成应用权限 |
| `TokenVersionAuthenticationFilter` | 单条查询 MySQL 权威状态、版本校验并替换数据库权限 `Authentication` |
| `JwtTokenService` | 使用授权状态快照签发 JWT |
| `TraceIdFilter` | 生成或规范化 TraceId |
| `UserRoleHistoryRepository` | 写入审计记录 |
| `GlobalExceptionHandler` | RFC 9457 错误映射 |

`UserTokenVersionService` 的 Redis 实现从授权链路移除；仅在没有其他调用者时删除，避免生成未使用代码。Controller 不返回 Entity，Service 不向 Controller 暴露 JPA Entity，Repository 不实现授权规则。

## 12. 测试计划

### Web 测试

- 无 Authorization、非法 JWT、认证必要 claims 缺失分别返回约定 401；缺失或非法 `role` claim 不影响数据库权限判定，伪造 `role=ADMIN` 不能获得管理员权限。
- 普通用户调用返回 403。
- 管理员可修改其他用户角色。
- 修改自己返回 `403 CANNOT_MODIFY_SELF_ROLE`。
- 目标不存在返回 404。
- 非法路径 ID 和角色返回 400。
- 最后管理员降级返回 `409 LAST_ADMIN_CANNOT_BE_DEMOTED`。
- 条件更新被外部写入绕过时返回 `409 ROLE_UPDATE_CONFLICT` 且不写审计。
- 响应不包含密码摘要、JWT 或 Token Version。
- 非法 `X-Trace-Id` 不导致 500，且响应使用服务端 TraceId。

### Service 单元测试

- `USER -> ADMIN`、`ADMIN -> USER` 成功路径。
- 操作者当前角色不是管理员返回 `ADMIN_PRIVILEGE_REVOKED`。
- 同角色请求幂等且不写审计。
- 最后管理员保护在不同角色组合下正确。
- 条件更新失败时不写审计。
- 审计记录 previous/new role、actor、target、UTC 时间和规范化 TraceId。

### MySQL Testcontainers 集成测试

- 角色和 Token Version 同事务提交。
- 数据库或审计写入失败时角色和版本一起回滚。
- 旧 Token 在角色变更提交后立即失效，新快照 Token 有效；认证查询必须单条读取角色和版本，禁止跨变更组合权限。
- Redis 被清空或不可用不影响 Token 校验结果。
- 两名管理员互相并发降级最多一个成功，管理员数量不为 `0`。
- 目标降权与并发登录签发不会产生长期有效的旧权限 Token。
- 同用户并发角色变更生成准确审计。
- V5 至 V7 迁移在空库和已有合法审计数据上成功，存在悬挂外键数据时失败并保留数据。

### Bootstrap 测试

- 空管理员表首次启动创建一个管理员。
- 已有管理员时跳过初始化并保持数据不变。
- 配置缺失、用户名冲突、邮箱冲突分别启动失败。
- 数据库仅保存 BCrypt 密码摘要。
- 多实例并发启动只创建一个管理员。
- 日志和异常不包含密码。

最终执行 `gradlew check`。

## 13. API 文档

更新：

- `docs/api/user/update-user-role.md`
- `docs/api/auth/login.md`

文档必须说明：

- Token Version 权威存储已迁移到 MySQL。
- 角色变更立即失效旧 Token。
- 最后管理员保护。
- RFC 9457 错误码。
- 相同角色幂等语义。
- bootstrap 仅通过部署环境配置，不提供 HTTP 管理接口。
- 可执行 curl 示例。

## 14. 实施顺序

1. 数据库迁移和 Entity 映射。
2. 授权状态读取组件和 JWT 原子快照签发。
3. `PATCH /api/v1/users/{id}/role` 的事务、并发保护、审计和测试。
4. 认证过滤器和权限生成改造。
5. TraceId 规范化和错误协议修正。
6. Admin Bootstrap Initializer 和测试。
7. OpenAPI、API 文档、格式化和 `gradlew check`。

实施仍遵守“一次完成一个 HTTP 接口”的项目规则；登录、认证过滤器和 bootstrap 属于既有接口或启动能力的必要重构，不新增其他 HTTP 接口。

## 15. 验收标准

- 管理员数量永远无法通过 API 降为 `0`。
- 角色变更、Token Version 和审计具有数据库原子性。
- 每个认证请求以 MySQL 当前角色和版本为准。
- Redis 丢失或不可用不会延长任何 JWT 生命周期。
- 并发登录不能签发可绕过任何角色变更的旧权限 Token。
- 初始管理员只在无管理员时通过环境变量创建，密码不进入 Git 或日志。
- 无 TODO、假实现、敏感信息日志和 `ddl-auto=update`。
- 单元测试、Testcontainers 集成测试和 `gradlew check` 通过。
