# 申请找回密码验证码

- Method：`POST`
- Path：`/api/v1/users/password-reset-requests`
- 权限：公开，无需Token、旧密码或auth_time；USER/ADMIN相同。
- Content-Type：UTF-8 `application/json`。

## 请求

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| email | string | 唯一必填字段；trim后Locale.ROOT小写；非空、合法单邮箱、最多120字符 |

拒绝未知/重复字段（含转义重复）、缺字段、非字符串、嵌套、多根、非法UTF-8/孤立代理及CR/LF。正文最大16384字节（16 KiB），包括尾随空白；不信任Content-Length。仅接受application/json，charset省略或UTF-8；其他媒体类型/charset及缺Content-Type返回415。

```json
{"email":"alice@example.com"}
```

按现有邮箱唯一索引等值定位，再核对规范化后的文本精确相等。大小写可匹配，重音等价不能代替相同地址。NO PAD排序规则下，数据库中首尾带空格的历史邮箱不命中，按无账户受理但不发信；不扫描或修改历史数据。未绑定邮箱的账户暂不支持自助找回。

## 受理成功

**202 Accepted**，`Cache-Control: no-store`，无Location/Token/账户ID/是否发送标记：

```json
{
  "challengeId":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "expiresAt":"2026-11-05T12:05:00Z"
}
```

32字节安全随机标识，43字符规范Base64URL无填充。expiresAt为Redis准入时间加300秒，UTC ISO-8601；排队、发送和激活均不续期。账户不存在时返回同格式结果并扣取同样额度、占用同样任务容量，不实际发信。

202仅表示任务已入队，不保证账户存在或邮件发送。后台每任务最多一次发送；用户保留本次challengeId配合邮箱内六位验证码，后续确认端点另行交付，本接口不修改密码/版本、撤销登录或自动登录。

同邮箱只保留最新待处理申请，迟到旧任务不能激活。已激活旧码在新码明确激活前保留原期限。发送失败、超时或后台退出不重试；可能收到无法使用的迟到邮件。激活结果未知时新码可能已生效，不保证旧码仍可用，不恢复旧码。申请后改密/换邮箱/认证版本变化使原绑定失效；普通登录/再次验证不改版本。

## 限流与失败

同规范化邮箱60秒1次、每UTC日10次；同TCP remoteAddr IP固定60秒5次，忽略不可信转发头。与其他验证码及改密额度隔离。拒绝不增加计数或续窗；429 Retry-After为全部受阻维度最大等待，向上取整至少1秒。不返回scope。

受理扣额后数据库故障、队列满、发送失败、无账户、任务过期均不退款。全局最多1000未结束任务（默认），处理中失格任务仍占槽至结束/过期；满载503不驱逐其他任务。客户端遇到503或回包丢失时任务可能已入队，不假定失败一定没副作用，不自动无条件重试；冷却后可重新申请。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 输入、结构、编码、大小非法 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | PASSWORD_RESET_REQUEST_RATE_LIMITED | 邮箱/IP配额耗尽，带Retry-After |
| 503 | PASSWORD_RESET_REQUEST_UNAVAILABLE | DB/Redis故障、损坏、队列满、入队期限已过/结果未知 |
| 500 | INTERNAL_ERROR | 未预期异常 |
| 401/403 | 沿用现有认证授权错误码 | 主动附带无效Bearer可能401 INVALID_TOKEN/TOKEN_REVOKED，现有拒绝处理403 FORBIDDEN；正常不带Token无需登录，无新增角色禁止分支 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 主动附带Token时认证状态查询失败 |

业务错误为application/problem+json，稳定code及`urn:problem:music:`加小写下划线code，不泄漏邮箱/申请标识/验证码/底层异常；禁止缓存。签名错误/过期沿用资源服务器响应与WWW-Authenticate，可能无ProblemDetail。进入MVC后响应X-Trace-Id关联后台受理traceId，认证链提前拒绝不保证该头。

```json
{"type":"urn:problem:music:password_reset_request_rate_limited","title":"找回密码申请过于频繁","status":429,"detail":"找回密码申请过于频繁","code":"PASSWORD_RESET_REQUEST_RATE_LIMITED"}
```

## curl

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-reset-requests' \
  --header 'Content-Type: application/json' \
  --data '{"email":"alice@example.com"}'
```

示例邮箱为虚构地址，生产使用HTTPS。不要在日志中记录请求体或完整challengeId。部署与故障恢复见[部署说明](../../deployment/password-reset-requests.md)。
