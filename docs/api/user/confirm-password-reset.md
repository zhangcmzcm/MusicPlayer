# 使用重置凭据设置新密码

## 方法与路径

`POST /api/v1/users/password-resets`

公开接口。无需登录、旧密码或近期认证。USER、ADMIN同规则，账户从服务端有效重置票据绑定取得。主动附带其他账户有效Bearer不会改变重置目标。主动附带无效、过期、撤销Bearer仍受原认证链拒绝。

## 请求

媒体类型为`application/json`，charset省略或UTF-8。严格UTF-8 JSON最多16384字节，尾随空白计入预算。只接受下列两个字符串，各出现一次，顺序不限。缺失、重复（含转义同名）、未知字段、嵌套、多根、非字符串与无效编码均拒绝。

| 字段 | 规则 |
| --- | --- |
| resetTicket | 必填，43字符规范Base64URL无填充，解码32字节且重编码相同，原值保留；取自第二步验证响应 |
| newPassword | 必填，8至72位可打印ASCII（U+0020至U+007E），非全空格，保留首尾空格，不截断、不预哈希 |

```json
{
  "resetTicket": "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
  "newPassword": "new-example-password"
}
```

确认密码由前端本地校验，无需上传。旧challengeId/code/newPassword请求返回400 VALIDATION_FAILED。email、userId、role、oldPassword及confirmPassword均为未知字段。

## 三步调用与提交规则

1. POST /api/v1/users/password-reset-requests提交email，202响应提供challengeId及expiresAt，验证码通过邮件送达。
2. POST /api/v1/users/password-reset-verifications提交challengeId/code，200响应提供resetTicket及expiresAt。前端将票据暂存在内存。
3. 本接口提交resetTicket/newPassword，204完成改密，随后使用新密码登录。

格式通过后，先扣TCP客户端IP固定60秒10次额度，再完整读取票据及当前账户指针，按可信userId扣固定900秒10次额度。第三步使用music:auth:password-reset-completion:前缀，第二步保留music:auth:password-reset-confirmation:，两组窗口独立。前层已准入及后续失败均计数，当前拒绝层不增、不续窗，新票、重发、换设备或登录均保留原窗口。不信任X-Forwarded-For。

仅IP拒绝429及Retry-After，值为Redis原窗口剩余毫秒向上取整秒数且至少1。账户额度拒绝统一400 PASSWORD_RESET_VERIFICATION_FAILED，隐藏等待时间、剩余次数及拒绝层。缺失假票只扣IP，无账户键。有效票据及prepared绑定通过后才比较新旧密码。

票据沿用第二步schema 1及签发起300秒原期限，当前时刻等于expiresAt已过期。必须同时匹配摘要记录和该账户当前票据指针。每账户最新一张，新票成功签发替换旧票，单纯申请、排队、发送或激活邮箱验证码保留票据原期限。

短只读事务复核票据原始邮箱、角色及版本，读取当前密码摘要。事务外判断新旧密码等价，等价返回NEW_PASSWORD_SAME_AS_OLD，保留票据与原期限，当前额度已扣。新摘要计算异常或版本上限在消费前503。摘要准备完成后，以完整预期快照单Lua原子消费票据及匹配指针，明确成功后执行一次短MySQL条件更新，仅修改密码摘要、认证版本加1及UTC更新时间。

原始邮箱、角色、旧密码摘要、版本或账户存在性变化导致条件更新失败，统一400。使用原prepared执行一次CAS，条件冲突后结束处理。仅用户名并发变化可成功并保留新用户名。期限内消费成功，后续数据库提交可以跨expiresAt。

## 成功响应

```http
HTTP/1.1 204 No Content
Cache-Control: no-store
```

空正文，无Location、新Token或认证版本。目标账户全部旧登录在后续认证版本校验中失效，旧角色选择Ticket不可升级。前端清除旧登录，用新密码走原登录流程，管理员仍按原角色选择流程。不承诺取消已通过认证的在途请求。

## 错误响应

业务错误使用`application/problem+json`，type为`urn:problem:music:`加小写下划线code，均禁止缓存。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 字段、JSON、编码或字节预算不符合规则 |
| 400 | PASSWORD_RESET_VERIFICATION_FAILED | 票据缺失/过期/被替换/已消费、账户额度拒绝、绑定变化或条件更新0行 |
| 400 | NEW_PASSWORD_SAME_AS_OLD | 有效票据绑定后发现新旧密码等价，未消费 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | PASSWORD_RESET_CONFIRMATION_RATE_LIMITED | 仅IP额度拒绝，带Retry-After |
| 503 | PASSWORD_RESET_CONFIRMATION_UNAVAILABLE | Redis/DB/事务/摘要故障、损坏状态、认证版本上限或结果未知 |
| 500 | INTERNAL_ERROR | 未预期异常或不可能的更新行数 |
| 401 | UNAUTHORIZED / INVALID_TOKEN / TOKEN_REVOKED | 主动附带无效Bearer的原安全链错误；签名错误/过期可仅有WWW-Authenticate及空正文 |
| 403 | FORBIDDEN | 原安全链拒绝，无新增角色限制 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 主动附带Token时认证状态查询失败 |

票据绑定的数据库账户已不存在，按绑定变化返回400。首次按摘要查询没有票据记录为普通失效400，此时无法定位未知账户指针；现存票据缺指针、字段损坏、归属或TTL矛盾为503。已有快照的消费阶段能定位已知双键半状态，按损坏503处理。正常已消费或自然到期的双键消失为400，保持现场且不扫描。

统一验证失败示例，traceId及instance由请求确定，无账户或等待扩展字段。

```json
{
  "type": "urn:problem:music:password_reset_verification_failed",
  "title": "密码重置未完成",
  "status": 400,
  "detail": "验证码或找回申请无效，请检查或重新申请",
  "instance": "/api/v1/users/password-resets",
  "code": "PASSWORD_RESET_VERIFICATION_FAILED",
  "traceId": "example-trace"
}
```

### 接口、额度键与错误码归属

| 接口 | Redis额度前缀 | 限流/不可用错误族 |
| --- | --- | --- |
| POST /api/v1/users/password-reset-requests | music:auth:password-reset:（发码各维度） | PASSWORD_RESET_REQUEST_* |
| POST /api/v1/users/password-reset-verifications | music:auth:password-reset-confirmation:（ip/user） | PASSWORD_RESET_VERIFICATION_* |
| POST /api/v1/users/password-resets | music:auth:password-reset-completion:（ip/user） | PASSWORD_RESET_CONFIRMATION_* |

错误族指RATE_LIMITED/UNAVAILABLE。第二、三步账户额度拒绝均使用PASSWORD_RESET_VERIFICATION_FAILED。普通失败保留公共title及detail，客户端按code处理。

## 失败结果与重复请求

票据明确消费后，数据库真实回滚或提交确认丢失均保留已消费状态。服务端自动重试、票据恢复及退款均关闭。503时可能已改密及撤销旧登录，应尝试新密码登录核实，必要时重新申请。消费结果未知时不执行写事务，票据可能已消费。

成功响应丢失后重试已消费票据返回400，无法据此认定首次未提交，无204重放或状态恢复接口。

## curl

先在安全本地文件写入上述两个字段，将文件路径放入PASSWORD_RESET_BODY_FILE，不将真实内容提交Git。生产使用HTTPS。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-resets' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_BODY_FILE}"
```
