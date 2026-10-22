# 注册用户

## 接口

- 方法：`POST`
- 路径：`/api/v1/auth/register`
- 权限：公开，无需认证
- Content-Type：`application/json`
- 成功状态码：`201 Created`

创建普通用户。请求不能指定角色；所有注册用户固定为 `USER`。`ADMIN` 角色由后续用户信息管理接口授权。

## 请求字段

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `username` | string | 是 | 3-20 位英文字母、数字、下划线；全局唯一 |
| `password` | string | 是 | 8-128 个字符；HTTPS 传输；服务端 BCrypt 存储 |
| `email` | string/null | 否 | 合法邮箱；最多 120 个字符；保存为小写；全局唯一 |
| `code` | string/null | 条件必填 | 6 位数字；提供 `email` 时必须提供，否则必须为空或不传 |

### 请求示例

```json
{
  "username": "alice_2026",
  "password": "correct-password",
  "email": "alice@example.com",
  "code": "123456"
}
```

## 成功响应

```json
{
  "id": 42,
  "username": "alice_2026",
  "email": "alice@example.com",
  "userRole": "USER",
  "createdAt": "2026-10-22T12:34:56Z"
}
```

`createdAt` 使用 UTC、ISO-8601。未提供邮箱时 `email` 为 `null`。响应不包含密码、密码摘要或验证码。

## 错误格式

所有错误使用 RFC 9457 `ProblemDetail`，并包含稳定扩展字段 `code`。

```json
{
  "type": "urn:problem:music:validation_failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more request fields are invalid",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "errors": {
    "emailCodePairValid": "code is required when email is present and is not allowed otherwise"
  }
}
```

| HTTP | `code` | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 请求格式、字段长度、条件必填或 JSON 不合法 |
| 409 | `USERNAME_ALREADY_EXISTS` | 用户名已存在 |
| 409 | `EMAIL_ALREADY_EXISTS` | 邮箱已存在 |
| 409 | `USER_ALREADY_EXISTS` | 并发唯一约束冲突 |
| 409 | `EMAIL_CODE_ALREADY_USED` | 验证码已经消费 |
| 422 | `EMAIL_CODE_INVALID` | 验证码不存在、已过期或不匹配 |
| 429 | `EMAIL_CODE_ATTEMPTS_EXCEEDED` | 验证码错误尝试达到 5 次 |
| 503 | `VERIFICATION_CODE_STORE_UNAVAILABLE` | Redis 不可用或超时 |
| 500 | `INTERNAL_ERROR` | 未预期的服务端错误 |

## Redis 验证码语义

- Redis key：`music:verification:code:REGISTER:<normalized-email>`。
- 验证码保存为 BCrypt 摘要，不保存明文。
- 验证码 TTL 为 300 秒；过期和不存在统一返回 `EMAIL_CODE_INVALID`。
- 同一邮箱写入新验证码会覆盖旧验证码，并重置尝试次数。
- 每个验证码最多错误尝试 5 次。
- 验证码验证通过后创建 MySQL 用户；数据库提交成功后按验证码 `version` 原子标记 `usedAt`。
- 数据库写入失败时不消费验证码。
- Redis 消费失败不会撤回已经提交的用户；服务端记录不含验证码和邮箱的告警。
- 邮箱大小写不敏感，统一转小写后匹配和保存。

## curl 示例

```bash
curl --request POST 'http://localhost:8080/api/v1/auth/register' \
  --header 'Content-Type: application/json' \
  --data '{
    "username": "alice_2026",
    "password": "correct-password",
    "email": "alice@example.com",
    "code": "123456"
  }'
```

查看 OpenAPI：

- JSON：`GET /v3/api-docs`
- UI：`GET /swagger-ui.html`