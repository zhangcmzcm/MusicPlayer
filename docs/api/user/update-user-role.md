# 修改用户角色

## 接口

- 方法：`PATCH`
- 路径：`/api/v1/users/{id}/role`
- 权限：本次实际登录身份必须为 `ADMIN`，且数据库最高角色必须仍为 `ADMIN`
- Content-Type：`application/json`
- 成功状态码：`200 OK`

管理员只能修改其他用户的角色，不能修改自己的角色。路径 `id` 必须是正整数。

## 请求

```http
PATCH /api/v1/users/42/role
Authorization: Bearer <admin-access-token>
Content-Type: application/json
```

```json
{
  "role": "ADMIN"
}
```

`role` 只能是 `USER` 或 `ADMIN`。

## 成功响应

```json
{
  "id": 42,
  "username": "target_user",
  "role": "ADMIN"
}
```

相同角色请求是幂等操作，同样返回 `200`，但不递增 Token Version、不修改用户，也不写角色变更审计。

## 权限和安全语义

- JWT `role` 表示本次实际登录身份；数据库 `users.user_role` 表示最高权限角色。
- 认证过滤器在单条 MySQL 查询中读取最高角色和 `token_version`，并校验登录角色不超过最高角色。
- 角色、Token Version 和 `user_role_history` 在同一 MySQL 事务中更新。
- 角色变更提交后，目标用户旧 Access Token 立即返回 `401 TOKEN_REVOKED`，需要重新登录。
- Redis 不参与 Token Version 或授权判断；Redis 清空或不可用不会让旧 Token 复活。
- MySQL 授权状态不可用时安全失败，返回 `503 AUTH_STATE_STORE_UNAVAILABLE`。
- 降级最后一名管理员返回 `409 LAST_ADMIN_CANNOT_BE_DEMOTED`，系统始终保留至少一名管理员。
- 当前任务不包含 Refresh Token 或独立强制下线接口。

## 并发语义

角色变更事务首先锁定单行 `user_role_change_guard`，然后使用锁定读检查管理员数量，并通过 `id + previous_role` 条件更新防止外部写入生成失真审计。

- 两名管理员互相并发降级时，最多一个请求成功。
- 条件更新未命中返回 `409 ROLE_UPDATE_CONFLICT`，不自动重试且不写审计。
- 已在角色变更提交前完成认证查询的请求可以完成；变更提交后的任何认证查询都拒绝旧 Token。

## 审计记录

`user_role_history` 保存：

- 操作者用户 ID
- 目标用户 ID
- 数据库真实发生的 previous/new role
- UTC 修改时间
- 服务端校验或生成的 `traceId`

不记录 JWT、密码摘要、Token Version 或请求体。审计外键采用 `ON DELETE RESTRICT`；有审计记录的用户不可物理删除。

## 错误格式

错误使用 RFC 9457 `ProblemDetail`，包含稳定字段 `code`。

| HTTP | `code` | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 路径 ID 或 `role` 不合法 |
| 401 | `INVALID_TOKEN` | JWT 必要 claims 缺失或格式非法 |
| 401 | `UNAUTHORIZED` | 未携带有效 Bearer Token |
| 401 | `TOKEN_REVOKED` | Token Version 不匹配或登录角色超过数据库最高角色 |
| 403 | `FORBIDDEN` | 本次登录身份不是管理员 |
| 403 | `ADMIN_PRIVILEGE_REVOKED` | Service 事务中确认操作者数据库最高角色已降级 |
| 403 | `CANNOT_MODIFY_SELF_ROLE` | 尝试修改自己 |
| 404 | `USER_NOT_FOUND` | 目标用户不存在 |
| 409 | `LAST_ADMIN_CANNOT_BE_DEMOTED` | 操作会导致管理员清零 |
| 409 | `ROLE_UPDATE_CONFLICT` | 条件更新未命中 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |
| 503 | `AUTH_STATE_STORE_UNAVAILABLE` | MySQL 授权状态不可用 |

## curl 示例

```bash
curl --request PATCH "http://localhost:8080/api/v1/users/42/role" \
  --header "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  --header "Content-Type: application/json" \
  --data '{"role":"ADMIN"}'
```

查看 OpenAPI：

- JSON：`GET /v3/api-docs`
- UI：`GET /swagger-ui.html`
