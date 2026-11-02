# 用户登录

## 接口

- 方法：`POST`
- 路径：`/api/v1/auth/login`
- 权限：公开，无需已有 Bearer Token
- Content-Type：`application/json`
- 成功状态码：`200 OK`

支持用户名密码、邮箱密码、邮箱验证码登录。管理员认证成功后通过同一接口完成身份选择。

## 登录模式

| `loginType` | 字段 | 说明 |
| --- | --- | --- |
| `USERNAME_PASSWORD` | `account`、`password` | `account` 为用户名 |
| `EMAIL_PASSWORD` | `account`、`password` | `account` 为邮箱 |
| `EMAIL_CODE` | `email`、`code` | 使用用途为 `LOGIN` 的验证码 |
| `ROLE_SELECTION` | `loginTicket`、`loginRole` | 管理员第二阶段身份选择 |

请求字段：

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `loginType` | enum | 必填 |
| `account` | string/null | 用户名 3-80 位或合法邮箱 |
| `password` | string/null | 明文密码，8-128 位 |
| `email` | string/null | 合法邮箱，最多 120 位 |
| `code` | string/null | 6 位数字 |
| `loginTicket` | string/null | 管理员一次性身份选择票据 |
| `loginRole` | enum/null | `USER` 或 `ADMIN` |

### 用户名密码

```json
{
  "loginType": "USERNAME_PASSWORD",
  "account": "alice",
  "password": "correct-password"
}
```

### 邮箱密码

```json
{
  "loginType": "EMAIL_PASSWORD",
  "account": "alice@example.com",
  "password": "correct-password"
}
```

### 邮箱验证码

```json
{
  "loginType": "EMAIL_CODE",
  "email": "alice@example.com",
  "code": "123456"
}
```

### 管理员身份选择

```json
{
  "loginType": "ROLE_SELECTION",
  "loginTicket": "one-time-ticket",
  "loginRole": "ADMIN"
}
```

## 成功响应

普通用户或完成身份选择的管理员：

```json
{
  "status": "AUTHENTICATED",
  "authentication": {
    "accessToken": "jwt",
    "tokenType": "Bearer",
    "expiresAt": "2026-10-24T23:10:00Z",
    "user": {
      "id": 42,
      "username": "alice",
      "email": "alice@example.com",
      "role": "USER"
    }
  },
  "roleSelection": null
}
```

管理员第一阶段：

```json
{
  "status": "ROLE_SELECTION_REQUIRED",
  "authentication": null,
  "roleSelection": {
    "loginTicket": "one-time-ticket",
    "expiresAt": "2026-10-23T23:15:00Z"
  }
}
```

管理员选择 `USER` 时，JWT `role` 为 `USER`；选择 `ADMIN` 时，JWT `role` 为 `ADMIN`。普通用户只能得到 `USER`。

数据库 `users.user_role` 是最高权限角色，JWT `role` 是本次实际登录身份。密码校验使用的同一用户记录、或验证码消费前读取的用户记录，产生绑定角色和 tokenVersion 的内部验证结果。直接签发 JWT 或生成管理员 Ticket 前，必须复核账户仍存在、当前角色与版本均与验证结果一致，不得用新版本升级旧验证结果。

初始复核失败统一返回 `401 INVALID_CREDENTIALS`。这包括验证后账户消失、角色变化或版本变化；账户消失的普通登录路径不再返回不适用于该阶段的 `LOGIN_TICKET_INVALID`。Ticket 兑换阶段的上述失效仍返回 `401 LOGIN_TICKET_INVALID`。

复核成功后只使用该次不可变快照签发。若撤销发生在最后复核之后，登录可能仍返回 200 和旧版本 JWT，但它随后访问受保护接口时会被拒绝；不保证所有并发撤销都使登录请求当场返回 401。详见下文与[部署边界](../../deployment/auth-version-binding.md)。

## JWT

- 算法：HS256。
- Access Token 有效期：24 小时。
- 请求使用 `Authorization: Bearer <accessToken>`。
- Claims：`sub`、`role`、`loginType`、`tokenVersion`、`jti`、`iat`、`exp`、`auth_time`。
- `role` 是本次实际登录身份，必须不超过数据库当前最高角色。
- `tokenVersion` 来自已匹配凭据验证结果或 Ticket 绑定版本的 MySQL 角色/版本快照；签发时不再另查新版本替换它。
- 每个认证请求都会单条查询数据库最高角色和 `tokenVersion`；版本不匹配或登录角色超过最高角色时返回 `401 TOKEN_REVOKED`。
- `JwtAuthenticationConverterProvider` 不直接信任 `role` claim；只有数据库校验通过后才生成对应权限。
- `loginType` 为原始登录方式，不使用 `ROLE_SELECTION`。
- 不返回密码摘要、验证码或登录票据摘要。

### 认证时间

实际登录签发的JWT携带JSON整数Unix秒的`auth_time`，表示完成凭据验证的UTC时间。密码路径在matches成功后立即记时，邮箱验证码路径在validate成功后记时、consume成功才继续；后续查询、角色选择、Ticket兑换和签发都不重置该时间。`iat`仍是当前Token签发时间，二者不能互相代替。按秒向下取整，不改变24小时访问Token有效期、请求或成功响应DTO。

缺少`auth_time`的旧Token按原到期/角色/版本规则继续普通访问，但没有近期验证证据。声明存在却为null、字符串、小数、负数、溢出、未来或晚于真实`iat`时，受保护链返回401 INVALID_TOKEN；不能当作字段缺失降级。声明存在时`iat`必须真实存在，不能使用转换器推算值。不存在额外未来时间宽限，部署需同步实例时钟。

校验后的认证时间和版本只保存在内部Authentication.details，不加入用户资料响应；前端参数/头部不能覆盖。当前没有300秒业务门禁、REAUTHENTICATION_REQUIRED映射或再次验证/refresh接口。未来refresh必须继承原验证时间和有效版本，不以刷新时间重置资格；未来敏感接口会单独检查300秒窗口。详见[认证时间部署边界](../../deployment/auth-time-binding.md)。

## 验证码登录

邮箱验证码登录使用：

```text
music:verification:code:LOGIN:<normalized-email>
```

验证码验证成功后立即消费。登录失败、过期、已使用或不匹配统一返回 `401 INVALID_CREDENTIALS`。

验证码消费后若账户版本/角色复核或后续处理失败，不恢复已消费验证码，需要重新发起验证码登录流程。未消费且仍有效的验证码继续按既有规则使用，不因认证版本绑定额外撤销。

## 管理员 Ticket

Ticket 是一次性角色选择凭据，服务端以一次 Redis Lua 操作保存 userId、maximumRole、loginType、tokenVersion、authTime 五字段及 300 秒 TTL。版本及认证时间取自原验证结果，不接受客户端提供或回填当前时间，也不升级旧验证结果。

兑换时原子检查 PTTL、读取并删除 Ticket，再复核当前账户版本和角色。版本 0 合法，但缺失版本不等于 0；旧格式、无有效 TTL、未知/缺失字段、非规范或溢出版本、已消费、过期及版本/角色变化均返回 `401 LOGIN_TICKET_INVALID`。

authTime必须为规范非负ASCII十进制Unix秒文本且不在未来；缺失、非法类型/数值、越界或未来同样返回LOGIN_TICKET_INVALID。0是真实旧时间，不是默认值。旧四字段票据拒绝并重新登录。Ticket剩余TTL不代表验证时间；Java验证时间已经超过300秒但Ticket真实TTL仍有效时，允许普通登录，JWT保留旧auth_time，不因此获得近期资格。

消费后的 Ticket 不因权限、状态查询、签发或网络失败而恢复；需要重新发起登录。Redis 超时可能已经消费 Ticket，返回 503 不保证凭据仍可使用。签发前复核后若又发生撤销，可能返回旧版本 Ticket，但其后续兑换必定无法升级为新版本有效 Token。

现有随机 Ticket 格式、角色选择请求与成功响应不变。TTL 以 Redis 为准，不因兑换或失败延期。上线必须让全部旧登录实例退出服务；版本绑定条件见[原部署说明](../../deployment/auth-version-binding.md)，五字段发布兼容与时钟验收以[认证时间部署说明](../../deployment/auth-time-binding.md)为准。

发送登录验证码：

```bash
curl -X POST "http://localhost:8080/api/v1/email/send-code" \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","purpose":"LOGIN"}'
```

## 错误格式

错误使用 RFC 9457 `ProblemDetail`，包含稳定字段 `code`。

| HTTP | `code` | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段组合或格式错误 |
| 401 | `INVALID_CREDENTIALS` | 用户名、邮箱、密码或验证码认证失败；初始复核时账户不存在或版本/角色变化 |
| 401 | `LOGIN_TICKET_INVALID` | Ticket 无效、过期、重放、无版本/TTL、字段/版本非法，或兑换时账户/版本/角色失效 |
| 403 | `ROLE_SELECTION_NOT_ALLOWED` | 普通用户选择 `ADMIN` |
| 429 | `LOGIN_RATE_LIMITED` | 登录请求或失败次数超限 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |
| 503 | `AUTH_STATE_STORE_UNAVAILABLE` | 登录状态、Ticket、限流或认证状态存储不可用 |
| 503 | `VERIFICATION_CODE_STORE_UNAVAILABLE` | 验证码 Redis 不可用 |

限流响应包含 `Retry-After`：

- 同一账号/IP：15 分钟内最多 5 次失败，成功后重置。
- 同一 IP：每分钟最多 20 次登录请求。

## JWT 配置

| 变量 | 说明 |
| --- | --- |
| `JWT_SECRET` | HS256 签名密钥，至少 32 字节 |
| `JWT_ISSUER` | Token issuer，默认契约为 `music-api` |
| `JWT_AUDIENCE` | Token audience，默认契约为 `music-client` |
| `JWT_TTL_SECONDS` | Access Token 秒数，默认契约为 `86400` |

## curl 示例

```bash
curl -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"loginType":"USERNAME_PASSWORD","account":"alice","password":"correct-password"}'
```

管理员第二阶段：

```bash
curl -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"loginType":"ROLE_SELECTION","loginTicket":"one-time-ticket","loginRole":"ADMIN"}'
```

查看 OpenAPI：

- JSON：`GET /v3/api-docs`
- UI：`GET /swagger-ui.html`
