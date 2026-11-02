# 修改本人密码

- Method：`PUT`
- Path：`/api/v1/users/me/password`
- Content-Type：`application/json`
- 认证：有效 Bearer JWT；USER、ADMIN 登录身份均可，只操作认证主体本人，Integer 用户 ID 不由前端指定。

## 请求

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| oldPassword | string | 必填、非 null、非全空白，1—128 个 Java String 字符；兼容历史短密码和非 ASCII 密码，不新增72字节上限 |
| newPassword | string | 必填、非全空格，8—72 个 ASCII 可打印字符（U+0020—U+007E），不能与当前密码等价 |

仅两个字段，各一次，顺序不限。重复键（含转义同名）、未知字段、缺字段、非字符串、非对象根值、多根值、JSON null 均400。拒绝 userId、角色、邮箱、tokenVersion、确认密码等字段。查询参数与自定义身份头不改变目标账户。

新密码允许字母、数字、英文符号和普通空格，不要求多种字符组合；禁止中文、emoji、非 ASCII 和控制字符。两字段原值处理，首尾空格保留，不 trim、不规范化、不截断、不预哈希。原密码正确后才检查新密码等价，使用真实 BCrypt 匹配而非比较随机盐摘要。

```json
{
  "oldPassword": "old-example-password",
  "newPassword": "new-example-password"
}
```

## 成功与撤销

成功返回 **204 No Content**，`Cache-Control: no-store`，无响应体、不签发新 Token、不返回密码摘要或版本。

同一短事务修改密码摘要、递增 token_version、更新 UTC 时间（微秒）。当前调用 Token、其他设备及其他登录角色的全部旧 Token 在后续数据库版本校验时返回401 TOKEN_REVOKED。客户端应清除当前 Token，以新密码重新登录。旧密码不能完成新的密码验证，邮箱验证码登录仍可使用。

旧密码验证结果与旧角色选择 Ticket 不得升级成新版本有效 Token。最终版本复核后仍可能签出旧版本 Token，但它访问受保护资源时会被拒绝。撤销不承诺中途取消已通过认证的业务请求或已开始媒体传输。

成功后用旧 Token 重复改密通常401，不保证重复返回204。不自动重试并发冲突。

## 限流与事务边界

按本人 userId 独立固定窗口：首次有效格式请求起900秒最多5次，任何 BCrypt 前消费配额。第6次起429，`Retry-After` 为实际 PTTL 向上取整的秒数，至少1；超限不延长窗口。

原密码错误、密码等价、CAS冲突或数据库失败均消耗额度；成功、重新登录、换设备/Token 不重置。JSON或字段校验失败不消耗此改密配额。Redis不可用、状态损坏或结果无法确认返回503，不继续密码运算或写入；不自动删除限流记录放行。Redis超时可能已计数，不承诺计数回滚。

BCrypt与Redis不在数据库写事务内。按用户ID、二进制旧摘要、旧版本条件更新，最多一个同快照并发请求成功，冲突409。账户消失401；版本达有符号BIGINT上限拒绝更新为503，不回绕。只更新 password_token、token_version、updated_at；不修改用户名、邮箱、角色、创建时间或最后登录时间。

数据库错误回滚未提交更新；提交确认故障503时数据库可能已提交，密码与撤销状态可能已生效，应通过登录确认，不承诺“失败一定未修改”。Redis配额与MySQL不构成分布式事务。

## 错误

业务错误为 RFC 9457 `application/problem+json`，携带稳定 code、下划线 urn，通过响应头 `X-Trace-Id` 关联日志，禁止缓存，不含密码、摘要、底层SQL或Redis key。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | JSON、字段、类型、重复键、字符或长度非法 |
| 400 | OLD_PASSWORD_INCORRECT | 原密码不正确 |
| 400 | NEW_PASSWORD_SAME_AS_OLD | 新密码与当前密码等价 |
| 401 | UNAUTHORIZED | 缺少认证 |
| 401 | INVALID_TOKEN | JWT必需声明非法 |
| 401 | TOKEN_REVOKED | 版本失效、角色不再允许或本人账户不存在 |
| 409 | PASSWORD_CHANGE_CONFLICT | CAS快照冲突，不自动重试 |
| 429 | PASSWORD_CHANGE_RATE_LIMITED | 配额耗尽，带Retry-After |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态查询失败 |
| 503 | PASSWORD_CHANGE_RATE_LIMIT_UNAVAILABLE | 配额存储故障或无法可靠判断 |
| 503 | PASSWORD_CHANGE_STORE_UNAVAILABLE | 密码读写、版本上限、事务启动/提交故障 |
| 500 | INTERNAL_ERROR | 未预期故障 |

```json
{
  "type": "urn:problem:music:old_password_incorrect",
  "title": "原密码不正确",
  "status": 400,
  "detail": "原密码不正确",
  "instance": "/api/v1/users/me/password",
  "code": "OLD_PASSWORD_INCORRECT"
}
```

签名错误、过期及资源服务器解析失败沿用既有响应与 WWW-Authenticate，可能无ProblemDetail响应体。本接口不存在正常角色的额外403或本人资源404分支。

## curl

仅为环境变量示意。调用者安全准备 PASSWORD_CHANGE_BODY_FILE，内容为两个密码字段；文件不入Git，调用后按本地安全策略清理。通过HTTPS发送，不把密码放在URL。以下不包含真实Token或密码。

```sh
curl --request PUT 'https://music.example.com/api/v1/users/me/password' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_CHANGE_BODY_FILE}"
```

```powershell
curl.exe --request PUT 'https://music.example.com/api/v1/users/me/password' `
  --header "Authorization: Bearer $env:ACCESS_TOKEN" `
  --header 'Content-Type: application/json' `
  --data-binary "@$env:PASSWORD_CHANGE_BODY_FILE"
```

## 部署前置条件

必须完成[登录认证版本绑定全实例升级与旧请求排空](../../deployment/auth-version-binding.md)，才能在环境中宣告全部旧凭据撤销保证。不能与旧登录代码混合提供服务；回滚旧登录代码时需停止依赖该保证的改密上线流程。
