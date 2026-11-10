# 找回密码独立验证部署说明

## 运行与阶段

本次交付POST /api/v1/users/password-reset-verifications，用户表只读。复用MySQL8.4、单Redis7以上、BCrypt及既有安全链，Lua使用TIME/PEXPIRETIME，原有发码后台继续管理队列。无新数据库迁移、依赖、生产配置项、Compose服务或后台任务。

第三步POST /api/v1/users/password-resets切换为resetTicket/newPassword，兼容已签发且尚有效的schema 1票据。前端在新版部署与第三步独立实现审核通过后整体切换，部署协调所有实例版本。旧三字段请求返回400 VALIDATION_FAILED。

保持可信Redis/系统/MySQL时钟、Redis持久化及故障转移安全前提。Redis回滚已消费状态会损害一次性保证，全部应用实例必须使用统一认证版本校验。生产HTTPS，凭据及请求体排除在日志和公开指标外。

## 三方定位表与独立预算

| 接口 | Redis额度键前缀 | 限流/不可用错误族 |
| --- | --- | --- |
| POST /api/v1/users/password-reset-requests | music:auth:password-reset:（email-cooldown/email-daily/ip） | PASSWORD_RESET_REQUEST_* |
| POST /api/v1/users/password-reset-verifications | music:auth:password-reset-confirmation:（ip/user） | PASSWORD_RESET_VERIFICATION_* |
| POST /api/v1/users/password-resets，resetTicket/newPassword | music:auth:password-reset-completion:（ip/user） | PASSWORD_RESET_CONFIRMATION_* |

第二步原confirmation窗口原样保留。第三步completion从本步首次准入建立独立窗口，两步各自IP60秒10次、账户900秒10次。各自保留count/resetAtMs及绝对到期时间，拒绝不延长。旧三字段在严格解析阶段400，准入前拒绝。以本表定位内部状态，HTTP码族与键名分别核对。

错误族指RATE_LIMITED/UNAVAILABLE。仅IP额度429及Retry-After，账户额度统一400 PASSWORD_RESET_VERIFICATION_FAILED，隐藏拒绝层和等待字段。第二步将复用组件CONFIRMATION不可用转换为VERIFICATION；第三步将复用票据VERIFICATION不可用转换为CONFIRMATION，各接口边界维持对应码族。

## 凭据状态

重置票键为music:auth:password-reset-ticket:active:<userId>的STRING指针，及ticket:<digest>的10字段HASH。字段固定schema/digest/userId/email/emailDigest/rawEmail/role/tokenVersion/issuedAt/expiresAt，schema=1。两键PEXPIRETIME相同，等于Redis签发时间加300000毫秒。版本保留Long十进制字符串，比较长度/字典序，排除Lua浮点精度损失。

只有完整绑定与正确验证码才允许原子签发。每账户最新一张，新申请/发送邮件本身不影响票；新票成功替换时旧票失效。更高版本活动票受到迟到低版本保护。

能定位的半状态、额外/缺少字段、非法值及TTL矛盾均503且保留现场。无活动指针时按无当前票处理，无法按用户ID枚举随机摘要孤立记录，孤立记录不具当前指针资格，按原TTL保留。已有旧快照时能检查指定双键，一键缺失503、双键同时过期或合法消费可继续兑换。禁止增加扫描、清额或删坏键放行流程。

## 故障与账户降级

原子兑换前故障可保留旧码及旧票；实际兑换后丢回复可能已消费旧码并替换票。两者都可503，不能从503推断是否执行。未执行时旧码可能仍可成功，已执行后重复400。服务端不自动重试、不重放成功响应、不退款或恢复，客户端重新申请邮件验证码恢复。

持有真实challengeId者可消耗账户900秒窗口额度，合法用户可能在原窗口剩余时间内统一400。五次错码和账户十次提交为不同计数，跨重发累计，绑定变化后失败也不退。原TTL自然恢复，重发不清额。单窗上限900秒，反复攻击可能在后续窗口再次触发，受发码邮箱60秒冷却及每日10次限制，不能解读为永久不会复现。

DB最终复核和Redis兑换存在间隙，期间身份绑定变化产生的票保留旧版本，第三步再次DB复核并执行prepared CAS，不能升级旧身份。第二步成功本身不修改密码或版本，旧登录与角色票仍按原版本规则使用。

日志使用SLF4J/traceId和PasswordResetDiagnostics脱敏异常链，排除完整标识、验证码、票、摘要、内部版本及邮件正文。Actuator保持既有health、info、metrics。以服务、Redis和DB健康信息排查，避免向客户端回传内部账户拒绝原因。
