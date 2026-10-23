# 发送邮箱验证码

## 接口

- 方法：`POST`
- 路径：`/api/v1/email/send-code`
- 权限：公开，无需认证
- Content-Type：`application/json`
- 成功状态码：`200 OK`

同步生成并发送邮箱验证码。支持 `REGISTER` 和 `LOGIN` 用途。

## 请求字段

| 字段 | 类型 | 必填 | 规则 |
| --- | --- | --- | --- |
| `email` | string | 是 | 合法邮箱；最多 120 个字符；规范化为小写 |
| `purpose` | enum | 是 | 允许 `REGISTER`、`LOGIN` |

```json
{
  "email": "alice@example.com",
  "purpose": "REGISTER"
}
```

## 成功响应

```json
{
  "purpose": "REGISTER",
  "expiresAt": "2026-10-22T12:40:00Z"
}
```

- `expiresAt` 使用 UTC、ISO-8601。
- 不返回验证码、邮件内容、SMTP 消息 ID 或验证码摘要。

## 错误格式

所有错误使用 RFC 9457 `ProblemDetail`，并包含稳定扩展字段 `code`。

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

| HTTP | `code` | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 邮箱格式、长度、用途或 JSON 不合法 |
| 429 | `SEND_CODE_TOO_FREQUENTLY` | 发送频率超限 |
| 500 | `INTERNAL_ERROR` | 未预期的服务端错误 |
| 502 | `EMAIL_SEND_FAILED` | SMTP 拒绝或发送异常 |
| 503 | `VERIFICATION_CODE_STORE_UNAVAILABLE` | Redis 不可用或超时 |

限流响应包含 `Retry-After` 响应头，单位为秒。`scope` 可能为：

- `EMAIL_COOLDOWN`
- `EMAIL_DAILY_LIMIT`
- `IP_RATE_LIMIT`

## 限流规则

| 范围 | 限制 |
| --- | --- |
| 同一邮箱 + 当前用途 | 60 秒 1 次 |
| 同一邮箱 + 当前用途 | 每 UTC 日 10 次 |
| 同一客户端 IP | 每分钟 5 次 |

当前直接暴露服务时使用 TCP `remoteAddr` 作为客户端 IP。后续接入可信反向代理后，由 `ClientIpResolver` 解析代理转发头。

## 验证码语义

- 使用 `SecureRandom` 生成 6 位数字验证码。
- Redis 只保存 BCrypt 摘要。
- Redis key：`music:verification:code:<PURPOSE>:<normalized-email>`。
- TTL 为 300 秒。
- 发送成功后，注册接口可以在数据库提交成功后消费该验证码。
- 发送失败时按版本删除刚写入的验证码，并返回 502。
- 新验证码覆盖旧验证码并重置错误次数。

## SMTP 配置

通过环境变量配置：

| 变量 | 说明 |
| --- | --- |
| `MAIL_HOST` | SMTP 主机 |
| `MAIL_PORT` | SMTP 端口 |
| `MAIL_USERNAME` | SMTP 用户名 |
| `MAIL_PASSWORD` | SMTP 密码 |
| `MAIL_FROM` | 发件人地址 |
| `MAIL_SMTP_AUTH` | 是否启用认证 |
| `MAIL_SMTP_STARTTLS` | 是否启用 STARTTLS |

真实密钥放入本地 `.env` 或 IntelliJ Run Configuration；`.env` 不提交 Git。

## curl 示例

```bash
curl --request POST 'http://localhost:8080/api/v1/email/send-code' \
  --header 'Content-Type: application/json' \
  --data '{
    "email": "alice@example.com",
    "purpose": "REGISTER"
  }'
```

查看 OpenAPI：

- JSON：`GET /v3/api-docs`
- UI：`GET /swagger-ui.html`