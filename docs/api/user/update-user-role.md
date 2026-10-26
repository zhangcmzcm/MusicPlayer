# 修改用户角色

## 接口

- 方法：`PATCH`
- 路径：`/api/v1/users/{id}/role`
- 权限：`ADMIN`
- Content-Type：`application/json`
- 成功状态码：`200 OK`

管理员只能修改其他用户的角色，不能修改自己的角色。

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

## 权限和安全语义

- 只有 JWT 实际登录身份为 `ADMIN` 才能调用。
- 目标用户 ID 不能等于当前管理员 ID。
- 角色更新与 `user_role_history` 审计记录在同一事务中提交。
- 角色变更会递增目标用户 Token Version。
- 目标用户旧 Access Token 立即返回 `401 TOKEN_REVOKED`，需要重新登录。
- Redis 不可用时不会修改数据库，返回 `503 AUTH_STATE_STORE_UNAVAILABLE`。
- 当前任务不包含 Refresh Token；后续接入无感刷新接口后，客户端可通过 Refresh Token 自动恢复会话。

## 审计记录

`user_role_history` 保存：

- 操作者用户 ID
- 目标用户 ID
- 修改前角色
- 修改后角色
- UTC 修改时间
- `traceId`

不记录 JWT、密码摘要、Token Version 或请求体。

## 错误格式

错误使用 RFC 9457 `ProblemDetail`，包含稳定字段 `code`。

| HTTP | `code` | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 路径 ID 或 `role` 不合法 |
| 401 | `UNAUTHORIZED` | 未携带有效 Bearer Token |
| 401 | `TOKEN_REVOKED` | Token Version 不匹配或缺失 |
| 403 | `FORBIDDEN` | 当前用户不是管理员 |
| 403 | `CANNOT_MODIFY_SELF_ROLE` | 尝试修改自己 |
| 404 | `USER_NOT_FOUND` | 目标用户不存在 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |
| 503 | `AUTH_STATE_STORE_UNAVAILABLE` | Redis Token Version 不可用 |

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