# 申请本人再次验证身份邮件

## 接口与权限

`POST /api/v1/users/me/reauthentication-email-requests`

需要有效 Bearer 访问 Token，USER、ADMIN 均只可操作本人。主体 ID、登录角色和认证版本来自服务端认证结果，收件人取数据库中的本人当前绑定邮箱。查询参数、自定义用户/邮箱/角色头部不能覆盖身份或收件人。

本接口是修改邮箱等敏感操作前的身份验证发送步骤，不向新邮箱发信，不修改任何账户字段，不刷新 `auth_time`、不递增 `token_version`、不签发 Token、不撤销其他登录。普通有效登录即可调用，不要求最近300秒验证；缺少 `auth_time` 的旧 Token 也可调用，其他认证校验不放宽。

后续统一再次验证接口将支持密码或本请求验证码两种方式；该兑换接口及修改邮箱接口尚未在本任务实现，收到邮件不等于已经完成再次验证。

## 请求

必须为零字节正文，无请求字段，无 Content-Type 要求。空格、换行、`{}`、`null`、数组或任何其他非空正文均返回400，即使未提供 Content-Length 或采用分块传输。无需发送空 JSON。

```http
POST /api/v1/users/me/reauthentication-email-requests HTTP/1.1
Authorization: Bearer ${ACCESS_TOKEN}
Content-Length: 0
```

服务端对数据库邮箱执行 trim 和 Locale.ROOT 小写规范化；未绑定邮箱返回409，异常存储地址返回503，不回显地址。不能借本接口指定任意邮箱、用户、版本或验证码用途。

## 成功响应

`201 Created`，`Content-Type: application/json`，`Cache-Control: no-store`；没有 Location 或额外读取接口。

```json
{
  "challengeId": "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq",
  "expiresAt": "2026-11-03T04:05:06.123Z"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| challengeId | string，43字符 | 32安全随机字节的Base64URL无填充标识，客户端临时保存用于后续验证，不应记录到日志 |
| expiresAt | UTC ISO-8601 Instant | 本次Redis预留/准入时间后300秒的绝对到期时间，哈希、邮件等待和激活不延长有效期 |

响应严格只有上述两个字段，不返回邮箱、验证码、摘要、内部版本或认证时间。只有SMTP接受发送、账户绑定复核通过并确认原子激活后才返回201。SMTP接受不代表邮件最终到达收件箱。

## 重发、额度与并发

- 每个用户每60秒最多准入一次，每UTC自然日最多10次；同一用户的多个Token共享额度。
- 每连接IP采用固定60秒窗口，最多5次；同一IP下不同用户共享此桶。当前只取服务端连接 `remoteAddr`，不信任任意 X-Forwarded-For。
- 三个桶同一Lua原子检查并准入，拒绝不增加计数或延长窗口。429的 Retry-After 取各阻塞桶最长等待秒数，向上取整且至少1。
- 准入之后的哈希、发送、复核、激活失败均不退额度，不自动重试。这是独立额度，与注册/登录验证码额度不合并。
- 每人最多一个ACTIVE及一个PENDING。新邮件发送中旧ACTIVE保持原有效期；只有新请求激活才原子替换。发送失败不会提前撤销旧请求。失败的本次PENDING按归属尽力清理，最长60秒租约，不因哈希/发送续租。

发送等待20秒，专用执行器4槽、零队列；满载503。超时请求不能激活，底层SMTP任务可能尚未结束并仍占用物理槽位；迟到邮件可能无效。过期租约或账户绑定变化返回409/401，不能用已发送邮件绕过再次核对。

## 错误

业务错误使用 `application/problem+json`，带稳定 `code`、`type`、`instance`，错误响应同样不缓存。JWT签名错误或到期沿用资源服务器现有401响应，可能没有ProblemDetail正文。

| 状态 | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 非空正文或正文读取失败 |
| 401 | UNAUTHORIZED | 未登录 |
| 401 | INVALID_TOKEN | 声明或可信认证上下文无效 |
| 401 | TOKEN_REVOKED | 账户不存在、版本失效或登录角色已不被允许，包括发送后复核竞态 |
| 409 | CURRENT_EMAIL_NOT_BOUND | 首次查询发现当前邮箱未绑定，尚未消费额度 |
| 409 | REAUTH_EMAIL_REQUEST_CONFLICT | 发送后邮箱变化/解绑，或预留过期、归属/阶段不匹配 |
| 429 | REAUTH_EMAIL_RATE_LIMITED | 三个桶任一阻塞，含 Retry-After |
| 502 | EMAIL_SEND_FAILED | 邮件发送失败、取消或结果等待超时 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 既有认证链读取账户状态失败 |
| 503 | REAUTH_EMAIL_REQUEST_UNAVAILABLE | 本请求DB/Redis故障、损坏状态、未知回包、坏存储邮箱或邮件执行器满载 |
| 500 | INTERNAL_ERROR | 未预期的内部故障 |

```json
{
  "type": "urn:problem:music:reauth_email_rate_limited",
  "title": "身份验证邮件请求过于频繁",
  "status": 429,
  "detail": "身份验证邮件请求过于频繁，请稍后重试",
  "instance": "/api/v1/users/me/reauthentication-email-requests",
  "code": "REAUTH_EMAIL_RATE_LIMITED"
}
```

Redis命令已执行但确认丢失时，503可能对应已消费额度、已发送邮件甚至已激活请求；不能承诺旧ACTIVE必然保留。MySQL复核与Redis激活也不是跨存储原子快照，未来兑换仍须复核用户、当前邮箱和版本。客户端不要自动无间隔重试，尊重 Retry-After；未收到成功响应时应在额度允许后重新申请。

## curl

POSIX（环境变量 ACCESS_TOKEN 为调用者访问Token）：

```bash
curl -i -X POST 'http://localhost:8080/api/v1/users/me/reauthentication-email-requests' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}"
```

PowerShell：

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/users/me/reauthentication-email-requests" -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

以上命令均无正文；示例标识和日期为虚构数据。实际邮件送达、代理IP桶验收见[部署说明](../../deployment/reauthentication-email-requests.md)。
