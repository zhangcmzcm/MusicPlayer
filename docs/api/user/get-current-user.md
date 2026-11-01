# 获取当前用户信息

- Method：`GET`
- 完整 Path：`/api/v1/users/me`
- 用途：读取本人允许展示的账户资料、账户角色及本次登录身份。
- 认证：有效 Bearer JWT；USER 和 ADMIN 登录身份均可访问，仅可读取本人。

## 请求与权限

无请求体、路径用户 ID 或业务查询参数，不需要分页、排序或幂等键。未知查询参数忽略；即使携带 `userId`、`id`、`role`、`loginRole` 或其重复值，也不会改变身份、查询对象或权限。

本人 ID 仅取自服务端验证后的 `AuthenticatedUserPrincipal.userId`。不信任前端提交的用户 ID 或角色。JWT 沿用现有签名、issuer、audience、有效期、token version 和角色校验。

```http
GET /api/v1/users/me
Authorization: Bearer <ACCESS_TOKEN>
```

## 成功响应

`200 OK`，`Content-Type: application/json`，`Cache-Control: no-store`。

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| id | integer | 本人 ID，Java Integer |
| username | string | 当前用户名 |
| email | string 或 null | 本人完整邮箱，不脱敏；空值保留字段 |
| userRole | USER / ADMIN | 资料查询时数据库中的账户角色 |
| loginRole | USER / ADMIN | 本次验证通过的登录身份 |
| createdAt | UTC ISO-8601 string 或 null | 账户创建时间；历史记录为空时保留 null，不伪造时间 |

管理员选择普通用户身份登录的示例：

```json
{
  "id": 42,
  "username": "alice",
  "email": "alice@example.com",
  "userRole": "ADMIN",
  "loginRole": "USER",
  "createdAt": "2026-10-21T12:34:56Z"
}
```

前端以 loginRole 展示本次可用操作，不能因为 userRole=ADMIN 就执行管理员操作；服务端继续独立鉴权。响应严格只有上述六个字段，不返回密码摘要、password_token、tokenVersion、访问 Token、验证码或其他内部字段。

## 错误响应

业务错误为 `application/problem+json`，携带稳定 code，type 为 `urn:problem:music:` 加 code 的小写形式（保留下划线）。

| HTTP | code | 条件 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 缺少认证 |
| 401 | INVALID_TOKEN | JWT 必需身份、角色或版本声明不合法 |
| 401 | TOKEN_REVOKED | 账户不存在、版本失效、角色不再允许；或认证通过后资料查询发现本人账户已消失 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 现有认证状态查询失败 |
| 503 | USER_PROFILE_STORE_UNAVAILABLE | 资料查询或只读事务发生存储故障 |
| 500 | INTERNAL_ERROR | 未预期错误 |

JWT 签名错误、过期或资源服务器解析失败沿用现有安全链的 401 与 WWW-Authenticate 行为，可能没有 ProblemDetail 响应体，不承诺返回上表某个 code。

```json
{
  "type": "urn:problem:music:user_profile_store_unavailable",
  "title": "用户资料存储不可用",
  "status": 503,
  "detail": "用户资料暂时无法读取，请稍后重试",
  "instance": "/api/v1/users/me",
  "code": "USER_PROFILE_STORE_UNAVAILABLE"
}
```

本人账户消失的 type 为 `urn:problem:music:token_revoked`。此接口没有正常已认证身份的 403、目标资源 404 或写入冲突 409 分支。

## 一致性与副作用

认证状态校验和资料查询是两个阶段，不是原子快照。userRole 来自资料查询时的数据库，loginRole 来自本次已验证主体。并发角色变更通过现有 token version 机制在后续请求校验，不承诺中止所有已进入业务层的请求。

查询采用只读事务，不更新最后登录时间或其他账户字段；重复请求没有业务写入副作用。不新增缓存或独立限流。

## curl

先在调用环境设置 ACCESS_TOKEN 为自己的有效凭证；示例不包含真实凭证。

```sh
curl --request GET 'http://localhost:8080/api/v1/users/me' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```

Windows PowerShell：

```powershell
curl.exe --request GET "http://localhost:8080/api/v1/users/me" --header "Authorization: Bearer $env:ACCESS_TOKEN"
```
