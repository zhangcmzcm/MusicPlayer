# 找回密码第三步契约切换实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式沿用项目协议，由Codex在本会话唯一实现，Claude独立只读审核。上述执行子技能未列为可用，执行时按本会话原生流程逐任务推进。用户已确认开始，任务1至6已完成，进入任务7完整验证与独立审核材料准备。

**Goal:** 将唯一POST /api/v1/users/password-resets直接切换为resetTicket/newPassword，完成三步找回密码闭环及目标账户全部旧登录失效。

**Architecture:** 严格两字段→completion独立额度→schema 1票据完整读取→prepared绑定复核→事务外密码准备→Redis完整预期原子消费→既有MySQL单次CAS。第二步签发、验证码计错及原额度窗口兼容保留。

**Tech Stack:** Java21、Spring Boot4.1.1、Jakarta Validation、StringRedisTemplate/Lua、JPA/MySQL8.4、既有BCrypt、JUnit5/MockMvc/Testcontainers、springdoc、Gradle Wrapper。无新依赖、数据库迁移或配置。

**Spec:** [第三步接口设计](../specs/2026-11-09-password-reset-completion-design.md)，646c1e6已获第1/3轮书面设计审核accepted，零阻断项。代码基线为main @ feb13c9，计划开始时HEAD=646c1e6。设计NOTE-001采纳两份第二步文档同步，见任务6。上轮严格Reader复用建议继续排期。

## Global Constraints

- 唯一接口POST /api/v1/users/password-resets。公开，USER/ADMIN同规则，目标仅由服务端票据绑定确定。他账户有效Bearer不改变目标，无效/过期/撤销Bearer沿用原安全链。
- 请求恰resetTicket/newPassword，票为43字符规范Base64URL无填充且解码32字节、重编码相同。密码8至72位可打印ASCII U+0020至U+007E、非全空格、原值保留。
- application/json、charset省略或UTF-8、严格UTF-8、正文16384字节含尾随空白、多读1字节。旧三字段及未知/重复/转义同名/null/嵌套/多根等400，媒体类型415。
- 成功204空正文及no-store，无新Token。Controller解析前设置缓存头，既有认证链拒绝沿用现状。敏感DTO/快照toString及异常链脱敏，日志保留traceId。
- schema 1票据恰10字段，独立300000毫秒、Redis TIME与两键PEXPIRETIME一致、now等于expiresAt即过期；同时核验摘要记录及当前账户指针。版本0至Long.MAX_VALUE用规范字符串，禁止Lua浮点化。
- completion:ip:<digest>/user:<id>分别固定60秒10次、900秒10次，前缀music:auth:password-reset-completion:。第二步仍使用confirmation前缀，各自独立。只IP429与Retry-After，账户统一400，无退款、清额或续窗。
- 顺序为IP→完整票据→账户额度→只读prepared→事务外新旧密码比较与摘要→明确消费→原prepared单次CAS。缺票/账户拒绝等普通失败直接400，第三步不依赖CodeVerifier。
- 复用原数据库专用resetPasswordIfSnapshotMatches，匹配ID、binary原始邮箱/角色/旧摘要、精确版本及上限，只改password_token/token_version/updated_at。0行400，1行成功，其他500回滚。
- 同密码、格式失败及消费前摘要/上限故障保留票原期限，已准入额度保留。消费未知503且零写事务；明确消费后DB故障不恢复票。提交未知可能生效，新密码正常登录核实。
- 保留第二步真/伪验证码比较矩阵、原12字段生产者与schema 1签发。第三步边界将票据组件VERIFICATION不可用转换为CONFIRMATION不可用。普通400继续PASSWORD_RESET_VERIFICATION_FAILED及既有公共title/detail。
- 中文文档/提交，不改已执行迁移、不加依赖、不提取共享严格Reader。本轮仅最小必要死代码移除及阶段性测试/文档迁移。
- 完整MINIO_INTEGRATION_TESTS=true cleanTest check，必要MySQL/Redis/MinIO测试零跳过。设计审核独立168类1926通过/0失败/0错误/0跳过为基线复核，未验证新实现。

## Review Focus

1. 首次按摘要读缺记录无法定位孤立指针为400，已知消费快照可定位半状态则503，任务1/2分别钉住。
2. 大于2^53版本仍精确匹配，Long.MAX_VALUE票可读但改密消费前503，任务1/2/4/5。
3. 消费未知即使实际已删票也零写事务，MySQL实际提交后丢确认可能已改密，任务4/5。
4. 第二步原窗口不清零，第三步独立窗口；旧三字段400不能触发任一步额度，任务3/4/6。
5. 旧票消费后新票签发不会中止原CAS，新票旧版本随成功递增失效，任务2/5。

## 文件地图与衔接协议

下表均为仓库根相对路径。行内文件以对应目录为前缀，计划仅修改列明范围。

| 目录 | 新增 |
| --- | --- |
| src/main/java/org/example/music/user/repository | PasswordResetCompletionQuotaStore.java |
| src/main/resources/redis/password-reset-verification | read-by-digest.lua、consume.lua |
| src/main/resources/redis/password-reset-completion | admit-ip.lua、admit-account.lua |
| src/test/java/org/example/music/user | PasswordResetTicketLookupIntegrationTest.java、PasswordResetTicketConsumeIntegrationTest.java、PasswordResetCompletionQuotaIntegrationTest.java |
| src/test/java/org/example/music/user/repository | PasswordResetCompletionQuotaStoreTest.java |
| docs/reviews | 2026-11-10-password-reset-completion-review.md |

| 目录 | 修改 |
| --- | --- |
| src/main/java/org/example/music/user/controller | PasswordResetConfirmationController.java、PasswordResetConfirmationRequestReader.java |
| src/main/java/org/example/music/user/dto | PasswordResetConfirmationRequest.java |
| src/main/java/org/example/music/user/repository | PasswordResetTicketStore.java、PasswordResetConfirmationStore.java |
| src/main/java/org/example/music/user/service | PasswordResetConfirmationSnapshotReader.java、PasswordResetConfirmationService.java |
| src/test/java/org/example/music/user/controller | PasswordResetConfirmationControllerTest.java、PasswordResetConfirmationRequestReaderTest.java |
| src/test/java/org/example/music/user/service | PasswordResetConfirmationSnapshotReaderTest.java、PasswordResetConfirmationServiceTest.java、PasswordResetVerificationServiceTest.java |
| src/test/java/org/example/music/user/repository | PasswordResetTicketStoreTest.java |
| src/test/java/org/example/music/user | PasswordResetConfirmationIntegrationSupport.java、PasswordResetConfirmationIntegrationTest.java、PasswordResetConfirmationConcurrencyIntegrationTest.java、PasswordResetConfirmationFailureIntegrationTest.java、PasswordResetConfirmationStoreIntegrationTest.java、PasswordResetVerificationIntegrationSupport.java、PasswordResetVerificationIntegrationTest.java |
| docs/api/user | confirm-password-reset.md、verify-password-reset.md、request-password-reset.md |
| docs/deployment | password-reset-confirmations.md、password-reset-verifications.md、password-reset-requests.md |
| docs/superpowers/specs | 2026-11-09-password-reset-completion-design.md，仅登记审核与本轮处置 |
| docs/superpowers/plans | 本文件，勾选进度与实际验证 |

删除src/main/resources/redis/password-reset-confirmation/consume.lua及原Store仅为该脚本服务的CONSUME字段、consume(challenge)方法，时间点在任务4完成全部调用迁移后。保留旧mutation原语供recordFailure使用。共享旧common.lua、原read-current/exchange脚本、数据库SQL/迁移、SecurityConfig及全局异常映射预计无需修改。若实际发现必须调整，应先核实原因并补齐范围记录。

既有票据接口readCurrent(Integer)、exchange(ChallengeSnapshot,Optional<TicketSnapshot>,String)及规则不改契约。新内部接口如下。

~~~java
// PasswordResetTicketStore，异常沿用PasswordResetVerificationUnavailableException
Optional<PasswordResetTicketSnapshot> readByDigest(String digest);
boolean consume(PasswordResetTicketSnapshot expected);

// PasswordResetCompletionQuotaStore，异常使用现有Confirmation异常
void admitIp(String digest);
boolean admitAccount(Integer userId);

// PasswordResetConfirmationSnapshotReader
Optional<PasswordResetPreparedSnapshot> prepareTicket(PasswordResetTicketSnapshot ticket);
// 保留 isCurrent(PasswordResetChallengeSnapshot)，旧 prepare(challenge)在任务4移除

// PasswordResetConfirmationService，reset签名保持
void reset(PasswordResetConfirmationRequest request, String clientIp);
// DTO在任务4切换
record PasswordResetConfirmationRequest(String resetTicket, String newPassword) {}
~~~

新票据脚本位于原verification目录，沿用TicketStore现有脚本装载与common组合。readByDigest和readCurrent共用严格Java回复解析，分别校验预期digest/userId，避免通过改旧读取返回契约影响第二步。

## 任务1　兼容schema 1的按摘要读取

**Files:** 修改PasswordResetTicketStore.java、PasswordResetTicketStoreTest.java，新增read-by-digest.lua、PasswordResetTicketLookupIntegrationTest.java。

**Interfaces:** 消费现有TicketRules.validate(TicketSnapshot)、ticket_record/ticket_current及10字段票据；提供readByDigest(String)，返回完整快照或空，损坏/未知抛现有VerificationUnavailable。

- [x] **1.1 写失败测试。** should_read_producer_ticket_by_digest_without_changing_ttl断言readByDigest(ticket.digest()).orElseThrow().equals(ticket)、两键PEXPIRETIME不变；should_distinguish_unknown_digest_from_locatable_damage断言随机缺记录为空、记录有而指针无抛503；参数化类型/字段/邮箱摘要/非规范版本/原始邮箱/TTL损坏、合法新指针导致旧票失效，以及2^53+1与Long.MAX_VALUE精确读取。Mockito验证null、错误数量/类型/状态、摘要不匹配、userId溢出与未知回复均关闭。

~~~java
assertEquals(ticket, tickets.readByDigest(ticket.digest()).orElseThrow());
assertTrue(tickets.readByDigest(PasswordResetIdentifiers.digest(PasswordResetIdentifiers.generate())).isEmpty());
redis.delete(PasswordResetTicketStore.PREFIX + "active:" + ticket.account().userId());
assertThrows(PasswordResetVerificationUnavailableException.class, () -> tickets.readByDigest(ticket.digest()));
~~~

上述ticket由真实生产者ACTIVE及现有exchange生成，初始快照取readCurrent，expiry断言使用真实PEXPIRETIME。
- [x] **1.2 运行失败测试。** .\gradlew.bat test --tests "org.example.music.user.PasswordResetTicketLookupIntegrationTest" --tests "org.example.music.user.repository.PasswordResetTicketStoreTest" --console=plain，预期新增方法缺失或断言失败。
- [x] **1.3 实现读取。** read-by-digest.lua仅KEYS[1]=ticket:<digest>、ARGV[1]=digest，调用现有原语读取，合法时回复[0,按ticket_names顺序10字符串]，缺失或合法旧票[1]，损坏[2]。Java严格解析，并执行TicketRules语义校验。首次缺记录直接空，不扫描未知账户指针；现存记录缺指针503。
- [x] **1.4 验证读取回归。** 重跑1.2并加 --tests "org.example.music.user.PasswordResetTicketReadIntegrationTest" --tests "org.example.music.user.PasswordResetTicketExchangeIntegrationTest"，要求实际通过且0跳过，确认readCurrent/exchange协议兼容。
- [x] **1.5 提交。** 仅本任务文件，中文提交“feat: 增加重置票据按摘要读取”。

## 任务2　完整预期快照原子消费

**Files:** 修改PasswordResetTicketStore.java、PasswordResetTicketStoreTest.java，新增consume.lua、PasswordResetTicketConsumeIntegrationTest.java。

**Interfaces:** 消费任务1 readByDigest、现有TicketSnapshot及Rules；提供boolean consume(TicketSnapshot)，明确成功true，已知失效false，损坏/未知抛VerificationUnavailable。

- [x] **2.1 写失败测试。** should_consume_both_keys_once_across_instances断言两独立Store并发返回值恰一true/一false，成功后双键消失；should_preserve_new_ticket_when_old_consume_loses断言签新票先发生时false且新票两键不变；should_reject_same_digest_mutation_and_known_half_state断言完整快照任一字段变动或半状态503且原状态保留。覆盖双键到期/已消费false、超过2^53版本、其他用户隔离、自然到期、读取后新票签发。

~~~java
assertTrue(tickets.consume(ticket));
assertFalse(tickets.consume(ticket));
assertTrue(tickets.readByDigest(ticket.digest()).isEmpty());
assertTrue(tickets.readCurrent(ticket.account().userId()).isEmpty());
~~~
- [x] **2.2 运行失败测试。** .\gradlew.bat test --tests "org.example.music.user.PasswordResetTicketConsumeIntegrationTest" --tests "org.example.music.user.repository.PasswordResetTicketStoreTest" --console=plain，预期缺少消费方法或断言失败。
- [x] **2.3 实现消费。** KEYS恰ticket:<digest>、active:<userId>；ARGV恰10项，顺序为schema/digest/userId/email/emailDigest/rawEmail/role/tokenVersion/issuedAt/expiresAt。校验参数、完整不可变字段、Redis时间和双TTL及可定位当前归属后，一次DEL本票和匹配指针。回复仅[0]成功、[1]普通失效、[2]损坏；Java未知回复503，禁止自动重试。双键同时消失false，已知指针仍指本票而记录无、记录有指针无均503，合法新指针false且不删除。
- [x] **2.4 验证双向协议。** 重跑2.2并运行任务1及原Exchange集成测试，验证消费先发生后原签发快照允许按无旧票继续；新票先签发则旧消费失败。原样保留第二步较高版本票保护。
- [x] **2.5 提交。** 中文提交“feat: 增加重置票据完整绑定原子消费”。

## 任务3　独立额度及票据绑定prepared读取

**Files:** 新增PasswordResetCompletionQuotaStore.java、completion/admit-ip.lua、admit-account.lua、PasswordResetCompletionQuotaStoreTest.java、PasswordResetCompletionQuotaIntegrationTest.java，修改PasswordResetConfirmationSnapshotReader.java及其Test。

**Interfaces:** 提供admitIp(String)、admitAccount(Integer)、prepareTicket(TicketSnapshot)。复用Quota Lua原语、UserRepository.findPasswordResetPreparedSnapshot(Integer)、AccountSnapshot、ConfirmationRules.requirePasswordHash；保留第二步isCurrent(challenge)及当前旧prepare入口直至任务4。

- [x] **3.1 写失败测试。** should_keep_completion_budget_separate_from_verification_budget分别真实扣两前缀并断言计数和PEXPIRETIME互不改变，10次准入/第11次IP异常与账户false，RetryAfter为原窗口剩余秒；额度HASH损坏、TTL缺失/不符及未知回复均503。SnapshotReader新增票绑定测试断言rawEmail精确保留、大小写变化/角色/版本/删除为空，非法ID/角色/负版本/密码摘要与真实事务异常503。

~~~java
for (int attempt = 0; attempt < 10; attempt++) {
    quota.admitIp(ipDigest);
    assertTrue(quota.admitAccount(42));
}
assertThrows(PasswordResetConfirmationRateLimitedException.class, () -> quota.admitIp(ipDigest));
assertFalse(quota.admitAccount(42));
~~~
- [x] **3.2 运行失败测试。** .\gradlew.bat test --tests "org.example.music.user.PasswordResetCompletionQuotaIntegrationTest" --tests "org.example.music.user.repository.PasswordResetCompletionQuotaStoreTest" --tests "org.example.music.user.service.PasswordResetConfirmationSnapshotReaderTest" --console=plain，预期新组件/方法缺失。
- [x] **3.3 实现两个边界。** QuotaStore构造器仅StringRedisTemplate，PREFIX固定completion，装载现有confirmation/common.lua加各入口，入口严格检查KEYS/ARGV及ip/user键；quota窗口60000/900000，回复[status,waitSeconds]严格映射现有Confirmation异常。prepareTicket在现有只读TransactionTemplate内读取prepared，先验证实际账户字段可用，再按account.equals比对票绑定并验证密码摘要，返回原prepared，异常安全包装。
- [x] **3.4 验证兼容。** 重跑3.2，加 --tests "org.example.music.user.service.PasswordResetVerificationServiceTest"，保证第二步isCurrent与原窗口兼容；自然到期恢复账户额度，拒绝层不增/续窗。
- [x] **3.5 提交。** 中文提交“feat: 增加密码重置独立额度与票据绑定复核”。

## 任务4　原路径两字段切换与所有旧调用迁移

**Files:** 修改地图列明的Controller/Reader/DTO、ConfirmationService、SnapshotReader及旧ConfirmationStore，删除旧consume.lua；同步地图列明全部旧构造器/消费调用相关测试与两份IntegrationSupport。新建组件已在任务1至3交付。

**Interfaces:** ConfirmationService构造器依次注入CompletionQuotaStore、TicketStore、ConfirmationSnapshotReader、ConfirmationRules、PasswordHasher、ConfirmationTransactionService。reset(request,ip)保持void。DTO切换为两字段。测试夹具保留Ready申请/验证码表示，新增record Issued(String resetTicket,Instant expiresAt)并脱敏toString，Issued issue(Ready ready)通过真实第二步兑换，MvcResult confirm(Issued issued,String password)仅提交两字段。

- [x] **4.1 写契约失败测试并迁移编译调用。** should_accept_only_reset_ticket_and_password断言正确两字段204及原值传Service、旧三字段400且Service零交互；迁移原Reader边界表为新字段，8/72合法与7/73非法、首尾空格保留、16384/16385边界、所有严格JSON与415断言。旧测试的DTO构造和夹具调用在本次切换中一并改成显式issue→confirm，避免长期保留旧入口适配。
- [x] **4.2 写业务顺序失败测试并运行。** ServiceTest使用InOrder断言IP→readByDigest→account→prepareTicket→matches→hash→requirePasswordHash→consume→update；缺票/账户拒绝零密码计算，同密码/摘要故障/MAX零消费，consume false统一400、未知503零update、转换VerificationUnavailable为ConfirmationUnavailable、敏感异常脱敏。运行 .\gradlew.bat test --tests "org.example.music.user.controller.PasswordResetConfirmation*Test" --tests "org.example.music.user.service.PasswordResetConfirmation*Test" --console=plain，预期新契约/顺序失败。

~~~java
when(tickets.consume(ticket)).thenThrow(new PasswordResetVerificationUnavailableException(null));
assertThrows(PasswordResetConfirmationUnavailableException.class, () -> service.reset(request, "127.0.0.1"));
verifyNoInteractions(updater);
verify(tickets, times(1)).consume(ticket);
~~~
- [x] **4.3 实现HTTP及Service切换。** Reader白名单resetTicket/newPassword、字段数2、canonical校验改票字段，DTO两字段均WRITE_ONLY。Controller同步Operation及各ApiResponse。Service按设计顺序组织任务1至3组件及原updater，所有BCrypt在事务外，只有明确consume true进入updater；复用票据异常仅在本边界转码。普通400复用公共title/detail。
- [x] **4.4 清理旧专用消费并完成测试迁移。** 移除旧Store.consume/CONSUME及脚本、SnapshotReader.prepare(challenge)，保留isCurrent。VerificationServiceTest原never consume断言改为其真实exchange及零用户更新断言。原ConfirmationStoreIntegrationTest中非终态错码后成功消费、同码并发一次、五错失效、旧操作不触新码分别对应到已有TicketExchange/VerificationConcurrency测试，保留recordFailure/配额/损坏测试，记录方法对照。第三步Concurrency/Failure夹具改为Issued，已有事务故障与行级断言完整保留；旧第五错/非终态错码交错留在第二步验证测试。
- [x] **4.5 验证切换与回归。** .\gradlew.bat test --tests "org.example.music.user.*PasswordReset*Test" --console=plain，实际MySQL/Redis通过、零必要跳过。第二步阶段性测试明确替换为旧请求400/零扣额、两步独立额度，跨申请累计统一拒绝继续测试第二步自身，避免删断言冒充兼容。
- [x] **4.6 提交。** 中文提交“feat: 切换密码重置为票据与新密码提交”，提交前rg确认生产旧consume(challenge)、旧prepare(challenge)及三字段DTO调用归零。

## 任务5　三步闭环、并发及故障证据补齐

**Files:** 修改PasswordResetConfirmationIntegrationTest.java、PasswordResetConfirmationConcurrencyIntegrationTest.java、PasswordResetConfirmationFailureIntegrationTest.java及其Support，必要断言补入任务1/2新增真实Redis测试。原TransactionIntegrationTest继续实跑，无需修改SQL或事务组件。

**Interfaces:** 使用任务4 Issued/issue/confirm及现有prepared/updater、真实MySQL/Redis spy，故障必须围绕真实动作前后注入，闩锁均有超时。fixture可构造schema 1近到期票，但两个时间字段、实际双TTL与原300000毫秒跨度须一致。

- [x] **5.1 补成功与认证测试。** should_complete_three_steps_and_login_with_new_password覆盖USER/ADMIN，实际申请/受控投递/第二步兑换/第三步204、逐列只允许三字段变化、version+1、旧密码拒绝、新密码经原登录入口成功、旧JWT与旧角色选择票拒绝。他账户有效Bearer重置票主且调用方行/Token不变；无效/真实过期/撤销Bearer在业务前拒绝。
- [x] **5.2 补可恢复与边界测试。** should_preserve_ticket_for_same_password_then_accept_different_password断言同密码400、原票/PEXPIRETIME不变、quota已扣，随后不同密码成功。缺票/过期/被替换/假票400统一，损坏503保留现场，MAX可读但消费前503、2^53+1正常精确加1、每步窗口互不清零。
- [x] **5.3 补并发测试。** should_allow_only_one_database_update_for_concurrent_ticket_submissions断言最多一个204及一次SQL；should_invalidate_new_old_version_ticket_after_prior_consumption_commits以消费后/提交前闩锁让第二步签新票，再放行原CAS，新票第三步400。覆盖反向新票先签发、prepared后邮箱/角色/摘要/版本/删除导致400及票消费、仅username变化成功、期限内消费后SQL跨期限成功。
- [x] **5.4 补故障测试。** should_skip_write_transaction_when_consumption_reply_is_unknown分别模拟consume执行前抛错与callRealMethod后抛错，两者503且写SQL/写事务均0；should_report_unknown_after_actual_commit_without_restoring_ticket模拟真实提交后丢确认，断言行已改/版本已增、旧票400、新密码正常登录核实。保留真实事务开始/查询/只读完成/写入/回滚故障，单次SQL/无自动重试/不恢复票；IP与账户准入实际执行后丢回复计数不退。断言HTTP仅CONFIRMATION不可用码族。

~~~java
assertProblem(confirm(issued, "new-password"), 503, "PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
verify(updater, never()).update(any(), anyString());
verify(users, never()).resetPasswordIfSnapshotMatches(anyInt(), any(), anyString(), anyString(), anyLong(), anyString());
~~~

该断言用于消费未知测试，confirm前清除创建账户、发码与兑换阶段的spy调用记录，额外验证没有启动写事务。
- [x] **5.5 运行验证。** .\gradlew.bat test --tests "org.example.music.user.*PasswordResetConfirmation*Test" --tests "org.example.music.user.*PasswordResetTicket*Test" --tests "org.example.music.user.*PasswordResetCompletion*Test" --tests "org.example.music.user.*PasswordResetVerification*Test" --console=plain，记录通过/失败/错误/跳过，所有故障和并发断言实际运行。
- [x] **5.6 提交。** 中文提交“test: 补齐三步密码找回并发与故障验证”。

## 任务6　API、部署及阶段性表述同步

**Files:** docs/api/user/confirm-password-reset.md、verify-password-reset.md、request-password-reset.md，docs/deployment/password-reset-confirmations.md、password-reset-verifications.md、password-reset-requests.md，ConfirmationControllerTest及VerificationIntegrationTest的真实OpenAPI断言。

**Interfaces:** 以设计§3/5/10/13为契约，第二步行为保持，文档统一切换后状态。落实本轮设计NOTE-001。

- [x] **6.1 写文档契约失败断言。** should_publish_two_field_reset_contract_and_final_quota_mapping断言实际/v3/api-docs第三步只含resetTicket/newPassword及204/400/401/403/415/429/500/503，两字段WRITE_ONLY，第二步仍challengeId/code→resetTicket/expiresAt。HTTP旧三字段400，两步分别使用VERIFICATION/CONFIRMATION错误族。
- [x] **6.2 实现文档与必要注解修正。** 第三步完整Method/Path/权限/字段/示例/204/错误/可执行curl、原期限、同密码保留票、未知恢复及旧登录撤销。两份第二步文档移除“当前共享旧组合入口预算”“completion后续交付”等现行声明，改成第二步confirmation、第三步completion独立额度最终表。第一步API第35行“确认端点另行交付”改成明确先调用第二步，再提交票据到第三步；第一步部署第5行保留原发码交付范围的历史说明并补充现行三步调用关系。历史迁移说明使用明确历史时态，部署注明协调实例/客户端切换、schema 1在途票兼容。
- [x] **6.3 验证文档与实际OpenAPI。** 重跑相关ControllerTest及VerificationIntegrationTest，git diff --check；rg检查六份文档的“当前组合/后续交付/另行交付/共用预算”逐项确认只剩明确历史语境。curl使用安全本地请求文件，无真实密码或票据。
- [x] **6.4 提交并冻结源代码。** 中文提交“docs: 同步三步密码找回接口与部署契约”，记录此时源码/测试冻结commit。后续如修代码须重新冻结并全量验证。

## 任务7　全量构建与独立实现审核材料

**Files:** 新增docs/reviews/2026-11-10-password-reset-completion-review.md，更新本计划实际进度/结果及设计审核登记。代码如有修复返回对应任务验证并重新冻结。

**Interfaces:** 审核仅POST /api/v1/users/password-resets，基线main @ 646c1e6及本轮冻结commit，保留设计第1/3轮与后续实现审核各自事实。

- [ ] **7.1 运行完整验证。** PowerShell先设置$env:MINIO_INTEGRATION_TESTS = "true"，执行.\gradlew.bat cleanTest check --console=plain。等待实际结束，记录退出码、BUILD状态、耗时，从本次JUnit XML聚合类数及通过/失败/错误/跳过；列出任何跳过类#方法和原因，必要容器零跳过后才可交付。
- [ ] **7.2 执行最终范围检查。** git diff --check、git status --short、基线到HEAD文件清单及untracked；核对未动已执行迁移、SQL/配置/依赖、无秘密与TODO/占位；核实旧测试迁移方法对应关系、两份第二步文档已切最终语义。XML数字须与本次源代码冻结相符。
- [ ] **7.3 编写审核材料。** 按AGENTS第14节含唯一Method+Path、权限、声称行为、全部文件含untracked、无迁移、新旧契约、API/部署路径、实际命令与精确数字、基线/冻结commit、安全curl、默认项及故障边界。写入设计NOTE-001六列表，记录已采纳待实现审核复核；跨任务Reader NOTE-001继续排期，原编号与非阻断分级不变。
- [ ] **7.4 提交材料并交用户转独立审核。** 中文提交“docs: 记录密码重置三步闭环验证与审核材料”。汇报完成的是本轮实现自验，独立accepted到达后才登记接口审核完成，按用户当时授权决定推送。

## 设计审核反馈复核与处置

设计审核对象646c1e6，第1/3轮accepted，零阻断项、无剩余争议。审核方独立执行MINIO_INTEGRATION_TESTS=true cleanTest check，17分08秒、退出0、BUILD SUCCESSFUL，168类1926通过/0失败/0错误/0跳过，真实MySQL8.4/Redis/MinIO。仅复核既有基线，该数字属于设计审核基线。当前实现定向26类252通过/0失败/0错误/0跳过，完整验证在任务7记录。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| 本轮设计NOTE-001 非阻断 | 成立，verify-password-reset.md:9/50/77-91及password-reset-verifications.md:7/13-29仍描述旧组合入口与第二步共享及completion后续交付 | 认可，当前设计已accepted，属切换文档同步范围建议 | 认可，明确两份第二步文档即可解决，无需修改第二步业务契约 | 采纳并列入文件地图及任务6，实施后改成旧请求400/独立额度/completion启用 | 任务6已实际同步两份第二步API/部署文档，定向真实OpenAPI与两步独立额度测试通过，待独立实现审核复核 |
| 前轮独立验证接口实现NOTE-001 非阻断 | 成立，两个Reader引擎重复，已在第三步设计评估并获用户方案A确认 | 认可，维护建议且前轮实现已accepted | 认可共享方向，用户选择本轮最小切换 | 共享引擎继续排期，修改现有第三步Reader | 本轮未提取引擎，未自行标记审核关闭 |

## 计划自查与确认门禁

已逐节映射设计到任务1至7，五项Review Focus均有具体测试落点。新Store签名、脚本参数/回复、DTO字段、prepared与quota接口一致；直接切换的所有旧构造/消费调用在同一任务4迁移，避免跨任务保留无法编译的代码。移除旧消费只发生于新兑换/票消费证据具备之后，测试迁移保留行为对应。

本计划只写接口签名、关键断言及协议取值，不预写业务实现。文件范围、源码冻结、fresh完整验证、文档三方对照与反馈原编号均明确。用户已确认计划，按既有原生执行方式实施。

## 实施记录

- 729f91d完成任务1，真实Redis新读取及原readCurrent/exchange回归通过；新增读取回复边界单测通过。
- 874e808完成任务2，真实Redis跨实例一次性消费、签发/消费双向顺序及可定位损坏测试通过。
- c049c37完成任务3，独立额度与prepared票绑定测试通过，第二步服务回归通过。
- d43642f完成任务4，命令为cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordReset*Test" --console=plain，退出0、BUILD SUCCESSFUL，4分56秒，46类369通过/0失败/0错误/0跳过。
- 89e5068完成任务5，定向命令为cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordResetConfirmation*Test" --tests "org.example.music.user.*PasswordResetTicket*Test" --tests "org.example.music.user.*PasswordResetCompletion*Test" --tests "org.example.music.user.*PasswordResetVerification*Test" --console=plain，退出0、BUILD SUCCESSFUL，3分31秒，26类252通过/0失败/0错误/0跳过。
- 任务5首轮252项中查询故障夹具1项失败。对Spring Data抽象查询使用callRealMethod触发夹具错误，已改为真实MySQL临时表名变更制造查询失败，并以finally恢复。故障类单独17项通过，随后上述252项全量定向通过。初次失败如实保留，不计为最终通过。
- 任务6同步六份API/部署文档，落实本轮设计NOTE-001。源码/测试冻结以本次文档提交commit为准，完整check执行及实际结果由任务7补记。
