# 用户登录接口设计

日期：2026-10-23

## 1. 目标与范围

本次只实现一个 HTTP 接口：

- `POST /api/v1/auth/login`
- 权限：公开接口，无需已有认证凭据。
- 支持用户名密码登录、邮箱密码登录和邮箱验证码登录。
- 管理员在认证成功后通过同一接口完成身份选择。

本接口同时扩展现有 `POST /api/v1/email/send-code` 的 `purpose` 白名单，新增 `LOGIN`。不新增其他 HTTP 接口。

非目标：

- 注销、刷新 Token 或修改密码。
- 忘记密码、修改邮箱。
- OAuth2 社交登录。
- 登录设备管理。

## 2. 请求契约

### 2.1 登录模式

`loginType` 取值：

| 值 | 用途 | 必填字段 |
| --- | --- | --- |
| `USERNAME_PASSWORD` | 用户名密码登录 | `account`、`password` |
| `EMAIL_PASSWORD` | 邮箱密码登录 | `account`、`password` |
| `EMAIL_CODE` | 邮箱验证码登录 | `email`、`code` |
| `ROLE_SELECTION` | 管理员选择登录身份 | `loginTicket`、`loginRole` |

请求 DTO 使用统一容器和条件校验：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `loginType` | enum | 必填 |
| `account` | string/null | 用户名或邮箱 |
| `password` | string/null | 明文密码，仅通过 HTTPS 传输 |
| `email` | string/null | 验证码登录邮箱 |
| `code` | string/null | 6 位邮箱验证码 |
| `loginTicket` | string/null | 管理员身份选择票据 |
| `loginRole` | enum/null | `USER` 或 `ADMIN` |

`USERNAME_PASSWORD` 和 `EMAIL_PASSWORD` 使用 `account`：

- `account` 不包含 `@` 时按用户名匹配。
- `account` 包含 `@` 时按邮箱匹配。
- 用户名注册规则不允许 `@`，因此匹配规则唯一。

### 2.2 示例

用户名密码：

```json
{
  "loginType": "USERNAME_PASSWORD",
  "account": "alice",
  "password": "correct-password"
}
```

邮箱密码：

```json
{
  "loginType": "EMAIL_PASSWORD",
  "account": "alice@example.com",
  "password": "correct-password"
}
```

邮箱验证码：

```json
{
  "loginType": "EMAIL_CODE",
  "email": "alice@example.com",
  "code": "123456"
}
```

管理员选择身份：

```json
{
  "loginType": "ROLE_SELECTION",
  "loginTicket": "one-time-ticket",
  "loginRole": "ADMIN"
}
```

### 2.3 成功响应

普通用户或已完成身份选择的管理员登录成功：

```json
{
  "status": "AUTHENTICATED",
  "authentication": {
    "accessToken": "jwt",
    "tokenType": "Bearer",
    "expiresAt": "2026-10-24T13:00:00Z",
    "user": {
      "id": 42,
      "username": "admin_user",
      "email": "admin@example.com",
      "role": "ADMIN"
    }
  },
  "roleSelection": null
}
```

管理员认证成功但尚未选择身份：

```json
{
  "status": "ROLE_SELECTION_REQUIRED",
  "authentication": null,
  "roleSelection": {
    "loginTicket": "one-time-ticket",
    "expiresAt": "2026-10-23T13:05:00Z"
  }
}
```

`role` 表示本次实际登录身份，不一定是数据库中的最高权限角色。

## 3. 登录流程

### 3.1 密码登录

1. 校验 `loginType`、字段组合和格式。
2. 规范化 `account`：去除首尾空白；邮箱转为小写。
3. 根据是否包含 `@` 查询用户名或邮箱。
4. 使用 BCrypt 比较请求 `password` 和数据库 `password_token`。
5. 用户不存在或密码不匹配时统一返回 `401 INVALID_CREDENTIALS`。
6. 密码验证成功后进入身份选择或 Token 签发阶段。

Java 代码使用 `passwordHash` 表示 BCrypt 存储摘要；数据库物理列继续为 `password_token`，本次不迁移表结构。

### 3.2 邮箱验证码登录

1. 邮箱规范化为小写。
2. 校验 Redis 中用途为 `LOGIN` 的验证码。
3. 使用 BCrypt 比较验证码摘要。
4. 验证码不存在、不匹配、过期或已使用时统一返回 `401 INVALID_CREDENTIALS`。
5. 验证成功后立即按 `version` 原子消费验证码，防止重复登录。
6. 查询具有该邮箱的用户；用户不存在时返回同一个 `401`，不泄露账号状态。

邮箱验证码登录需要发送用途为 `LOGIN` 的验证码：

```text
music:verification:code:LOGIN:<normalized-email>
```

`POST /api/v1/email/send-code` 将接受 `REGISTER` 和 `LOGIN` 两种 `purpose`。

### 3.3 管理员身份选择

数据库 `user_role` 是用户的最高权限角色：

- `USER` 用户只能登录为 `USER`。
- `ADMIN` 用户可以选择 `USER` 或 `ADMIN`。
- 第一阶段请求不接受 `loginRole`，避免客户端在密码验证前声明权限。

当密码或验证码验证成功：

1. 如果用户最高角色是 `USER`，直接签发 `role=USER` 的 JWT。
2. 如果用户最高角色是 `ADMIN` 且未完成身份选择，生成一次性 Ticket。
3. Ticket TTL 为 300 秒，绑定用户 ID、最高角色和登录方式。
4. 前端弹窗后调用相同登录接口，提交 `ROLE_SELECTION`。
5. 后端校验 Ticket 未过期、未使用且属于有效用户。
6. `loginRole` 为 `ADMIN` 时，数据库最高角色必须是 `ADMIN`。
7. Ticket 消费后更新 `last_login_at` 并签发包含实际登录身份的 JWT。
8. Ticket 不存在、过期或已使用时统一返回 `401 LOGIN_TICKET_INVALID`。

Ticket 不保存在客户端可修改的声明中。Redis 只保存 Ticket 的 SHA-256 摘要，明文 Ticket 只返回给当前客户端。

## 4. JWT

### 4.1 算法与配置

- 算法：HS256。
- Access Token TTL：24 小时。
- 不提供 Refresh Token。
- 签名密钥通过 `JWT_SECRET` 提供，最少 32 字节。
- Issuer：`music-api`。
- Audience：`music-client`。
- 使用 Spring Security OAuth2 Resource Server / Nimbus 生成和校验 Token。

配置变量：

| 变量 | 用途 |
| --- | --- |
| `JWT_SECRET` | HMAC 签名密钥 |
| `JWT_ISSUER` | Token issuer |
| `JWT_AUDIENCE` | Token audience |
| `JWT_TTL_SECONDS` | Token 有效秒数，默认契约为 86400 |

### 4.2 Claims

| Claim | 说明 |
| --- | --- |
| `sub` | 用户 ID |
| `role` | 本次实际登录角色 |
| `loginType` | 密码、邮箱验证码或管理员选择 |
| `jti` | Token ID |
| `iat` | 签发时间 |
| `exp` | 过期时间 |

Ticket 保存原始登录方式，`ROLE_SELECTION` 完成后 JWT 的 `loginType` 使用原始登录方式，而不是 `ROLE_SELECTION`。

不写入密码摘要、验证码、邮箱或 Ticket。后续受保护接口使用 `Authorization: Bearer <token>`，移除 HTTP Basic。

## 5. 登录限流

所有计数使用 Redis 和 UTC 时间。

### 5.1 规则

| 范围 | 限制 | 重置 |
| --- | --- | --- |
| 同一账号/IP | 15 分钟内最多 5 次失败 | 登录成功后重置 |
| 同一 IP | 每分钟最多 20 次请求 | 60 秒后自动过期 |

计数对象使用 SHA-256 摘要，避免 Redis key 直接保存邮箱或用户名。

### 5.2 行为

- 限流在读取用户密码摘要或验证码之前执行。
- 失败登录累计账号/IP 计数。
- 成功登录只重置账号/IP 计数，不重置 IP 每分钟窗口。
- 超过限制返回 `429 LOGIN_RATE_LIMITED` 和 `Retry-After`。
- IP 解析继续使用 `ClientIpResolver`；当前直接暴露服务时使用 `remoteAddr`。

## 6. 错误协议

所有错误使用 RFC 9457 `ProblemDetail`。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段格式或组合不合法 |
| 401 | `INVALID_CREDENTIALS` | 用户名、邮箱、密码或验证码不正确 |
| 401 | `LOGIN_TICKET_INVALID` | Ticket 不存在、过期、重放或绑定失效 |
| 403 | `ROLE_SELECTION_NOT_ALLOWED` | 普通用户尝试选择 `ADMIN` |
| 429 | `LOGIN_RATE_LIMITED` | 登录频率或失败次数超限 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |
| 503 | `AUTH_STATE_STORE_UNAVAILABLE` | Redis Ticket/限流存储不可用 |
| 503 | `VERIFICATION_CODE_STORE_UNAVAILABLE` | 验证码 Redis 不可用 |

账号不存在、密码错误和验证码错误不返回不同错误码，避免账号枚举。

## 7. 数据更新

- 密码登录成功后，在最终 Token 签发事务中更新 `users.last_login_at`。
- 普通用户在第一阶段更新。
- 管理员在 `ROLE_SELECTION` 完成后更新。
- 邮箱验证码在身份验证成功后立即消费；如果管理员放弃身份选择，需要重新获取登录验证码。
- 不修改 `username`、`email`、`password_token` 或 `user_role`。

## 8. 组件边界

| 组件 | 职责 |
| --- | --- |
| `AuthController` | 登录 HTTP、条件校验、OpenAPI |
| `UserLoginService` | 登录模式编排、管理员选择流程、返回状态 |
| `PasswordLoginService` | 用户名/邮箱密码认证 |
| `EmailCodeLoginService` | `LOGIN` 验证码认证和消费 |
| `LoginTicketService` | 生成、校验和一次性消费 Redis Ticket |
| `LoginRateLimiter` | 账号/IP 和 IP 限流 |
| `JwtTokenService` | JWT 签发、claims 和过期时间 |
| `UserRepository` | 按用户名、邮箱、ID 查询 |
| `GlobalExceptionHandler` | RFC 9457 错误映射 |
| `SecurityConfig` | 公开登录端点、Bearer JWT 保护其他端点 |

Controller 不写认证业务逻辑；Service 不返回 Entity；Repository 不写权限判断。

## 9. 依赖

新增：

- `org.springframework.boot:spring-boot-starter-oauth2-resource-server`

版本由 Spring Boot BOM 管理。用途是提供 Spring Security OAuth2 JOSE、Nimbus JWT 编解码和 Bearer Token 过滤链。

不引入独立 JWT SDK。Redis 继续通过现有 `spring-boot-starter-data-redis` 使用。

## 10. 测试计划

Web 测试：

- 无 Authorization 可以调用登录接口。
- 三种登录模式的合法请求。
- 每种模式的字段组合、长度和格式边界。
- 普通用户登录返回 JWT。
- 管理员第一阶段返回 Ticket，不返回 JWT。
- 管理员第二阶段返回选定角色的 JWT。
- 响应不包含密码、密码摘要、验证码或 Ticket 摘要。

服务测试：

- 用户名和邮箱分别匹配。
- BCrypt 密码正确和错误。
- 不存在账号统一返回 `INVALID_CREDENTIALS`。
- `LOGIN` 验证码正确、错误、过期、已使用。
- 验证码登录成功后只消费一次。
- 普通用户不能选择 `ADMIN`。
- 管理员可以登录为 `USER` 或 `ADMIN`。
- Ticket 只能使用一次、不能跨用户、不能跨登录方式。
- JWT claims、角色和过期时间正确。
- `last_login_at` 仅在最终登录成功时更新。
- 账号/IP 5 次失败后锁定。
- IP 每分钟 20 次限制。
- 成功登录重置账号失败计数。

Redis + MySQL Testcontainers 测试：

- Ticket TTL、原子消费和重放保护。
- 登录验证码 `LOGIN` 与 `REGISTER` 隔离。
- JWT 使用真实密钥生成并校验。
- 并发身份选择只成功一次。
- 唯一用户查询和 `last_login_at` 更新使用真实 MySQL。

测试命名使用 `should_<result>_when_<condition>`。

## 11. API 文档

新增：

- `docs/api/auth/login.md`

文档包含四种 `loginType`、两种成功响应、JWT、管理员 Ticket、错误码、限流和 curl 示例。

## 12. 已确认决策

- 单接口 `POST /api/v1/auth/login`。
- 登录支持用户名密码、邮箱密码、邮箱验证码。
- 请求使用 `loginType`，不依赖字段探测。
- 邮箱验证码用途新增 `LOGIN`。
- JWT Access Token 有效期 24 小时，无 Refresh Token。
- 管理员采用同一接口的两阶段身份选择。
- Ticket TTL 300 秒、一次性、绑定用户和登录方式。
- 管理员可选择 `USER` 或 `ADMIN`，普通用户只能登录为 `USER`。
- 登录失败使用统一 `INVALID_CREDENTIALS`。
- 同一账号/IP 15 分钟 5 次失败，IP 每分钟 20 次请求。
- 数据库 `password_token` 不改名；Java 语义使用 `passwordHash`。
- 后续受保护接口使用 Bearer JWT。