# 注册用户

## 接口

- 方法：`POST`
- 路径：`/api/v1/auth/register`
- 权限：公开，无需认证
- Content-Type：`application/json`
- Accept：`application/json`
- 成功状态码：`201 Created`

创建一个普通用户。请求不能指定角色；所有注册用户固定为 `USER`。`ADMIN` 角色由后续用户信息管理接口授权。

## 请求字段

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `username` | string | 是 | 3-20 位英文字母、数字、下划线；全局唯一 |
| `password` | string | 是 | 8-128 个字符；通过 HTTPS 传输；服务端 BCrypt 存储 |
| `email` | string/null | 否 | 合法邮箱；最多 120 个字符；保存为小写；全局唯一 |
| `code` | string/null | 条件必填 | 6 位数字；提供 `email` 时必须提供，否则必须为空或不传 |

### 请求示例

不使用邮箱：

```json
{
  "username": "alice_2026",
  "password": "correct-password"
}
```

使用邮箱和验证码：

```json
{
  "username": "alice_2026",
  "password": "correct-password",
  "email": "alice@example.com",
  "code": "123456"
}
```

## 成功响应

状态码：`201 Created`

```json
{
  "id": 42,
  "username": "alice_2026",
  "email": "alice@example.com",
  "userRole": "USER",
  "createdAt": "2026-10-21T12:34:56Z"
}
```

- `email` 未提供时返回 `null`。
- `createdAt` 使用 UTC、ISO-8601。
- 响应不包含密码、密码摘要或验证码。

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
| 409 | `USER_ALREADY_EXISTS` | 并发插入触发唯一约束且无法安全区分冲突字段 |
| 409 | `EMAIL_CODE_ALREADY_USED` | 验证码已经消费 |
| 422 | `EMAIL_CODE_INVALID` | 验证码不存在或不匹配 |
| 422 | `EMAIL_CODE_EXPIRED` | 验证码已过期 |
| 429 | `EMAIL_CODE_ATTEMPTS_EXCEEDED` | 验证码错误尝试达到 5 次 |
| 500 | `INTERNAL_ERROR` | 未预期的服务端错误 |

### 冲突示例

```json
{
  "type": "urn:problem:music:username_already_exists",
  "title": "User conflict",
  "status": 409,
  "detail": "Username is already registered",
  "instance": "/api/v1/auth/register",
  "code": "USERNAME_ALREADY_EXISTS"
}
```

## 验证码语义

- 邮箱可选；填写邮箱时必须提供验证码。
- 验证码只接受该邮箱用途为 `REGISTER` 的最新记录。
- 验证码以 BCrypt 摘要保存，不在数据库保存明文。
- 验证码和用户创建在同一事务内提交；用户创建失败时验证码不会被消费。
- 每个验证码最多允许 5 次错误尝试。
- 验证码只能成功消费一次。
- 邮箱大小写不敏感；服务端统一转为小写后匹配和保存。

## curl 示例

不使用邮箱：

```bash
curl --request POST 'http://localhost:8080/api/v1/auth/register' \
  --header 'Content-Type: application/json' \
  --data '{
    "username": "alice_2026",
    "password": "correct-password"
  }'
```

使用邮箱和验证码：

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