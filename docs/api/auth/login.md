# 用户登录

## 接口

- 方法：`POST`
- 路径：`/api/v1/auth/login`
- 权限：公开，无需已有 Bearer Token
- Content-Type：`application/json`
- 成功状态码：`200 OK`

支持用户名密码、邮箱密码、邮箱验证码登录。管理员认证成功后通过同一接口完成身份选择。

## 登录模式

| `loginType` | 字段 | 说明 |
| --- | --- | --- |
| `USERNAME_PASSWORD` | `account`、`password` | `account` 为用户名 |
| `EMAIL_PASSWORD` | `account`、`password` | `account` 为邮箱 |
| `EMAIL_CODE` | `email`、`code` | 使用用途为 `LOGIN` 的验证码 |
| `ROLE_SELECTION` | `loginTicket`、`loginRole` | 管理员第二阶段身份选择 |

请求字段：

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `loginType` | enum | 必填 |
| `account` | string/null | 用户名 3-80 位或合法邮箱 |
| `password` | string/null | 明文密码，8-128 位 |
| `email` | string/null | 合法邮箱，最多 120 位 |
| `code` | string/null | 6 位数字 |
| `loginTicket` | string/null | 管理员一次性身份选择票据 |
| `loginRole` | enum/null | `USER` 或 `ADMIN` |

### 用户名密码

```json
{
  "loginType": "USERNAME_PASSWORD",
  "account": "alice",
  "password": "correct-password"
}
```

### 邮箱密码

```json
{
  "loginType": "EMAIL_PASSWORD",
  "account": "alice@example.com",
  "password": "correct-password"
}
```

### 邮箱验证码

```json
{
  "loginType": "EMAIL_CODE",
  "email": "alice@example.com",
  "code": "123456"
}
```

### 管理员身份选择

```json
{
  "loginType": "ROLE_SELECTION",
  "loginTicket": "one-time-ticket",
  "loginRole": "ADMIN"
}
```

## 成功响应

普通用户或完成身份选择的管理员：

```json
{
  "status": "AUTHENTICATED",
  "authentication": {
    "accessToken": "jwt",
    "tokenType": "Bearer",
    "expiresAt": "2026-10-24T23:10:00Z",
    "user": {
      "id": 42,
      "username": "alice",
      "email": "alice@example.com",
      "role": "USER"
    }
  },
  "roleSelection": null
}
```

管理员第一阶段：

```json
{
  "status": "ROLE_SELECTION_REQUIRED",
  "authentication": null,
  "roleSelection": {
    "loginTicket": "one-time-ticket",
    "expiresAt": "2026-10-23T23:15:00Z"
  }
}
```

管理员选择 `USER` 时，JWT `role` 为 `USER`；选择 `ADMIN` 时，JWT `role` 为 `ADMIN`。普通用户只能得到 `USER`。

数据库 `users.user_role` 是最高权限角色，JWT `role` 是本次实际登录身份。登录签发通过单条查询读取用户角色和 `tokenVersion` 快照，角色变更与签发竞态产生的旧 Token 会在变更提交后失效。

## JWT

- 算法：HS256。
- Access Token 有效期：24 小时。
- 请求使用 `Authorization: Bearer <accessToken>`。
- Claims：`sub`、`role`、`loginType`、`tokenVersion`、`jti`、`iat`、`exp`。
- `role` 是本次实际登录身份，必须不超过数据库当前最高角色。
- `tokenVersion` 来自与角色同一次 MySQL 快照查询。
- 每个认证请求都会单条查询数据库最高角色和 `tokenVersion`；版本不匹配或登录角色超过最高角色时返回 `401 TOKEN_REVOKED`。
- `JwtAuthenticationConverterProvider` 不直接信任 `role` claim；只有数据库校验通过后才生成对应权限。
- `loginType` 为原始登录方式，不使用 `ROLE_SELECTION`。
- 不返回密码摘要、验证码或登录票据摘要。

## 验证码登录

邮箱验证码登录使用：

```text
music:verification:code:LOGIN:<normalized-email>
```

验证码验证成功后立即消费。登录失败、过期、已使用或不匹配统一返回 `401 INVALID_CREDENTIALS`。

发送登录验证码：

```bash
curl -X POST "http://localhost:8080/api/v1/email/send-code" \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","purpose":"LOGIN"}'
```

## 错误格式

错误使用 RFC 9457 `ProblemDetail`，包含稳定字段 `code`。

| HTTP | `code` | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段组合或格式错误 |
| 401 | `INVALID_CREDENTIALS` | 用户名、邮箱、密码或验证码认证失败 |
| 401 | `LOGIN_TICKET_INVALID` | Ticket 无效、过期或重放 |
| 403 | `ROLE_SELECTION_NOT_ALLOWED` | 普通用户选择 `ADMIN` |
| 429 | `LOGIN_RATE_LIMITED` | 登录请求或失败次数超限 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |
| 503 | `AUTH_STATE_STORE_UNAVAILABLE` | 登录状态、Ticket、限流或认证状态存储不可用 |
| 503 | `VERIFICATION_CODE_STORE_UNAVAILABLE` | 验证码 Redis 不可用 |

限流响应包含 `Retry-After`：

- 同一账号/IP：15 分钟内最多 5 次失败，成功后重置。
- 同一 IP：每分钟最多 20 次登录请求。

## JWT 配置

| 变量 | 说明 |
| --- | --- |
| `JWT_SECRET` | HS256 签名密钥，至少 32 字节 |
| `JWT_ISSUER` | Token issuer，默认契约为 `music-api` |
| `JWT_AUDIENCE` | Token audience，默认契约为 `music-client` |
| `JWT_TTL_SECONDS` | Access Token 秒数，默认契约为 `86400` |

## curl 示例

```bash
curl -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"loginType":"USERNAME_PASSWORD","account":"alice","password":"correct-password"}'
```

管理员第二阶段：

```bash
curl -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"loginType":"ROLE_SELECTION","loginTicket":"one-time-ticket","loginRole":"ADMIN"}'
```

查看 OpenAPI：

- JSON：`GET /v3/api-docs`
- UI：`GET /swagger-ui.html`
