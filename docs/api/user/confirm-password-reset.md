# 校验找回验证码并重置密码

## 方法与路径

`POST /api/v1/users/password-resets`

公开接口。无需登录、旧密码或近期认证。USER、ADMIN同规则，账户从服务端已激活申请绑定取得。主动附带其他账户有效Bearer不会改变重置目标。主动附带无效、过期、撤销Bearer仍受原认证链拒绝。

## 请求

媒体类型为`application/json`，charset省略或UTF-8。严格UTF-8 JSON最多16384字节，尾随空白计入预算。只接受下列三个字符串，各出现一次，顺序不限。缺失、重复（含转义同名）、未知字段、嵌套、多根、非字符串与无效编码均拒绝。

| 字段 | 规则 |
| --- | --- |
| challengeId | 必填，43字符规范Base64URL无填充，解码32字节且重编码相同，不trim；取自申请响应 |
| code | 必填，六位ASCII数字，保留前导零，不trim |
| newPassword | 必填，8至72位可打印ASCII（U+0020至U+007E），非全空格，保留首尾空格，不截断、不预哈希 |

```json
{
  "challengeId": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "code": "012345",
  "newPassword": "new-example-password"
}
```

确认密码由前端本地校验，无需上传。请求不接受email、userId、role或oldPassword。

## 验证与提交规则

格式通过后，先扣TCP客户端IP固定60秒10次额度，再读ACTIVE申请并扣绑定账户固定900秒10次额度。采用独立确认命名空间。两层顺序准入，前层获准额度及后续失败均不退款。当前拒绝层不增、不续窗，重发、设备切换和重新登录不会清额。不信任X-Forwarded-For。

只有IP拒绝返回429及Retry-After，值为原窗口剩余秒数向上取整且至少1。账户拒绝与其他正常验证失败统一400，没有Retry-After、剩余次数或拒绝层字段。IP获准、验证码比较前即确定普通失败时做一次同成本伪BCrypt比较，忽略结果后统一失败。真实错码仅一次真实验证码比较，IP拒绝及存储503无需伪比较。数据库与I/O仍有差异，不承诺完全等时。

申请沿用发码准入后的300秒原期限，计错、消费、重发均不延长。仅ACTIVE可兑换，累计第五次错码原子失效，正确码一次性消费。新申请仅pending时旧ACTIVE仍有效，新码明确激活后才替换旧码。不同用途验证码隔离。

验证码正确后复核账户并判断新旧密码等价。等价时返回NEW_PASSWORD_SAME_AS_OLD，保留原码、原期限、错误次数，当前提交额度已扣。新摘要生成成功并明确消费验证码后，短MySQL事务仅更新密码摘要、认证版本加1、UTC更新时间。消费时有效即可，后续提交允许跨原到期时间。

原始邮箱、角色、密码摘要或版本并发变化导致条件更新失败，统一400，不重新读取快照重试。仅用户名并发变化可成功并保留新用户名。

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
| 400 | PASSWORD_RESET_VERIFICATION_FAILED | 申请缺失/过期/未激活/被替换/已消费/五错失效、验证码错误、账户额度拒绝、绑定变化或条件更新0行 |
| 400 | NEW_PASSWORD_SAME_AS_OLD | 正确码证明后发现新旧密码等价，未消费 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | PASSWORD_RESET_CONFIRMATION_RATE_LIMITED | 仅IP额度拒绝，带Retry-After |
| 503 | PASSWORD_RESET_CONFIRMATION_UNAVAILABLE | Redis/DB/事务/摘要故障、损坏状态、认证版本上限或结果未知 |
| 500 | INTERNAL_ERROR | 未预期异常或不可能的更新行数 |
| 401 | UNAUTHORIZED / INVALID_TOKEN / TOKEN_REVOKED | 主动附带无效Bearer的原安全链错误；签名错误/过期可仅有WWW-Authenticate及空正文 |
| 403 | FORBIDDEN | 原安全链拒绝，无新增角色限制 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 主动附带Token时认证状态查询失败 |

“无账户”指原申请绑定的数据库账户已不存在，按绑定变化返回400 PASSWORD_RESET_VERIFICATION_FAILED。不存在邮箱的假申请未产生ACTIVE，按申请未激活或缺失返回同一400。ACTIVE缺少userId、rawEmail、role或tokenVersion等必需字段属于结构损坏，返回503 PASSWORD_RESET_CONFIRMATION_UNAVAILABLE。

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

### 发码与确认错误码归属

| 接口 | IP或发码额度拒绝 | 存储故障 |
| --- | --- | --- |
| POST /api/v1/users/password-reset-requests | PASSWORD_RESET_REQUEST_RATE_LIMITED | PASSWORD_RESET_REQUEST_UNAVAILABLE |
| POST /api/v1/users/password-resets | PASSWORD_RESET_CONFIRMATION_RATE_LIMITED，仅IP | PASSWORD_RESET_CONFIRMATION_UNAVAILABLE |

确认的账户额度拒绝始终使用PASSWORD_RESET_VERIFICATION_FAILED。

## 失败结果与重复请求

验证码明确消费后，无论数据库真实回滚还是提交确认丢失，都不会恢复验证码、退款或自动重试。503时可能已改密及撤销旧登录，应尝试新密码登录核实，必要时重新申请。消费结果未知时不执行写事务，验证码可能已消费。

成功响应丢失后重试旧申请返回400，无法据此认定首次未提交，无204重放或状态恢复接口。

## curl

先在安全本地文件写入上述三个字段，将文件路径放入PASSWORD_RESET_BODY_FILE，不将真实内容提交Git。生产使用HTTPS。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-resets' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_BODY_FILE}"
```
