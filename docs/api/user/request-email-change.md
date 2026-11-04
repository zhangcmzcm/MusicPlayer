# 向新邮箱发送变更验证码

`POST /api/v1/users/me/email-change-requests`

当前登录用户向目标新邮箱申请专用验证码。适用于更换邮箱和未绑定用户首次绑定；申请及发码不更新用户邮箱、不预占目标、不签发Token、不撤销登录。最终确认接口另行实现。

## 认证与权限

- Bearer JWT，USER/ADMIN均只操作本人，身份及版本来自服务端认证上下文。
- 真实auth_time距业务准入时刻不超过300秒，包含恰好300秒；不使用iat。普通认证先执行，再检查近期资格，然后解析请求。
- 有效登录缺auth_time或超窗：401 REAUTHENTICATION_REQUIRED。前端保留输入，弹窗通过密码或当前邮箱专用码再次验证，获得新Token后重试。过期或撤销的Token需恢复有效登录。
- 再次验证保留旧申请的原到期时间；本接口不影响其他既有接口的准入。

## 请求

Content-Type为application/json，可显式指定charset=UTF-8。缺失、非法或其他媒体类型、非UTF-8字符集返回415；正文需为合法UTF-8，含尾随空白总计最多16384字节，超出返回400。不依赖Content-Length限制，未知长度正文同样检查。

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| newEmail | string，必填 | 去除首尾空白，Locale.ROOT小写后合法邮箱，最多120字符；原输入含CR/LF拒绝；只接受单地址，不接受显示名称或地址列表 |

仅允许该字段，重复键、未知字段、null/数字/数组/嵌套、尾随JSON、空正文均400。JSON转义产生的孤立UTF-16代理字符同样拒绝，避免编码替换后向另一地址发送。首尾普通空格允许，不删除内部空格；不合并点号、+tag等服务商别名。不能用参数或请求头指定本人ID、原邮箱、用途、版本或认证时间。

```json
{"newEmail":" New@Example.com "}
```

规范化为new@example.com。与当前邮箱相同返回409 EMAIL_UNCHANGED；命中其他账户返回409 EMAIL_ALREADY_EXISTS，比较遵守数据库既有唯一约束及排序规则。上述拒绝不发信、不扣额度。

## 成功

201 Created，Cache-Control: no-store，无Location或新增查询接口：

```json
{
  "challengeId":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "expiresAt":"2026-11-04T08:05:00Z"
}
```

challengeId为32随机字节Base64URL无填充编码；expiresAt为UTC Instant，从Redis获准发送起算300秒，发送耗时计入期限，到期时刻起无效。客户端不接收验证码，用户从新邮箱收取6位数字码；前导零需保留。

申请绑定本人、原邮箱或未绑定状态、新邮箱和认证版本，与注册、登录、再次验证用途隔离。每人最多一个有效申请和一个发送中申请；同邮箱重发或改目标均在新申请成功激活时替换旧申请。最终确认仍须检查绑定和邮箱可用性，发码成功不预占目标邮箱。

## 限流和重试

| 维度 | 限制 |
| --- | --- |
| 本人 | 每60秒一次，每UTC自然日10次，跨设备和Token共享 |
| 规范化目标邮箱 | 每60秒一次，每UTC自然日10次，跨申请账户共享 |
| 连接IP | 首次获准尝试起固定60秒窗口5次 |

本用途与其他验证码额度隔离，不代表全项目邮件总限额。IP仅使用连接remoteAddr，不信任客户端转发头。日界只重置日计数，不清除冷却。429携带Retry-After，为所有阻塞额度中的最长等待时间，向上取整秒且至少1。

限流或pending冲突拒绝不扣额度、不延长窗口；准入后SMTP失败、超时、发送器满载、复核失败或存储故障均不退款、不自动重发。

SMTP接受不保证收件箱送达。明确激活前失败保留旧有效申请及原期限；激活结果未知返回503，可能已替换旧申请，不承诺旧申请仍有效。超时后仍可能收到未激活的无效邮件。响应丢失没有幂等或查询恢复能力，等待冷却后重新申请。

## 错误

错误使用application/problem+json，包含稳定code，type按code小写保留下划线，例如urn:problem:music:unsupported_media_type。普通JWT过期/签名错误沿用资源服务器现状，可能无ProblemDetail。

| 状态 | code | 含义 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 请求或邮箱不合法，含16 KiB上限、非法UTF-8 |
| 401 | REAUTHENTICATION_REQUIRED | 有效Token缺auth_time或超窗 |
| 401 | UNAUTHORIZED / INVALID_TOKEN / TOKEN_REVOKED | 登录、可信上下文或认证版本失效 |
| 403 | FORBIDDEN | 现有安全链拒绝 |
| 409 | EMAIL_UNCHANGED | 目标等同当前邮箱，含数据库唯一规则等同本人 |
| 409 | EMAIL_ALREADY_EXISTS | 目标已被其他账户占用，含发送后复核发现 |
| 409 | EMAIL_CHANGE_REQUEST_CONFLICT | 有发送中申请、绑定变化、租约失效或确定激活冲突 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | EMAIL_CHANGE_RATE_LIMITED | 发送额度超限，附Retry-After |
| 502 | EMAIL_SEND_FAILED | 邮件失败或20秒等待超时 |
| 503 | EMAIL_CHANGE_REQUEST_UNAVAILABLE | 本接口DB/Redis故障、坏状态、发送满载或激活结果未知 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 前置认证状态服务故障 |
| 500 | INTERNAL_ERROR | 意外内部错误 |

```json
{
  "type":"urn:problem:music:reauthentication_required",
  "title":"邮箱变更请求未完成",
  "status":401,
  "detail":"请再次验证本人身份后重试",
  "instance":"/api/v1/users/me/email-change-requests",
  "code":"REAUTHENTICATION_REQUIRED"
}
```

## curl

将ACCESS_TOKEN设置为本人有效且满足近期资格的Token，示例不包含真实凭据：

```bash
curl -i -X POST 'http://localhost:8080/api/v1/users/me/email-change-requests' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"newEmail":"new@example.com"}'
```

本接口成功不修改账户；未来最终确认成功将递增认证版本并撤销全部旧登录，包括当前操作，届时需重新登录。该确认接口目前未实现。
