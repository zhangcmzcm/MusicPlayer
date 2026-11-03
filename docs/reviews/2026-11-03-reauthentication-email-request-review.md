# 本人再次验证身份邮件请求：首次实现送审

日期：2026-11-03。实现审核第1/3轮已获Claude独立只读审核accepted，审核基线main@6fc1ddf，零阻断、零非阻断。用户已授权推送；以下保留首次送审证据，最终审核闭环见文末。本次不推进下一个接口。

## 本次接口

`POST /api/v1/users/me/reauthentication-email-requests`

本任务只实现当前邮箱的再次验证邮件发送，不实现验证码兑换、新邮箱变更请求、实际改邮箱、refresh或找回密码。

## 需求与权限

普通有效Bearer JWT，USER/ADMIN均可操作本人；不要求300秒内auth_time，兼容缺auth_time的旧Token。ID/登录角色来自AuthenticatedUserPrincipal，版本来自可信AuthenticatedSessionContext，当前邮箱由DB主键快照确定。零字节正文才合法，不接受前端目标邮箱或用户ID；任意自定义头、参数不能覆盖身份及连接IP。

## 声称实现的行为

- Controller只读取首字节校验无正文，未知Content-Length/分块同样不能绕过；Service核对账户存在、版本、角色，再规范化当前邮箱，未绑定409发生在准入前。
- Redis私有前缀music:auth:reauth-email:。同一reserve Lua读取TIME，检查并原子消费用户60秒、UTC日10次、连接IP固定60秒5次三个桶，并预留PREPARING。拒绝不写入或延长窗口，所有阻塞桶取最长Retry-After，失败不退已准入额度。
- 32随机字节challengeId，Redis只存SHA256/Base64URL摘要；复用安全6位码生成器的随机源，私有适配器输出ASCII数字，BCrypt完成后按归属转PREPARED。公开用途仍只有REGISTER、LOGIN，未复用其完整发送/消费/额度流程。
- 300秒有效期、60秒租约均锚定准入时间，PEXPIREAT/PEXPIRETIME校验实际绝对到期；PREPARED/邮件发送/激活不续命。schema、TYPE/HLEN、规范整数/Long.MAX_VALUE版本、摘要/BCrypt形态及回包均校验，损坏或未知确认503，不自动修复或重试。
- 专用隔离SMTP客户端复制既有JavaMailSenderImpl及Session配置，TLS/auth保持；smtp/smtps连接/读/写超时各5000ms，禁用mail.debug，不修改共享发送器。执行器4个实际线程、零队列、20秒等待，满载503、超时502；取消后实际未退出任务继续占槽，无扩容补偿。
- 邮件线程只有传输SPI，无DB/Redis/激活能力。发送确认后请求线程重新读取DB，账户/版本/角色失效401、邮箱变化或解绑409；最后Lua归属CAS激活，成功201/no-store严格两个字段challengeId/expiresAt，无Location。用户表整行不变、不签Token、不修改auth_time/tokenVersion。
- 新发送中旧ACTIVE保留原字段和绝对到期；新请求成功激活才替换。任何未确认激活的失败（包括reserve未知回包）尽力按本次归属cancel，只删本人本次pending，不退额度、不恢复active。清理错误不覆盖主错误，迟到旧结果不能覆盖或删新请求。
- 六个专用异常经GlobalExceptionHandler映射400/409/429/502/503；401复用既有InvalidToken/CurrentUserRevoked。500及清理/存储/邮件诊断保留通用消息和调用位置，剥离原消息/cause/suppressed；邮件、绑定、预留、响应的字符串表示隐藏敏感值。

## 变更文件（含送审前untracked）

相对代码基线22af54e共38文件：19个生产Java、5个Lua、8个新增测试、6个文档。既有运行时代码只修改GlobalExceptionHandler，新增49行映射；UserRepository、SecurityConfig、JWT/过滤链、公开验证码、共享SMTP、PasswordHasher、生成器均未改。相对实施基线324b198的原设计/计划/设计反馈变更仅为实施状态及意见处置记录。

```text
docs/api/user/request-reauthentication-email.md
docs/deployment/reauthentication-email-requests.md
docs/reviews/2026-11-03-reauthentication-email-request-design-review.md
docs/reviews/2026-11-03-reauthentication-email-request-review.md
docs/superpowers/plans/2026-11-03-reauthentication-email-request-plan.md
docs/superpowers/specs/2026-11-03-reauthentication-email-request-design.md
src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
src/main/java/org/example/music/user/controller/ReauthenticationEmailRequestController.java
src/main/java/org/example/music/user/domain/ReauthenticationEmailBinding.java
src/main/java/org/example/music/user/domain/ReauthenticationEmailReservation.java
src/main/java/org/example/music/user/dto/ReauthenticationEmailRequestResponse.java
src/main/java/org/example/music/user/email/BoundedReauthenticationEmailDelivery.java
src/main/java/org/example/music/user/email/IsolatedReauthenticationMailClientFactory.java
src/main/java/org/example/music/user/email/ReauthenticationEmailMessage.java
src/main/java/org/example/music/user/email/ReauthenticationEmailSender.java
src/main/java/org/example/music/user/email/SmtpReauthenticationEmailSender.java
src/main/java/org/example/music/user/exception/CurrentEmailNotBoundException.java
src/main/java/org/example/music/user/exception/ReauthenticationEmailDiagnostics.java
src/main/java/org/example/music/user/exception/ReauthenticationEmailInvalidRequestException.java
src/main/java/org/example/music/user/exception/ReauthenticationEmailRateLimitedException.java
src/main/java/org/example/music/user/exception/ReauthenticationEmailRequestConflictException.java
src/main/java/org/example/music/user/exception/ReauthenticationEmailRequestUnavailableException.java
src/main/java/org/example/music/user/exception/ReauthenticationEmailSendFailedException.java
src/main/java/org/example/music/user/repository/ReauthenticationEmailRequestStore.java
src/main/java/org/example/music/user/service/ReauthenticationEmailRequestService.java
src/main/resources/redis/reauthentication-email/activate.lua
src/main/resources/redis/reauthentication-email/cancel.lua
src/main/resources/redis/reauthentication-email/common.lua
src/main/resources/redis/reauthentication-email/prepare.lua
src/main/resources/redis/reauthentication-email/reserve.lua
src/test/java/org/example/music/user/ReauthenticationEmailRequestIntegrationTest.java
src/test/java/org/example/music/user/ReauthenticationEmailRequestStoreIntegrationTest.java
src/test/java/org/example/music/user/controller/ReauthenticationEmailRequestControllerTest.java
src/test/java/org/example/music/user/email/BoundedReauthenticationEmailDeliveryTest.java
src/test/java/org/example/music/user/email/IsolatedReauthenticationMailClientFactoryTest.java
src/test/java/org/example/music/user/email/SmtpReauthenticationEmailSenderTest.java
src/test/java/org/example/music/user/repository/ReauthenticationEmailRequestStoreTest.java
src/test/java/org/example/music/user/service/ReauthenticationEmailRequestServiceTest.java
```

初始工作区干净，没有排除用户既有改动。全部上述新增文件纳入提交，最终无untracked。

## 数据库、安全配置与文档

数据库迁移：无，V1—V8不动。依赖、配置、Compose、Actuator及安全开放路径：无变化，沿用anyRequest().authenticated()。

API：[request-reauthentication-email.md](../api/user/request-reauthentication-email.md)。部署：[reauthentication-email-requests.md](../deployment/reauthentication-email-requests.md)。设计和计划见文件清单。

## 实际验证

2026-11-03，PowerShell实际命令：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
cmd /c gradlew.bat cleanTest check --console=plain
```

结果：BUILD SUCCESSFUL，exit0，9分47秒。新XML生成时间为2026-11-03 12:53:11（UTC+08:00），96类、1105通过/0失败/0错误/0跳过；必要MySQL8.4、Redis8.10.2、MinIO容器均实际执行，没有把跳过当通过。跳过项：无。失败摘要：无。相对代码基线999项新增106项。

| 本任务测试类 | 通过 | 失败/错误/跳过 |
| --- | --- | --- |
| ReauthenticationEmailRequestStoreTest | 13 | 0/0/0 |
| ReauthenticationEmailRequestStoreIntegrationTest | 24 | 0/0/0 |
| IsolatedReauthenticationMailClientFactoryTest | 2 | 0/0/0 |
| SmtpReauthenticationEmailSenderTest | 2 | 0/0/0 |
| BoundedReauthenticationEmailDeliveryTest | 5 | 0/0/0 |
| ReauthenticationEmailRequestServiceTest | 16 | 0/0/0 |
| ReauthenticationEmailRequestControllerTest | 12 | 0/0/0 |
| ReauthenticationEmailRequestIntegrationTest | 32 | 0/0/0 |
| 合计 | 106 | 0/0/0 |

另已实际运行仓储定向、邮件/Service定向、Controller/真实链路定向，以及本任务全部测试配EmailSendCodeIntegrationTest、AuthTimeBindingIntegrationTest、PasswordChangeIntegrationTest的相关回归（BUILD SUCCESSFUL，2分6秒）；最终计数统一以上述重新cleanTest后的完整XML为准。

覆盖重点：真实签名JWT/实际过滤链（含旧Token、过期、签名、subject/版本/角色/issuer/audience失败）、多角色、主体/头/参数防覆盖、整行无写入、哈希匹配与敏感值不回传/不存明文、真实Redis并发及损坏/TTL/UTC界限、同IP不同用户额度、同用户并发恰一次准入、发送后真实DB变更、旧active保留/替换、真实执行后故意丢确认、清理失败不掩盖主错误、迟到旧结果隔离、物理4槽不可中断任务超时后仍满载，以及真实MIME/配置隔离和安全日志。

时间测试使用Redis真实过期命令和同一纯Lua UTC日界helper，没有生产时间覆盖参数，也没有以Java Clock推进模拟Redis TIME；并发用限时闩锁/future并在finally释放，无Thread.sleep。SMTP使用受控边界和真实MimeMessage单测，不声称已验证真实外部收件箱送达。

实施顺序说明：仓储、工厂、Service、Controller首次运行出现缺少新类的预期编译失败，之后补实现转绿；这不是业务断言先红的证明。真实Redis、MIME及物理线程测试同阶段补全，未严格执行计划中“所有测试都先于实现”的顺序，但最终全部验证要求已落实。首次发送器测试另有一处测试lambda括号编译错误、HTTP测试有重复thenThrow设桩错误，均已修正；最终全量结果不复用这些失败运行。

构建关闭阶段有缓存连接池对已关闭MySQL连接的告警，出现于Spring shutdown hook，构建与XML无失败；本任务没有扩大范围调整共享测试生命周期。

## Git基线与实现提交

- 代码基线：main @ 22af54e（本地origin/main跟踪点）；设计bad68f3、设计回执/计划324b198。
- b4f9113：Redis原子准入、状态、异常与仓储测试。
- d06767e：隔离SMTP与有界发送、相关测试。
- 5cb1bfb：绑定核对、发送后激活协调及响应DTO/Service测试。
- 3e36944：无正文HTTP契约、统一异常映射及Controller测试。
- 3d1f6d0：真实链路/并发/故障测试补齐；仓储同时补反序列化/错类型故障503翻译及Object[]参数消除varargs歧义。
- 审核材料为本文件所在独立文档提交；全量验证后只补文档与状态，不再改运行时代码或测试。不自动push。

## curl示例

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/users/me/reauthentication-email-requests" -H "Authorization: Bearer $env:ACCESS_TOKEN"
```

无正文。POSIX示例及所有状态码/响应字段见API文档，不含真实Token/验证码/密码。

## 审核反馈复核与处置

首次实现审核：不涉及旧实现反馈、无自行关闭的阻断项。沿用设计意见编号与原分级：

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断，代理IP共享桶 | 成立，ClientIpResolver沿用remoteAddr；无real-IP的代理会令不同用户共桶 | 认可，非阻断，不改变原分级 | 认可部署验收，不在本接口信任任意XFF或改全局代理配置 | 部署文档明确全站5次/60秒瓶颈，要求两个用户经直连/正式代理的实际地址与桶行为留档 | 真Redis共IP五次限制、真JWT链伪造头无效均通过；实际生产代理烟测仍由部署方执行，待Claude复核代码侧处置 |
| NOTE-002 非阻断，准备时间歧义 | 成立，PREPARED是哈希完成后阶段，不能替代reserve TIME | 认可措辞问题，非阻断 | 认可统一预留/准入时间及绝对到期测试 | 设计/计划/API/部署明确300000/60000ms均从reserve TIME起算；prepare/发送/激活不续命 | 真Redis和完整链路验证绝对到期与原记录一致、旧请求迟到不激活，106项任务测试和全量1105项均通过，待Claude复核 |

## 未决问题与边界

无待人类决定的业务默认项或设计冲突。全实例认证加固、Redis版本/ACL、真实SMTP、代理桶与时钟验收仍按部署文档独立落实；本次代码测试不等于环境验收。

MySQL复核与Redis激活非原子，未来兑换仍需再次核对绑定。发送超时不等于未发送，激活确认丢失可能已生效；不承诺503时旧active必定保留，不自动补偿额度或回滚新active。仅发送端可用，未实现的统一再次验证和邮箱变更端点不能对外宣告已交付。

## 独立审核结果与Codex复核

用户提供第1/3轮实现审核：accepted，零阻断、零非阻断、无剩余争议。审核者独立执行MINIO_INTEGRATION_TESTS=true的cleanTest check --console=plain，1105通过/0失败/0错误/0跳过、96类，8个本任务类合计106项，与上述自验及申报范围一致。

按AGENTS.md §3.1复核：报告没有新的阻断或非阻断问题，无需修改代码；核对38文件范围及未动迁移/配置/依赖/安全链的事实，认可结论。设计NOTE-001已由Claude关闭代码侧处置，正式环境直连/代理烟测继续独立跟踪；NOTE-002已由Claude关闭。原处置表的“待Claude复核”由本结论覆盖，不改动原编号及非阻断分级。

本次闭环提交仅更新设计、计划和审核文档，没有运行时代码或测试变化；无需为状态登记重复运行全量测试。用户另行授权处理的本机Redis端口/认证修改仅在被Git忽略的.env及本机容器生效，不纳入推送，也不代表生产Redis、SMTP、代理或认证加固部署条件全部验收完成。用户已授权推送已审核提交及本次闭环记录。
