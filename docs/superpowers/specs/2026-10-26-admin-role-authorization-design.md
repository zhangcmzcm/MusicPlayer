# 管理员角色授权设计

日期：2026-10-26

## 1. 目标与范围

本次只实现一个 HTTP 接口：

- `PATCH /api/v1/users/{id}/role`
- 用途：由已登录管理员修改其他用户的角色。
- 权限：JWT 中实际登录身份必须是 `ADMIN`。
- 目标用户不能是当前登录用户。

非目标：

- 用户资料修改、密码修改、注销。
- Refresh Token 和无感刷新接口。
- 用户列表、禁用、删除。
- 角色授权的批量操作。

## 2. 接口契约

### 2.1 请求

```http
PATCH /api/v1/users/{id}/role
Authorization: Bearer <admin-access-token>
Content-Type: application/json
```

路径参数：

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `id` | integer | 目标用户 ID，必须为正数且不能等于当前用户 ID |

请求体：

```json
{
  "role": "ADMIN"
}
```

`role` 只能是 `USER` 或 `ADMIN`。

### 2.2 成功响应

状态码：`200 OK`

```json
{
  "id": 42,
  "username": "target_user",
  "role": "ADMIN"
}
```

响应不返回密码摘要、JWT、Ticket 或审计历史中的内部字段。

## 3. 授权规则

1. 从 Bearer JWT 中获取当前用户 ID 和实际登录角色。
2. JWT 角色必须是 `ADMIN`。
3. 路径用户 ID 不能等于当前用户 ID。
4. 目标用户必须存在。
5. 只允许目标角色 `USER` 或 `ADMIN`。
6. 只修改 `users.user_role`，不修改用户名、邮箱、密码摘要或登录时间。
7. 角色更新和角色历史写入同一个 MySQL 事务。

自授权必须返回 `403 CANNOT_MODIFY_SELF_ROLE`，避免管理员误操作把自己降权或提权。

## 4. Token Version

角色变更需要让目标用户现有的 Access Token 立即失效。

### 4.1 Token 声明

JWT 新增：

| Claim | 说明 |
| --- | --- |
| `tokenVersion` | 用户当前授权版本，整数 |

登录签发 JWT 时读取当前版本并写入 claim。用户角色选择 Ticket 不直接签发 JWT，Ticket 保存原始登录方式。

### 4.2 存储

Redis key：

```text
music:auth:user-token-version:<user-id>
```

值为非负整数。首次访问不存在时视为版本 `0`。

### 4.3 请求校验

每个需要认证的请求在 Bearer 认证完成后执行 Token Version 校验：

1. 读取 JWT `tokenVersion`。
2. 读取 Redis 当前版本。
3. 版本不一致时返回 `401 TOKEN_REVOKED`。
4. Redis 不可用时返回 `503 AUTH_STATE_STORE_UNAVAILABLE`，不允许安全校验静默跳过。
5. 无 `tokenVersion` 的旧 Token 返回 `401 TOKEN_REVOKED`。

角色授权接口在数据库更新前先原子递增 Redis Token Version：

1. Redis 不可用时不修改数据库。
2. Token Version 递增成功后，若 MySQL 更新失败，目标用户会被安全地强制重新登录。
3. 该策略优先防止降权后旧 Token 继续使用，而不是保证登录会话绝对连续。

## 5. 角色变更审计

新增 `user_role_history` 表：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT AUTO_INCREMENT | 主键 |
| `target_user_id` | INT | 被修改用户 ID |
| `actor_user_id` | INT | 操作管理员 ID |
| `previous_role` | VARCHAR(10) | 修改前角色 |
| `new_role` | VARCHAR(10) | 修改后角色 |
| `changed_at` | DATETIME(6) | UTC 修改时间 |
| `trace_id` | VARCHAR(64) | 请求追踪 ID |

索引：

- `ix_user_role_history_target_changed_at (target_user_id, changed_at)`
- `ix_user_role_history_actor_changed_at (actor_user_id, changed_at)`

建议增加目标用户和操作者外键；若现有 `users` 表由外部系统维护，则迁移前确认外键约束策略。

角色历史记录与角色更新在同一事务提交。审计日志不得记录 JWT、Token Version 原值之外的秘密或请求体。

## 6. 错误协议

所有错误使用 RFC 9457 `ProblemDetail`，包含稳定扩展字段 `code`。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 路径 ID 或 `role` 不合法 |
| 401 | `UNAUTHORIZED` | 无有效 Bearer JWT |
| 401 | `TOKEN_REVOKED` | Token 版本过期或缺失 |
| 403 | `FORBIDDEN` | 当前 JWT 不是管理员 |
| 403 | `CANNOT_MODIFY_SELF_ROLE` | 尝试修改自己的角色 |
| 404 | `USER_NOT_FOUND` | 目标用户不存在 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |
| 503 | `AUTH_STATE_STORE_UNAVAILABLE` | Redis Token Version 不可用 |

`403 FORBIDDEN` 与 `403 CANNOT_MODIFY_SELF_ROLE` 分开，便于前端区分无权限和自授权限制。

## 7. 组件边界

| 组件 | 职责 |
| --- | --- |
| `UserRoleController` | HTTP、路径/请求校验、OpenAPI |
| `UserRoleAuthorizationService` | 管理员校验、目标用户校验、角色更新事务 |
| `UserTokenVersionService` | Redis Token Version 读取和递增 |
| `TokenVersionAuthenticationFilter` | Bearer 认证后校验 JWT Token Version |
| `UserRoleHistoryRepository` | 角色历史写入 |
| `UserRepository` | 查询目标用户、更新角色 |
| `JwtTokenService` | 登录签发 JWT 时写入 Token Version |
| `GlobalExceptionHandler` | RFC 9457 错误映射 |

Controller 不写权限业务逻辑，Service 不返回 JPA Entity，Repository 不写授权判断。

## 8. 数据库迁移

新增数据库迁移：

- `V4__create_user_role_history.sql`

迁移内容：

- 创建 `user_role_history` 表。
- 创建目标用户和操作者索引。
- 根据现有数据库权限决定是否增加外键约束。

本接口不修改 `users` 表的 `user_role`、`password_token` 或其他现有列。Token Version 存放在 Redis，不修改用户表。

## 9. 测试计划

Web 测试：

- 无 Authorization 返回 401。
- 普通用户 JWT 调用返回 403。
- 管理员 JWT 可以调用。
- 修改自己返回 403 `CANNOT_MODIFY_SELF_ROLE`。
- 目标不存在返回 404。
- 非法角色返回 400。
- 响应不包含 Token、密码摘要或 Token Version。

服务测试：

- 目标用户角色从 `USER` 改为 `ADMIN`。
- 目标用户角色从 `ADMIN` 改为 `USER`。
- 记录 previous/new role、actor、target、UTC 时间和 traceId。
- Redis Token Version 在数据库操作前递增。
- Redis 不可用时不修改数据库。
- 旧 JWT 版本不匹配返回 `TOKEN_REVOKED`。
- 并发角色变更只生成对应版本，不丢失审计记录。

Redis + MySQL Testcontainers 测试：

- Token Version 首次访问为 0。
- 角色更新后版本递增。
- 旧 Token 立即失效，新 Token 可访问。
- `user_role_history` 与角色更新同事务。
- 目标用户不存在、自授权和普通用户越权行为与 HTTP 响应一致。

测试命名使用 `should_<result>_when_<condition>`。

## 10. API 文档

新增：

- `docs/api/user/update-user-role.md`

文档包含权限、请求、成功响应、RFC 9457 错误、Token Version 失效语义、审计说明和 curl 示例。

## 11. 已确认决策

- 单接口 `PATCH /api/v1/users/{id}/role`。
- 只有实际登录身份为 `ADMIN` 可以授权。
- 只能修改其他用户，不能修改自己。
- 角色值使用现有 `USER/ADMIN` 枚举。
- 角色变更立即通过 Token Version 失效目标用户旧 JWT。
- Token Version 存 Redis，不增加 `users` 字段。
- Redis 更新先于数据库更新，失败时采取安全优先策略。
- 新增 `user_role_history` 审计表。
- Refresh Token 和刷新接口另行实现。