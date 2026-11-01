# 修改个人资料

- Method：`PATCH`
- 完整 Path：`/api/v1/users/me`
- Content-Type：`application/json`
- 用途：修改当前登录用户本人的 username。
- 权限：有效 Bearer JWT；USER 与 ADMIN 登录身份均可操作本人，身份仅来自服务端认证主体，不接受客户端用户 ID 替代。

## 请求字段与严格校验

这是普通 JSON 对象接口，不使用 JSON Patch 或 JSON Merge Patch 操作数组。

| 字段 | 类型 | 必填 | 校验 |
| --- | --- | --- | --- |
| username | string | 是 | 3—20 位 ASCII 英文字母、数字或下划线，保留大小写；不删除首尾空白、不自动转小写 |

```json
{"username":"alice_new"}
```

仅允许一个 username 键，拒绝重复键（包含转义后名称相同的键）、任意未知字段和隐式字符串转换。缺体、空对象、非法 JSON、非对象根值、多个根值、缺键、null、空串、空白字符、非法长度/字符均返回 `400 VALIDATION_FAILED`。

数字 `123` 非法，字符串 `"123"` 在长度合法时允许。邮箱、密码、角色、用户 ID、tokenVersion、审计时间等字段不能出现在请求对象中，值为 null 也拒绝。例如 `{"username":"alice_new","email":null}` 整个请求失败，不修改任何字段。

无路径用户 ID、版本、业务查询参数或幂等键。未知查询参数忽略；userId、loginRole 等查询参数、自定义身份头均不决定身份或更新内容。认证先于请求体业务校验，未认证请求按现有安全链拒绝。

## 用户名与并发规则

- 全局唯一性沿用 MySQL 既有大小写不敏感规则：他人占用 Alice 时，改为 alice 返回 409。
- 本人 Alice 改为 alice 允许，保存请求大小写并更新时间。
- 与本人当前用户名逐字节完全相同时成功返回，不更新 updated_at；缺少字段不表示无变化。
- 唯一索引兜底并发竞争。同一用户多次并发改名按数据库执行顺序生效，后执行的成功更新可以覆盖前一次，没有版本冲突保护。
- 只修改 username 与实际变化时的 updated_at；updated_at 为数据库当前 UTC 时间。邮箱、密码摘要、角色、tokenVersion、创建时间与最后登录时间均不修改。

## 成功响应

`200 OK`，`application/json`，`Cache-Control: no-store`。响应复用 GET 当前用户信息的六字段白名单。

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| id | integer | 本人 ID，Java Integer |
| username | string | 更新后的用户名，保留大小写 |
| email | string 或 null | 本人完整邮箱，空时保留 null |
| userRole | USER / ADMIN | 数据库账户角色 |
| loginRole | USER / ADMIN | 本次已验证的登录身份 |
| createdAt | UTC ISO-8601 string 或 null | 原账户创建时间，保留微秒及显式 null |

```json
{
  "id": 42,
  "username": "alice_new",
  "email": "alice@example.com",
  "userRole": "ADMIN",
  "loginRole": "USER",
  "createdAt": "2026-10-21T12:34:56.123456Z"
}
```

管理员以 USER 身份登录时不提升权限，loginRole 仍为 USER。响应不包含密码、密码摘要、tokenVersion、访问 Token、验证码或其他内部字段。

## 错误响应

业务错误为 `application/problem+json`，包含稳定 code；type 使用 `urn:problem:music:` 加 code 小写并保留下划线。错误响应禁止缓存，不向客户端泄漏 SQL、底层异常或占用用户名的其他账户信息。

| HTTP | code | 条件 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | JSON、字段白名单、重复键、类型、长度或字符非法 |
| 401 | UNAUTHORIZED | 未认证 |
| 401 | INVALID_TOKEN | JWT 必需主体、角色或版本声明非法 |
| 401 | TOKEN_REVOKED | 账户不存在、版本已失效、登录角色不再允许；包含认证后账户消失 |
| 409 | USERNAME_ALREADY_EXISTS | 用户名已被其他账户占用，包含并发唯一键冲突 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 既有认证状态查询失败 |
| 503 | USER_PROFILE_UPDATE_UNAVAILABLE | 修改、投影、事务启动或提交确认发生存储故障，包含死锁与锁等待超时 |
| 500 | INTERNAL_ERROR | 未预期错误 |

用户名冲突的 detail 为通用“用户名已占用”，不返回占用者 ID 或邮箱。

```json
{
  "type": "urn:problem:music:user_profile_update_unavailable",
  "title": "个人资料更新不可用",
  "status": 503,
  "detail": "个人资料暂时无法更新，请稍后重试",
  "instance": "/api/v1/users/me",
  "code": "USER_PROFILE_UPDATE_UNAVAILABLE"
}
```

JWT 签名错误、过期及资源服务器解析失败仍沿用既有 401 与 WWW-Authenticate 行为，可能没有 ProblemDetail 响应体。不新增正常已认证身份的 403、资源 404 或专用限流 429 分支。

## 事务、重复提交与登录

UPDATE、白名单查询及响应组装在同一短写事务中；提交确认成功后返回 200。投影查询失败会回滚未提交改名。提交确认阶段若连接中断，服务端可能无法确认数据库是否已经提交，因此 503 不保证修改一定未生效；调用方可重新 GET 查询或重复提交目标用户名。

当前值与请求完全相同的重复请求不更新时间；若期间有其他改名，下一次请求按普通修改处理。不提供跨请求快照、客户端版本保护或自动死锁重试。

改名不递增 tokenVersion，已有 Token 继续按用户 ID 识别本人。后续 GET 返回新用户名；实际改成不同名称后，新用户名可用于密码登录，旧名称不再指向本人，也不保留别名或占用记录。若仅改变大小写，原拼写仍可通过大小写不敏感匹配。邮箱登录规则不变，仍使用现有限流与认证流程。

认证状态校验与资料修改不是原子快照，并发角色变更仍通过既有撤销机制使后续请求失效，不承诺中止所有已进入业务层的请求。

本次不新增改名专用限流或冷却期，不将认证校验等同于写操作限流，也不假定部署层已提供改名频率控制。

## curl 示例

调用者预先在环境中设置自己的 ACCESS_TOKEN，不在文档中填写真实凭证。

```sh
curl --request PATCH 'http://localhost:8080/api/v1/users/me' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data '{"username":"alice_new"}'
```

Windows PowerShell（通过标准输入传递 JSON，避免不同 PowerShell 版本的原生命令引号差异）：

```powershell
'{"username":"alice_new"}' | curl.exe --request PATCH "http://localhost:8080/api/v1/users/me" --header "Authorization: Bearer $env:ACCESS_TOKEN" --header "Content-Type: application/json" --data-binary '@-'
```
