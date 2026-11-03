# 再次验证本人身份

## 接口与权限

`POST /api/v1/users/me/reauthentications`

普通有效Bearer访问Token即可，USER/ADMIN只验证本人；不要求旧Token具有近期auth_time，缺少该声明的旧Token也可调用。过期、签名错误或已撤销的Token仍须恢复有效登录。

身份、原登录角色及认证版本取服务端可信认证结果，前端不能指定用户ID、邮箱、角色、版本或认证时间。查询/自定义头不覆盖这些值，额外JSON字段拒绝。本接口不修改账户任何字段、不更新登录活动、不递增token_version、不撤销原Token或其他登录。

这是修改邮箱等敏感操作前的身份验证步骤；新邮箱变更请求和最终确认接口未在本任务实现。

## 请求

Content-Type必须为application/json，UTF-8，charset可省略或声明UTF-8。媒体类型/字符集名称按HTTP规则不区分大小写；缺失、非法、其他媒体类型或字符集按本接口400 VALIDATION_FAILED拒绝。

密码方式严格两个字段：

```json
{
  "method": "PASSWORD",
  "password": "example-password"
}
```

邮箱验证码方式严格三个字段：

```json
{
  "method": "EMAIL_CODE",
  "challengeId": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "code": "012345"
}
```

| 字段 | 类型与校验 |
| --- | --- |
| method | 必填字符串，精确PASSWORD或EMAIL_CODE，区分大小写，不trim |
| password | 仅PASSWORD必填，字符串、非全空白、1—128个Java String字符；保留首尾空白，兼容历史密码，原值matches，不套用新密码ASCII/72字节创建规则 |
| challengeId | 仅EMAIL_CODE必填，规范43字符Base64URL无填充，解码32字节且回编码一致；来自[本人专用发送接口](request-reauthentication-email.md) |
| code | 仅EMAIL_CODE必填，六位ASCII数字字符串[0-9]，保留前导零，不接受数字类型或Unicode数字 |

缺体/null/空对象/非对象根/非法JSON/多根、重复键（含解码后同名）、未知字段、混用两方式、缺字段/null或任何隐式类型转换均400。局部字段名/字符串token上限128字符，仅允许根对象及字符串值，不改变其他接口的JSON规则。

EMAIL_CODE只接受当前绑定邮箱的专用ACTIVE请求；不能复用REGISTER/LOGIN、新邮箱验证凭据，不能验证PREPARING/PREPARED。未绑定邮箱时可使用PASSWORD；忘记密码且无可用当前邮箱的恢复流程不属于本接口。

## 成功响应

200 OK，Content-Type: application/json，Cache-Control:no-store，无Location，严格四字段：

```json
{
  "accessToken": "<新访问Token>",
  "tokenType": "Bearer",
  "expiresAt": "2026-11-04T06:00:00Z",
  "authenticatedAt": "2026-11-03T06:00:00Z"
}
```

| 字段 | 说明 |
| --- | --- |
| accessToken | 新签名访问Token，前端替换当前使用的Token；不得记录到日志 |
| tokenType | 固定Bearer |
| expiresAt | UTC ISO-8601访问Token到期；有效期沿现有配置，示例不承诺固定24小时 |
| authenticatedAt | UTC ISO-8601凭据匹配完成时间，与JWT整数秒auth_time相同；不是签发、消费或响应完成时间 |

新Token保持原登录角色和本次验证版本。以USER身份登录的ADMIN账户仍获得USER Token，不弹角色选择、不提升角色、不把旧验证升级到数据库新版本。内部loginType分别记录REAUTHENTICATION_PASSWORD或REAUTHENTICATION_EMAIL_CODE；它们不是公开登录接口的新请求选项，客户端仍应把Token作为不透明凭据管理。

原Token和其他登录保留各自原期限及原auth_time，不会同步获得新近期资格。候选认证时间在密码/码匹配后立即采样；后续消费、数据库复核与签发耗时计入300秒窗口，不能从收到响应重新计300秒。后续敏感接口按0≤now-authenticatedAt≤300秒（含端点）判断；缺失或窗口外可返回REAUTHENTICATION_REQUIRED，本接口自身不施加该门禁。

## 额度、错误次数与重复调用

- 每用户从首次准入起900秒内最多10次，PASSWORD/EMAIL_CODE、所有设备与Token共享；不是滑动窗口，后续请求不延长截止时间。
- 请求通过认证/上下文/格式检查后先准入，再查业务数据库。所有已准入结果均计数：成功、凭据错误、无邮箱、绑定变化、数据库/消费/签发故障均不退款；换Token、成功、重发验证码不重置额度。
- 第11次返回429，Retry-After为剩余秒数向上取整，1—900；拒绝不计数、不续期、不消费验证码。Redis不可用时密码方式同样拒绝，不降级放行。
- 同一ACTIVE请求累计五次码错误，第5次原子删除；正确验证也原子消费，并发同一请求最多一次消费成功。若五次错误先耗尽，即使另一请求已经完成正确匹配也不能消费。
- 错标识、旧请求或其他绑定不能扣新ACTIVE的错误次数，但已准入的接口额度仍计数。重发后新旧码数值偶然相同，也必须按challenge标识区分。
- PASSWORD成功不消费ACTIVE/pending；EMAIL_CODE成功只删除匹配ACTIVE，发送端pending和发送额度保持原状态。所有操作保持原验证码绝对到期，不续命。

## 错误

业务错误为application/problem+json，携带稳定code/type/instance，错误响应同样no-store。JWT签名错误、过期等沿用资源服务器既有401响应，可能没有ProblemDetail正文。

| 状态 | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 局部媒体类型/编码/JSON/字段绑定或校验失败，不准入 |
| 401 | UNAUTHORIZED | 未登录 |
| 401 | INVALID_TOKEN | 声明或可信上下文无效 |
| 401 | TOKEN_REVOKED | 账户消失、认证版本不等或当前登录角色已不被允许 |
| 401 | REAUTHENTICATION_FAILED | 密码错误/摘要变化；码错误、耗尽、过期、已消费、被替换或绑定失效；不回显剩余次数 |
| 409 | CURRENT_EMAIL_NOT_BOUND | EMAIL_CODE首轮数据库查询发现无绑定邮箱，已计额，可改用PASSWORD |
| 429 | REAUTHENTICATION_RATE_LIMITED | 用户额度耗尽，附Retry-After |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 既有认证过滤链读取账户状态故障 |
| 503 | REAUTHENTICATION_UNAVAILABLE | 本流程DB/事务/Redis故障，异常存储结构、TTL漂移、损坏或未知回包 |
| 500 | INTERNAL_ERROR | 未预期的校验、绑定组件或JWT签发故障；不泄漏底层细节 |

```json
{
  "type": "urn:problem:music:reauthentication_failed",
  "title": "身份验证失败",
  "status": 401,
  "detail": "身份验证失败，请检查凭据或重新申请验证码",
  "instance": "/api/v1/users/me/reauthentications",
  "code": "REAUTHENTICATION_FAILED"
}
```

REAUTHENTICATION_FAILED不表示原登录已经失效，前端保留验证弹窗和原操作上下文；不要把所有401统一跳登录页。TOKEN_REVOKED/访问Token确实无效时再恢复登录。邮箱方式消费后邮箱发生变化或解绑返回401 REAUTHENTICATION_FAILED，与首轮未绑定的409不同。

## 故障与并发边界

额度、错误计数、消费的Redis操作已执行但确认丢失时，503可能对应已计额、已计错/耗尽或已消费。服务端不自动重试、不退款、不恢复ACTIVE；不能承诺失败就没有副作用。

码已消费后最终数据库复核或签发失败，该码保持已消费，需在发送额度允许时重新申请或改用密码。Token已签出但响应失败/丢失，不能承诺未签发，不缓存可重放成功响应；PASSWORD再次调用须重新真实验证且继续计额。

最后DB复核和签发不是跨存储原子快照。若复核后立即改密或撤销，只会签出原验证版本Token，实际过滤链会拒绝它；接口200不保证并发撤销后仍能使用。旧证明不会被升级到新版本。

真实SMTP、代理共享IP桶、Redis7+/ACL、既有认证加固全实例升级及严格时钟同步，仍须按[邮件部署说明](../../deployment/reauthentication-email-requests.md)、[认证时间部署说明](../../deployment/auth-time-binding.md)单独验收。未来refresh应继承auth_time及原版本，本接口不实现refresh。

## curl

POSIX密码方式：

```sh
curl --request POST 'http://localhost:8080/api/v1/users/me/reauthentications' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data '{"method":"PASSWORD","password":"example-password"}'
```

PowerShell邮箱验证码方式（stdin保留JSON引号）：

```powershell
'{"method":"EMAIL_CODE","challengeId":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","code":"012345"}' | curl.exe --request POST 'http://localhost:8080/api/v1/users/me/reauthentications' --header "Authorization: Bearer $env:ACCESS_TOKEN" --header 'Content-Type: application/json' --data-binary '@-'
```

ACCESS_TOKEN、示例密码、码和标识均须替换为本人有效凭据；示例为虚构值，不应写入应用日志或共享脚本。
