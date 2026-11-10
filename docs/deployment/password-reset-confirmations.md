# 三步密码重置提交部署与故障边界

## 运行前提

本轮改造唯一POST /api/v1/users/password-resets，接收resetTicket/newPassword。复用MySQL8.4、单Redis7以上及既有认证版本过滤器，Lua使用TIME和PEXPIRETIME。无新依赖、Flyway迁移、Compose服务、生产配置项或后台任务，保持ddl-auto=none。

沿用发码单实例队列部署前提。Redis持久化及故障转移须避免回放已消费票据或回滚认证状态，各实例统一执行认证版本校验。生产HTTPS，系统、Redis、MySQL保持可信时钟。Redis TIME授权额度与票据期限，MySQL UTC_TIMESTAMP(6)维护updated_at。

## 契约切换

现行流程依次调用以下接口。

| 步骤 | 接口 | 请求与成功 |
| --- | --- | --- |
| 1 | POST /api/v1/users/password-reset-requests | email，202返回challengeId/expiresAt |
| 2 | POST /api/v1/users/password-reset-verifications | challengeId/code，200返回resetTicket/expiresAt |
| 3 | POST /api/v1/users/password-resets | resetTicket/newPassword，204空正文 |

本轮采用直接切换。协调所有应用实例版本，避免同一路径新旧字段契约混用。第三步独立实现审核通过且新版本部署可用后，前端整体切换，票据在内存暂存。旧challengeId/code/newPassword请求400 VALIDATION_FAILED，格式拒绝发生在业务额度准入前。

第二步已签发且尚有效的schema 1票据兼容新版第三步。旧验证码仍可按原期限在第二步兑换。切换保留第二步在途额度，新第三步completion额度从首次准入建立自身窗口。

## 接口、额度键与错误码

| 接口 | Redis额度前缀 | 限流/不可用错误族 |
| --- | --- | --- |
| password-reset-requests | music:auth:password-reset:，发码各维度 | PASSWORD_RESET_REQUEST_* |
| password-reset-verifications | music:auth:password-reset-confirmation:，ip/user | PASSWORD_RESET_VERIFICATION_* |
| password-resets | music:auth:password-reset-completion:，ip/user | PASSWORD_RESET_CONFIRMATION_* |

第二、三步各自IP60秒10次、账户900秒10次。quota HASH恰count/resetAtMs，两键各依自身首次准入确定的PEXPIRETIME自动过期。重发、换设备、签新票或登录保留原账户额度，已准入操作后续失败继续计数。

仅IP拒绝429及Retry-After，按原窗口剩余毫秒向上取整秒数。账户额度拒绝统一400 PASSWORD_RESET_VERIFICATION_FAILED，隐藏账户、拒绝层、等待与次数。第三步在完整票据后按可信账户扣额，假票仅耗IP。有效票据持有者可能消耗账户窗口，合法用户在剩余窗口内也会统一400，重复消耗可能在后续窗口再发生，按原TTL恢复。

第二步保留验证码五错失效和原猜码窗口。第三步通过随机票据授权，密码比较在票据/额度/prepared绑定通过后执行。两步额度及用途分别维护。

## 票据状态与原子消费

沿用music:auth:password-reset-ticket:active:<userId> STRING和ticket:<digest> HASH，schema=1恰10字段。仅存SHA-256摘要及完整账户绑定，每账户最新一张票；expiresAt恰为Redis签发时间加300000毫秒，两键PEXPIRETIME相等，等于到期端点即失效。

按摘要读取和原子消费均核验完整字段、归属、类型、原始/规范化邮箱关联及期限。消费匹配已读完整不可变快照，仅删除本票及匹配指针。新票先签发时旧消费400保留新票；旧票先消费时已获资格的原SQL可继续，新签发但绑定旧版本的票随成功版本递增失效。

按摘要首次缺记录返回400，此时无法定位未知账户指针；现存记录缺指针可定位503。已知消费快照能定位双键半状态，返回503。自然到期或消费后的双键消失为400。损坏、无TTL、TTL矛盾或非法字段均503，保留现场，原TTL负责健康状态清理。

版本使用Long规范十进制字符串，经Java精确解析，Lua避免浮点转换。Long.MAX_VALUE票据可读取，提交在消费前503。大于2^53的版本仍精确绑定与递增。

## 数据库及失败边界

短只读事务读取prepared并核对ID、原始邮箱、角色、版本与密码摘要。事务结束后计算密码。相同密码400 NEW_PASSWORD_SAME_AS_OLD及摘要故障503保留原票期限，已扣额度保留。新密码8至72位可打印ASCII、非全空格、首尾空格原值保留。

只有明确Redis消费成功后进入短MySQL写事务。使用原prepared执行一次专用CAS，匹配ID、binary原始邮箱/角色/旧密码摘要及精确版本，只修改password_token、token_version加1及updated_at。0行400，1行成功，其他500回滚。用户名并发变更可保留。

| 故障 | 结果 |
| --- | --- |
| 准入操作实际执行后丢回复 | 503，已扣计数保留 |
| Redis消费未执行或执行后丢回复 | 503，本次零写事务和更新SQL，票可能已消费 |
| 明确消费后写事务开始/执行失败或真实回滚 | 503，票保持已消费，行按真实事务结果保留 |
| MySQL实际提交后确认丢失 | 503，可能已改密及撤销旧登录 |
| 期限内明确消费，数据库提交跨期限 | 可完成原prepared更新 |

服务端自动消费/SQL重试、恢复票、清额、退款及204重放均关闭。Lua运行时错误没有数据库式回滚承诺，未知回复按503关闭授权。503或重试400都不足以判断首次是否改密，客户端尝试新密码正常登录核实，必要时重新申请邮件验证码。

成功递增版本使目标全部旧JWT及旧角色选择票在后续认证中失效，前端清理旧登录并走原登录/角色选择流程。他账户有效Token按原规则使用。已通过认证的在途请求遵守既有执行边界。

## 观测与核实

SLF4J保留traceId，异常经PasswordResetDiagnostics脱敏。请求体、密码、验证码、票、摘要、内部版本、Redis键及邮件内容排除在日志与公开指标外，Actuator保持health、info、metrics。

按上表的接口/额度前缀/HTTP错误族定位问题。共享TicketStore沿用VERIFICATION不可用异常，由第三步服务边界安全转换为CONFIRMATION不可用，既有第二步调用保持原行为。

故障修复后按原窗口与期限恢复，消费后的用户通过重新申请建立新证明。排查使用已有服务、Redis及DB健康信息，内部账户限额原因保留在服务端边界。
