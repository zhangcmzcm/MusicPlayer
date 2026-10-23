# 邮箱验证码发送接口设计

日期：2026-10-22

## 1. 目标与范围

本次只实现一个 HTTP 接口：

- `POST /api/v1/email/send-code`
- 用途：生成并发送邮箱验证码。
- 权限：公开接口，无需认证。
- 当前用途：仅支持 `REGISTER`。

该接口属于通用 Email 模块。发送工具放在 `common.util`，注册、修改邮箱、找回密码等业务通过 `purpose` 复用验证码能力。

非目标：

- 修改注册接口。
- 实现登录、修改邮箱或找回密码接口。
- 实现邮件模板管理后台。
- 引入消息队列或异步任务平台。

## 2. 接口契约

### 2.1 请求

Content-Type：`application/json`

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `email` | string | 是 | 合法邮箱；最多 120 个字符；规范化为小写 |
| `purpose` | enum | 是 | 当前仅允许 `REGISTER` |

```json
{
  "email": "alice@example.com",
  "purpose": "REGISTER"
}
```

`purpose` 是服务端白名单，不允许客户端传入任意字符串。后续增加用途时只扩展服务端枚举和对应权限策略，不在客户端暴露自由文本。

### 2.2 成功响应

同步邮件发送成功后返回 `200 OK`：

```json
{
  "purpose": "REGISTER",
  "expiresAt": "2026-10-22T12:40:00Z"
}
```

- 不返回验证码。
- 不返回邮件内容、SMTP 消息 ID 或密码摘要。
- `expiresAt` 使用 UTC、ISO-8601。
- 响应不返回规范化后的邮箱，避免无意泄露处理结果。

### 2.3 RFC 9457 错误

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 邮箱格式、长度、用途缺失或 JSON 不合法 |
| 429 | `SEND_CODE_TOO_FREQUENTLY` | 邮箱冷却期、每日上限或 IP 分钟上限触发 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |
| 502 | `EMAIL_SEND_FAILED` | SMTP 服务拒绝或发送异常 |
| 503 | `VERIFICATION_CODE_STORE_UNAVAILABLE` | Redis 不可用或超时 |

限流错误包含 `Retry-After` 响应头，单位秒：

```json
{
  "type": "urn:problem:music:send_code_too_frequently",
  "title": "Verification code rate limited",
  "status": 429,
  "detail": "Email verification code requests are temporarily rate limited",
  "instance": "/api/v1/email/send-code",
  "code": "SEND_CODE_TOO_FREQUENTLY",
  "scope": "EMAIL_COOLDOWN"
}
```

`scope` 可能为 `EMAIL_COOLDOWN`、`EMAIL_DAILY_LIMIT` 或 `IP_RATE_LIMIT`。不返回具体邮箱是否存在，因为注册验证码无需账户已存在。

## 3. 验证码生成与 Redis

### 3.1 生成

- 使用 `SecureRandom` 生成 6 位数字验证码。
- 不使用普通 `Random`。
- 明文验证码只存在于当前请求的内存中，并交给邮件发送器。
- Redis 仅保存 BCrypt 摘要。
- 日志不得输出验证码、摘要、邮件正文或 SMTP 密码。

### 3.2 Redis Hash

复用注册接口定义的 key：

```text
music:verification:code:REGISTER:<normalized-email>
```

字段：

| 字段 | 值 |
| --- | --- |
| `codeHash` | BCrypt 摘要 |
| `version` | 新生成 UUID |
| `attempts` | `0` |
| `usedAt` | 空字符串 |

写入必须通过 Lua 或等效原子操作完成：

1. 覆盖旧验证码。
2. 重置错误次数和消费状态。
3. 设置新的 `version`。
4. 设置 TTL 300 秒。

新验证码覆盖旧验证码后，旧验证码立即失效。注册接口的 `version` 校验继续保证不会误消费新验证码。

### 3.3 发送失败回滚

流程如下：

1. 生成验证码、摘要和 `version`。
2. 原子写入 Redis。
3. 同步调用 SMTP。
4. SMTP 成功后返回 200。
5. SMTP 失败时调用 `removeIfVersion(email, purpose, version)`。
6. 只删除相同 `version`，不能删除并发请求刚写入的新验证码。
7. 删除成功后返回 `502 EMAIL_SEND_FAILED`。
8. 删除失败时记录 ERROR 和 `traceId`，仍然返回 `502`；验证码将在 300 秒后自动过期。

限流额度在请求进入发送流程时消耗。SMTP 失败不会自动返还额度，避免攻击者反复触发外部邮件服务。

## 4. 限流设计

所有计数器存放在 Redis，使用 UTC 时间窗口。

### 4.1 规则

| 范围 | 限制 | TTL |
| --- | --- | --- |
| 同一邮箱 + `REGISTER` | 60 秒 1 次 | 60 秒 |
| 同一邮箱 + `REGISTER` | 每 UTC 日 10 次 | 到当日 UTC 日结束 |
| 同一客户端 IP | 每分钟 5 次 | 60 秒 |

### 4.2 Key

```text
music:email:send-code:cooldown:REGISTER:<email>
music:email:send-code:daily:REGISTER:<email>:<yyyy-MM-dd>
music:email:send-code:ip:<client-ip>:<yyyy-MM-dd'T'HH:mm>
```

写入验证码前，由 Lua 脚本原子执行冷却期、每日计数和 IP 计数检查。任一限制触发即返回 429，并给出对应 `scope` 和剩余秒数。

前一次请求触发限流时，已经设置的计数器不回滚。这样可以限制持续重试。

### 4.3 客户端 IP

新增 `common.web.ClientIpResolver`：

- 当前直接暴露服务时使用 `HttpServletRequest.getRemoteAddr()`。
- 不信任未经配置的 `X-Forwarded-For`，避免攻击者伪造 IP。
- 后续接入 Nginx、网关或负载均衡器时，在该组件中增加可信代理地址配置，再解析 `X-Forwarded-For`。
- `EmailSendCodeService` 只接收解析后的 `clientIp`，不依赖 Servlet API。

## 5. SMTP 发送

### 5.1 组件边界

| 组件 | 职责 |
| --- | --- |
| `EmailController` | HTTP、Bean Validation、客户端 IP 提取、OpenAPI |
| `EmailSendCodeService` | 用途校验、限流、生成验证码、写 Redis、调用发送器、失败回滚 |
| `EmailCodeSendRateLimiter` | Redis 原子限流 |
| `EmailVerificationCodeStore` | 验证码原子写入、读取、失败计数、消费、按版本删除 |
| `EmailVerificationCodeSender` | 邮件发送端口，位于 `common.util` |
| `SmtpEmailVerificationCodeSender` | 使用 Spring Mail 的 SMTP 实现 |
| `SecureVerificationCodeGenerator` | 使用 SecureRandom 生成验证码 |
| `ClientIpResolver` | 解析可信客户端 IP |
| `GlobalExceptionHandler` | RFC 9457 错误映射 |

`EmailVerificationCodeSender` 定义为接口，便于 SMTP 实现、单元测试和未来邮件服务商实现替换。SMTP 实现也放在 `common.util`，满足当前模块约定。

### 5.2 邮件内容

默认模板：

- 主题：`音乐播放器邮箱验证码`
- 正文包含 6 位验证码和 5 分钟有效提示。
- 邮件正文不包含密码、Token 或其他隐私数据。
- 发件人来自配置，不硬编码。

### 5.3 SMTP 配置

通过环境变量配置：

| 变量 | 用途 |
| --- | --- |
| `MAIL_HOST` | SMTP 主机 |
| `MAIL_PORT` | SMTP 端口 |
| `MAIL_USERNAME` | SMTP 用户名 |
| `MAIL_PASSWORD` | SMTP 密码 |
| `MAIL_FROM` | 发件人地址 |
| `MAIL_SMTP_AUTH` | 是否启用认证 |
| `MAIL_SMTP_STARTTLS` | 是否启用 STARTTLS |

`.env.example` 增加这些变量，真实 `.env` 继续被 `.gitignore` 排除。

## 6. 安全

- 验证码使用 SecureRandom 生成。
- Redis 仅保存 BCrypt 摘要。
- 日志不输出验证码、邮件正文、摘要、SMTP 密码或完整请求体。
- 不返回验证码或邮件服务响应内容。
- `purpose` 使用枚举白名单。
- IP 限流默认不信任代理头。
- 发送接口与注册接口都显式配置为公开端点。
- 请求字段有严格 Bean Validation。
- 邮件发送失败不吞异常，转换为稳定错误码并记录不含敏感信息的错误上下文。

## 7. 依赖

新增：

- `org.springframework.boot:spring-boot-starter-mail`

版本由 Spring Boot BOM 管理。用途是提供 `JavaMailSender` 和 SMTP 配置。

不引入邮件服务商 SDK、队列系统或新的 Web 框架。

## 8. 测试计划

Web 测试：

- 无 Authorization 请求成功。
- 合法邮箱和 `REGISTER` 返回 200。
- 邮箱缺失、格式错误、过长返回 400。
- `purpose` 缺失或不支持返回 400。
- 响应不包含验证码、摘要或邮件内容。
- 限流响应包含 429、`Retry-After` 和 `scope`。

服务单元测试：

- 只允许当前支持的 `purpose`。
- SecureRandom 生成恰好 6 位数字。
- 写入 Redis 的验证码使用 BCrypt 摘要。
- 成功发送时设置 300 秒 TTL。
- SMTP 失败时只删除相同 `version`。
- SMTP 失败返回 `EMAIL_SEND_FAILED`。
- Redis 写入失败返回 503，不调用 SMTP。
- 邮箱冷却、每日上限、IP 上限分别返回 429。
- 重发覆盖旧验证码并重置尝试次数。
- 不输出验证码、摘要和邮件密码。

Redis 集成测试：

- Hash 字段、版本和 TTL 正确。
- 60 秒冷却限制。
- UTC 每日 10 次限制。
- IP 每分钟 5 次限制。
- 并发发送只有一个成功。
- `removeIfVersion` 不删除新版本。
- 重发后旧版本不能消费。

SMTP 组件测试：

- 邮件主题、收件人和正文正确。
- 正文包含验证码和有效期。
- SMTP 异常转换为 `EMAIL_SEND_FAILED`。

测试命名使用 `should_<result>_when_<condition>`。资源不存在和权限不足在公开接口中不适用；重复请求通过限流和覆盖语义覆盖。

## 9. API 文档

新增：

- `docs/api/email/send-code.md`

文档包含方法、路径、权限、请求字段、成功和 RFC 9457 错误示例、限流规则、Redis TTL、curl 示例和 SMTP 配置说明。

Controller 使用完整 OpenAPI 注解，包括 `@Operation`、`@ApiResponse`、请求和响应 `@Schema`。

## 10. 已确认决策

- 接口路径为 `POST /api/v1/email/send-code`。
- 请求包含 `email` 和 `purpose`。
- 当前仅支持 `REGISTER`。
- 成功返回 200 和 `{purpose, expiresAt}`。
- 邮箱 60 秒 1 次、每日 10 次。
- 客户端 IP 每分钟 5 次。
- 当前使用 `remoteAddr`，未来通过 `ClientIpResolver` 支持可信反向代理。
- 邮件同步发送，SMTP 失败返回 502。
- 验证码 BCrypt 存储到 Redis，TTL 300 秒。
- 发送工具接口位于 `common.util.EmailVerificationCodeSender`。
- 使用 Spring Mail SMTP。
- 不返回或记录验证码。