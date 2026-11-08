# 独立验证找回密码验证码

## 方法与路径

`POST /api/v1/users/password-reset-verifications`

公开接口，USER与ADMIN同规则，无需旧密码、登录或近期auth_time。目标来自服务端ACTIVE绑定，主动他账户有效Bearer不改变目标，主动无效/过期/撤销Bearer仍受原安全链拒绝。

本接口交付三步流程的第二步，成功授予一次性设置新密码资格。前端待第三步resetTicket/newPassword改造也审核通过后整体切换。当前POST /api/v1/users/password-resets仍使用challengeId/code/newPassword原组合契约。

## 请求与校验

严格UTF-8 application/json，charset省略或UTF-8，正文最多16384字节，含尾随空白。仅两个字符串，各出现一次，顺序不限。缺失/null/类型错误、未知字段、重复或转义重复、嵌套、多根、非法编码均400。媒体类型不支持415。

| 字段 | 规则 |
| --- | --- |
| challengeId | 必填，恰43字符规范Base64URL无填充，解码32字节且重编码相同，原值保留 |
| code | 必填，恰六位ASCII数字，前导零保留，原值保留 |

```json
{
  "challengeId": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "code": "012345"
}
```

newPassword、email、userId、role与resetTicket均属于未知字段。

## 成功响应

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{
  "resetTicket": "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
  "expiresAt": "2026-11-08T12:05:00Z"
}
```

恰两个字段。resetTicket为独立32字节随机数的43字符规范Base64URL，Redis仅保存其SHA-256摘要及绑定；前端内存暂存，后续由第三步请求体携带。expiresAt为Redis成功兑换时间加300秒，UTC ISO-8601。

每账户仅最新一张票有效，新票签发成功时替换旧票。单纯申请、排队、发送或激活新的邮箱验证码，已签发票仍按自身期限有效。票绑定账户ID、原始及规范化邮箱、角色、认证版本，第三步须复核当前绑定。

本接口用户表只读，原密码、认证版本、真实认证时间和旧登录资格均由各自既有规则维护。密码更新与旧登录撤销由第三步完成。

## 额度与验证码

与当前旧组合提交接口共用IP固定60秒10次、账户固定900秒10次预算。格式通过先扣remoteAddr的IP摘要额度，完整ACTIVE后扣绑定账户额度，之后读取DB及比较验证码。未知/未激活/假申请扣IP但不创建用户键。

获准的前层及后续失败不退款，当前拒绝层不增计数、不延长窗口。重发、设备/Token变化不清额。仅IP拒绝429与Retry-After，剩余秒数由原Redis窗口向上取整且至少1。账户拒绝与正常验证失败统一400，没有等待、拒绝层、scope或剩余次数。

普通预验证码失败进行一次同源成本伪BCrypt比较并忽略结果；真实错码只做一次真实比较。IP拒绝和存储503不补比较，正确码后绑定或交换冲突也不补比较。不使用sleep填时，不承诺完全等时。

验证码保留原准入后300秒期限，累计第五错失效。兑换CAS匹配完整不可变绑定及当前指针，读取后非终态attempts变化仍允许正确兑换。正确兑换在单Lua中消费验证码、签发新票并替换旧票，最多一次成功。

## 错误

业务错误采用application/problem+json、稳定code与urn:problem:music:加小写下划线code，均禁止缓存。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 参数、JSON、编码或字节预算非法 |
| 400 | PASSWORD_RESET_VERIFICATION_FAILED | 申请缺失/未激活/假申请/过期/替换/消费/五错、错码、账户额度拒绝、绑定变化或已知交换冲突 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | PASSWORD_RESET_VERIFICATION_RATE_LIMITED | 仅IP额度拒绝，带Retry-After |
| 503 | PASSWORD_RESET_VERIFICATION_UNAVAILABLE | DB/Redis故障、可定位状态损坏、候选摘要碰撞或结果未知 |
| 500 | INTERNAL_ERROR | 未预期异常 |
| 401/403 | 既有认证授权错误码 | 主动Bearer的原安全链拒绝，签名/到期错误可能空正文及WWW-Authenticate |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 主动Bearer认证状态查询失败 |

统一验证失败title为“密码重置未完成”，detail为“验证码或找回申请无效，请检查或重新申请”，不对外细分账户、票或验证码状态。数据库绑定账户已不存在为400，能定位的ACTIVE/票缺必需字段、所有者或TTL矛盾为503。

## 接口、额度键与错误码对照

当前阶段如下，键前缀是内部兼容命名，按接口归属选择HTTP错误码。第二步与旧组合入口共享confirmation键。

| 接口 | Redis额度键前缀 | 限流/不可用错误族 |
| --- | --- | --- |
| POST /api/v1/users/password-reset-requests | music:auth:password-reset:（email-cooldown/email-daily/ip） | PASSWORD_RESET_REQUEST_* |
| POST /api/v1/users/password-reset-verifications | music:auth:password-reset-confirmation:（ip/user） | PASSWORD_RESET_VERIFICATION_* |
| POST /api/v1/users/password-resets，当前组合契约 | music:auth:password-reset-confirmation:（ip/user） | PASSWORD_RESET_CONFIRMATION_* |

最终阶段规划如下，第三步新契约及额度为后续交付。

| 接口 | Redis额度键前缀 | 限流/不可用错误族 |
| --- | --- | --- |
| POST /api/v1/users/password-reset-requests | music:auth:password-reset:（email-cooldown/email-daily/ip） | PASSWORD_RESET_REQUEST_* |
| POST /api/v1/users/password-reset-verifications | music:auth:password-reset-confirmation:（ip/user） | PASSWORD_RESET_VERIFICATION_* |
| POST /api/v1/users/password-resets，后续resetTicket/newPassword | music:auth:password-reset-completion:（ip/user） | PASSWORD_RESET_CONFIRMATION_* |

上述错误族指RATE_LIMITED/UNAVAILABLE，账户及普通验证失败统一PASSWORD_RESET_VERIFICATION_FAILED。实际票数据位于music:auth:password-reset-ticket:active:<userId>与ticket:<digest>，用途和额度键分开。

## 重复与故障恢复

明确兑换成功后，旧邮箱验证码已消费，再次提交统一400，既有成功响应不重放，期限不续期。

兑换结果未知返回503。操作未执行时原验证码可能仍可成功，实际执行后则旧码失效、新票可能已签发且旧票已替换。服务端不自动重试、退款或恢复，恢复采用重新申请邮件验证码并遵守原额度与冷却。成功200响应丢失也采用此恢复路径。

DB复核与Redis兑换存在跨存储间隙，期间凭据变更时票仍保存旧绑定，第三步需拒绝。已有活动更高版本票时，低版本迟到兑换失败并保留它。

## curl

安全准备PASSWORD_RESET_VERIFICATION_BODY_FILE，文件包含challengeId/code，文件不入Git，生产使用HTTPS。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-reset-verifications' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_VERIFICATION_BODY_FILE}"
```
