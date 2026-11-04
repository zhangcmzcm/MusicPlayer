# 确认本人邮箱变更

`PUT /api/v1/users/me/email`

验证新邮箱专用码，完成首次绑定或更换邮箱。需要有效Bearer JWT，USER/ADMIN仅操作本人。成功更新邮箱并递增认证版本，当前及其他全部旧登录失效，前端清除Token后重新登录。

## 近期身份验证

可信auth_time距业务准入不超过300秒，包含300秒端点；不看iat。缺失或超窗返回401 REAUTHENTICATION_REQUIRED，保留输入并通过已有再次验证流程获取新Token，在申请原有效期内继续。普通登录过期或撤销按现有认证错误处理，需恢复有效登录。

近期门禁先于正文解析，只检查一次；解析/校验期间跨过窗口不重新拒绝，但Redis消费时申请仍须未到期。

## 请求

Content-Type为application/json，可显式指定charset=UTF-8。缺失、非法或不支持的媒体类型/非UTF-8字符集返回415。严格UTF-8及JSON校验，正文含尾随空白最多16384字节，未知长度或分块输入同样检查，超限400。

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| challengeId | string，必填 | 发码返回的32字节标识，规范Base64URL无填充，恰43字符；解码并重编码必须相同 |
| code | string，必填 | 恰6位ASCII数字，保留前导零，不trim，不接受数字类型或Unicode数字 |

只允许两个字段。空体、null、未知/重复（含转义同名）字段、嵌套、非字符串、多JSON根、非法编码均400。目标邮箱只从服务端ACTIVE读取，不接受newEmail、用户ID、原邮箱、版本或角色覆盖。

```json
{"challengeId":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","code":"012345"}
```

## 成功

204 No Content，Cache-Control: no-store，无响应正文、Location或新Token。数据库事务明确提交后才返回；邮箱、token_version加1及UTC updated_at同时提交，其他用户字段保持。后续携带任何旧版本Token的请求被拒绝。

目标邮箱仍可能被并发占用；发码时未预占。本接口以数据库唯一约束和原始账户快照条件更新保护。首次绑定与更换邮箱使用相同撤销策略。

## 次数与申请

每用户首个获准提交起900秒内最多10次，跨Token/设备/角色，与发码和再次验证额度独立。普通认证、近期资格、请求格式通过后计数；成功、错码、申请无效、冲突及获准后的故障均不退额。429不新增计数或续窗，Retry-After为剩余窗口向上取整秒、至少1。重发、重新登录或再次验证不重置。

同一ACTIVE累计第五次错码失效，正确码一次性消费。不返回剩余次数。只有当前本人及challenge完整归属匹配才计错，旧申请的迟到请求不会计错或删除新申请。

新申请先激活，旧码不能再消费；旧码先消费，已获准修改可继续条件更新，提交后的认证版本变化会使同期旧版本申请失效。期限仍为发码获准起300秒，再次验证和确认都不续期。

## 失败及重试

Redis明确消费成功后才进入SQL，不存在跨Redis/MySQL的原子事务：

- 消费前业务拒绝不主动删申请；错码仍按五次规则累计。
- 消费后数据库明确回滚、邮箱冲突或其他失败，验证码不恢复，需要重新申请并遵守发送限流。
- Redis消费已执行但回包丢失：503，本次不进入SQL，码可能已消费。
- 数据库提交结果未知：503，邮箱和版本可能已更新；不自动重试SQL，不恢复验证码。
- 204响应丢失：更新仍可能已经成功，旧Token失效；没有成功重放或申请查询恢复接口。

503/响应丢失后核实本人当前邮箱；若旧Token被撤销，恢复有效登录后核实，可用未改变的用户名登录。旧Token被拒绝也可能由改密/角色变化引起，不能单独作为改邮箱成功证明。相同凭据重复提交不会再次改邮箱或再加版本，但响应可能是401或400。

## 错误

业务错误使用application/problem+json，稳定code，type为urn:problem:music:加code小写、保留下划线，例如urn:problem:music:email_change_verification_failed。普通JWT签名/过期拒绝沿用资源服务器现状，可能无ProblemDetail。

| 状态 | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 正文、标识、码格式、编码或大小不合法 |
| 400 | EMAIL_CHANGE_VERIFICATION_FAILED | 错码、无申请、过期、已消费、替换、外用户challenge或旧认证版本申请 |
| 401 | REAUTHENTICATION_REQUIRED | 缺少近期身份资格 |
| 401 | UNAUTHORIZED / INVALID_TOKEN / TOKEN_REVOKED | 普通认证/可信上下文/账户授权失效 |
| 403 | FORBIDDEN | 现有安全链拒绝；本接口无额外角色403业务 |
| 409 | EMAIL_ALREADY_EXISTS | 目标被其他账户占用，含邮箱唯一约束冲突 |
| 409 | EMAIL_CHANGE_CONFLICT | 原邮箱等快照变化，或目标等同本人当前邮箱 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | EMAIL_CHANGE_CONFIRMATION_RATE_LIMITED | 独立提交额度超限，附Retry-After |
| 503 | EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE | 本接口存储故障、损坏状态、版本不能递增、消费/提交结果未知 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 前置认证状态基础设施故障 |
| 500 | INTERNAL_ERROR | 意外内部错误，响应脱敏 |

```json
{"type":"urn:problem:music:email_change_verification_failed","title":"邮箱变更确认未完成","status":400,"detail":"验证码无效或申请已失效","instance":"/api/v1/users/me/email","code":"EMAIL_CHANGE_VERIFICATION_FAILED"}
```

## curl

将ACCESS_TOKEN及标识、验证码替换为本人实际有效凭据，示例不包含真实秘密：

```bash
curl -i -X PUT 'http://localhost:8080/api/v1/users/me/email' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"challengeId":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","code":"012345"}'
```
