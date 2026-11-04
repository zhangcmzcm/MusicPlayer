# 申请找回密码及异步邮箱发码实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式已由AGENTS.md确定：Codex唯一实现，Claude独立只读审核；本会话逐任务实施，不派发实现子代理。上述执行子技能未列为可用，不声称调用。本文为计划，等用户审阅确认后才实现。

**Goal:** 实现唯一公开接口POST /api/v1/users/password-reset-requests，统一202受理，通过有界Redis任务驱动一次异步邮箱发码，成功后激活找回专用验证码。

**Architecture:** 严格解析→Redis三维扣额及固定期限→MySQL最小受理快照→原子有界入队→202；后台跨实例一次领取、绑定复核、事务外BCrypt/SMTP、复核后原子激活。最新pending、领取中的任务及ACTIVE分别保存，未知结果不重试、不恢复、不退款。

**Tech Stack:** Java21、Spring Boot4.1.1、JPA/MySQL8.4、StringRedisTemplate/Lua、JavaMail、Jakarta Validation、JUnit5/MockMvc/Testcontainers、Gradle Wrapper。无新增依赖、业务表、迁移或Compose服务。

**Spec:** [已通过设计审核的书面设计](../specs/2026-11-04-password-reset-request-design.md)，原始设计83bab37，第1/3轮accepted；本文同步落实NOTE-001/002文档澄清。代码基线main @ 0705928，计划开始时HEAD=83bab37。

## Global Constraints

- 唯一POST /api/v1/users/password-reset-requests；不实现密码重置确认、申请查询、恢复、取消、refresh或其他HTTP端点。
- 未登录可用，USER/ADMIN同规则；正常请求不带Token，不要求auth_time；主动附带无效Bearer仍走现有认证链。
- 仅email字符串，trim+Locale.ROOT小写，≤120字符，拒绝CR/LF、孤立代理、非单地址；严格UTF-8 application/json、重复/转义重复/未知键、多根、嵌套非法；16384字节预算、16385过读检测。
- 明确入队才202，响应仅challengeId/expiresAt及no-store；标识32字节SecureRandom、43字符规范Base64URL无填充，Redis只存SHA-256摘要。
- Redis TIME准入后绝对300秒deadline；所有任务同格式、同额度、同容量，不存在账户也入队；不等待SMTP，不保证所有分支等时。
- 邮箱60秒1次、每UTC日10次、IP固定60秒5次；三维拒绝全不扣额不续窗，Retry-After取最大受阻等待、向上取整至少1秒；独立命名空间，失败不退款。
- 受理时读取最小账户快照，使用现有邮箱唯一索引等值查询；NO PAD下存储地址首尾空格按无匹配处理，禁止LOWER/TRIM全表扫描；大小写可命中，重音候选须规范化后精确相等。
- 全局1000未结束任务、每实例4路发送、SMTP等待20秒；外部化并正数验证。单Redis协议，Redis持久化故障可丢任务，不宣称永久投递或exactly-once。
- 仅最新待处理申请有激活资格，旧ACTIVE保留至新码明确激活。领取一次，无租约接管重投；领取回包未知不发送，SMTP未知不激活不重发，激活未知不恢复旧ACTIVE。
- 有界过期索引清理；已领取失格仍占容量至完成/过期；队列满503不驱逐未到期任务。不得使用应用内队列冒充可靠受理。
- 不在数据库事务中等待Redis/BCrypt/SMTP，不改用户数据。token_version保留Long十进制字符串，不经Lua浮点转换。
- ProblemDetail及既有小写下划线urn；400/415/429/503/500与认证链401/403完整记录。新异常不改原邮箱接口专用415/400映射。
- 中文文档与提交；日志/toString/异常链无邮箱/IP明文、码/摘要、challengeId、密码、Token、邮件内容。后台关联原traceId，不扩大Actuator。
- 最终必须MINIO_INTEGRATION_TESTS=true运行cleanTest check，真实MySQL/Redis/MinIO不跳过；历史1599不作本接口结果。

## Review Focus

1. NO PAD历史首尾空格与大小写/重音：唯一索引查询后精确核验，文档不声称空白历史地址可找回；任务2/8具名测试。
2. 慢查询跨过冷却后迟到入队：旧准入不能覆盖已经完成的新申请，最新时间水位保留到deadline；任务4/8具名测试。
3. 旧任务已领取又被替换：旧任务仍占容量、可收到死码邮件但不能激活，结束不能动新pending/ACTIVE；任务4/6/8具名测试。
4. Redis实际领取/激活后丢回包：未知领取绝不发信，未知激活绝不恢复旧码；任务4/6/8真实脚本故障注入。
5. 主动附带过期或被撤销Token：真实安全链可拒绝，未带Token正常202，API/OpenAPI含401/403沿用说明而不制造正常403；任务7/8测试。

## 文件结构与协议总览

下列文件均限定本接口直接需要；路径以仓库根为基准。任务中标明的Test文件属于测试，不修改既有测试来消除失败。配置/邮件组件随所属能力交付，不独立生成空壳。

| 职责 | 新增文件 |
| --- | --- |
| HTTP与DTO | src/main/java/org/example/music/user/controller/PasswordResetRequestReader.java、PasswordResetRequestController.java；src/main/java/org/example/music/user/dto/PasswordResetRequest.java、PasswordResetRequestResponse.java |
| 规则及模型 | src/main/java/org/example/music/user/domain/PasswordResetEmailRules.java、PasswordResetIdentifiers.java、PasswordResetAccountSnapshot.java、PasswordResetAdmission.java、PasswordResetTask.java、PasswordResetClaim.java |
| 存储 | src/main/java/org/example/music/user/repository/PasswordResetRequestStore.java；src/main/resources/redis/password-reset-request/common.lua、admit.lua、enqueue.lua、claim.lua、prepare.lua、activate.lua、finish.lua、reap.lua |
| 协调 | src/main/java/org/example/music/user/service/PasswordResetSnapshotReader.java、PasswordResetRequestService.java、PasswordResetTaskProcessor.java、PasswordResetWorkerLifecycle.java |
| 配置 | src/main/java/org/example/music/user/config/PasswordResetProperties.java |
| 邮件 | src/main/java/org/example/music/user/email/PasswordResetMessage.java、PasswordResetEmailSender.java、SmtpPasswordResetEmailSender.java、IsolatedPasswordResetMailClientFactory.java、BoundedPasswordResetEmailDelivery.java |
| 异常 | src/main/java/org/example/music/user/exception/PasswordResetValidationException.java、PasswordResetUnsupportedMediaTypeException.java、PasswordResetRequestRateLimitedException.java、PasswordResetRequestUnavailableException.java、PasswordResetSendFailedException.java、PasswordResetDiagnostics.java |
| 文档 | docs/api/user/request-password-reset.md、docs/deployment/password-reset-requests.md、docs/reviews/2026-11-04-password-reset-request-review.md |

只修改现有UserRepository（最小查询投影）、SecurityConfig（单公开路径）、GlobalExceptionHandler（专用映射）及application.properties/.env.example（本功能配置占位）。不改Flyway、旧生产者脚本、JWT和密码策略。

共享模型签名，所有record敏感toString显式脱敏：

```java
record PasswordResetAccountSnapshot(Integer userId, String email, UserRole userRole, long tokenVersion) {}
record PasswordResetAdmission(Instant admittedAt, Instant expiresAt) {}
record PasswordResetTask(String challengeDigest, String emailDigest, String email,
    PasswordResetAdmission admission, Optional<PasswordResetAccountSnapshot> account, String traceId) {}
record PasswordResetClaim(PasswordResetTask task, String ownerNonce) {}
record PasswordResetMessage(String email, String code, Instant expiresAt) {}
```

email是规范化投递目标；account.email保留数据库原始值作后续精确复核，无账户使用Optional.empty，不用虚构ID/版本冒充账户。role为当时账户角色，仅复核存储完整性，不接受客户端角色。ownerNonce由领取方生成32字节随机标识摘要，领取后不可换owner，所有prepare/activate/finish比较digest+owner+原期限。

### 任务1：严格HTTP输入、地址规则及随机标识

**Files:** 新增上表Reader、两个DTO、EmailRules、Identifiers和Validation/UnsupportedMediaType/Diagnostics；测试src/test/java/org/example/music/user/controller/PasswordResetRequestReaderTest.java及src/test/java/org/example/music/user/domain/PasswordResetEmailRulesTest.java、PasswordResetIdentifiersTest.java。

**Interfaces:** `PasswordResetRequestReader(Validator).read(HttpServletRequest): PasswordResetRequest`；Request为`record(String email)`并带Bean Validation，Response为`record(String challengeId, Instant expiresAt)`；Rules注入Validator，`String normalize(String raw)`与`boolean matches(String normalizedInput, String storedEmail)`；Identifiers提供`String generate()`、`String digest(String value)`、`boolean isCanonicalDigest(String value)`，生成与摘要不写日志。

- [ ] **步骤1：写失败测试。** `should_normalize_only_one_strict_email_field()`断言`" Alice@Example.com "→"alice@example.com"`；`should_reject_duplicate_escaped_unknown_or_non_string_fields()`覆盖转义重复、null/数组/嵌套/多根；`should_accept_16384_and_reject_16385_bytes_including_whitespace()`；`should_reject_invalid_utf8_surrogates_crlf_and_non_json_media_type()`固定400/415异常分类。Identifiers断言decode长度32、43字符、重新编码相同、不同输入摘要不同，非规范末位拒绝。

```java
assertEquals("alice@example.com", rules.normalize(" Alice@Example.com "));
String identifier = PasswordResetIdentifiers.generate();
assertEquals(43, identifier.length());
assertEquals(32, Base64.getUrlDecoder().decode(identifier).length);
assertFalse(rules.matches("alice@example.com", "álice@example.com"));
```

- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.PasswordResetRequestReaderTest" --tests "org.example.music.user.domain.PasswordReset*Test" --console=plain`，确认缺实现而失败，不把环境故障当TDD红灯。
- [ ] **步骤3：实现Reader/Rules/DTO及标识。** 有界过读、UTF-8 REPORT、严格单地址，原值CRLF/孤立代理检查先于trim。matches对已命中的候选规范化后String.equals，不自行查询或允许重音等价。异常固定中文安全文案，Diagnostics只保留安全分类/栈，不链出原始邮件或Redis内容。
- [ ] **步骤4：运行同命令至全绿。** Rules测试覆盖大小写匹配、重音不匹配、null/空白不匹配及损坏地址安全失败；本阶段不声称历史空白地址能被SQL命中。
- [ ] **步骤5：检查并中文提交。** `git diff --check`，提交`feat: 增加找回密码申请严格输入校验`，仅暂存任务文件。

### 任务2：受理账户快照与历史邮箱语义

**Files:** 新增AccountSnapshot、service/PasswordResetSnapshotReader.java、exception/PasswordResetRequestUnavailableException.java；修改UserRepository.java追加投影查询；测试src/test/java/org/example/music/user/service/PasswordResetSnapshotReaderTest.java、src/test/java/org/example/music/user/PasswordResetSnapshotIntegrationTest.java。Unavailable接受脱敏cause，供后续Store/Service复用，避免任务2依赖尚未创建的任务3异常。

**Interfaces:** Repository追加`Optional<PasswordResetAccountSnapshot> findPasswordResetSnapshotByEmail(String email)`及`findPasswordResetSnapshotById(Integer userId)`，显式@Param；Reader注入Repository/PlatformTransactionManager/Rules，`Optional<PasswordResetAccountSnapshot> capture(String normalizedEmail)`、`boolean stillMatches(PasswordResetAccountSnapshot expected, String normalizedEmail)`。短只读TransactionTemplate外层处理启动/读取/完成异常为Unavailable；stillMatches缺行/绑定或版本/角色变化返回false，损坏/存储故障抛Unavailable。

- [ ] **步骤1：写失败测试。** `should_capture_minimal_raw_snapshot_without_password()`、`should_reject_accent_only_candidate_after_index_lookup()`；真实MySQL `should_treat_padded_historical_email_as_unmatched_under_no_pad()`分别保存首空格、尾空格；`should_match_case_variant_but_not_accent_variant()`。NULL/空白不匹配，角色/版本损坏不当无账户；transaction开始/提交读失败转503。

```java
// 对首/尾带空格的真实数据库行分别执行；不将Java normalize测试冒充SQL匹配测试。
assertTrue(reader.capture("alice@example.com").isEmpty());
```

- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.PasswordResetSnapshotReaderTest" --tests "org.example.music.user.PasswordResetSnapshotIntegrationTest" --console=plain`；MySQL8.4容器必须实际运行。
- [ ] **步骤3：实现两个等值投影及Reader。** 不加载Entity/密码、不作LOWER/TRIM全表扫描、不写数据库。capture按邮箱索引定位后精确规范化核验；stillMatches按ID复读并比较原始邮箱/版本/角色，不刷新expected，不替不存在任务补绑新账户。
- [ ] **步骤4：同命令全绿并核对实际SQL。** 断言整行不变；复核同时改名不使邮箱绑定失效，改密/换邮箱/角色版本变化及删除使其失效。
- [ ] **步骤5：检查并提交。** `feat: 增加找回密码受理账户快照`。

### 任务3：独立三维Redis准入

**Files:** 新增Admission及Store、common.lua/admit.lua、RateLimited异常，复用任务2的Unavailable；测试src/test/java/org/example/music/user/repository/PasswordResetRequestStoreTest.java、src/test/java/org/example/music/user/PasswordResetAdmissionIntegrationTest.java。任务4在同Store追加队列方法，任务3不留未实现方法。

**Interfaces:** `PasswordResetRequestStore(StringRedisTemplate)`，`PasswordResetAdmission admit(String emailDigest, String ipDigest)`。RateLimited暴露`long retryAfterSeconds()`，Unavailable接受脱敏cause。脚本以List协议返回成功时间或明确限流状态；Java严格验证元组长度/类型/时间范围，所有其他回复503，不自动重试。

- [ ] **步骤1：写失败测试。** `should_limit_email_to_one_per_60_seconds_and_ten_per_utc_day()`、`should_limit_ip_to_five_per_fixed_60_seconds()`、`should_use_maximum_blocked_wait_without_partial_increment_or_extension()`；损坏数值、WRONGTYPE、缺TTL、执行后丢回复和异常元组都503。准入成功expiresAt−admittedAt恰300秒。

```java
PasswordResetAdmission admission = store.admit(emailDigest, ipDigest);
assertEquals(Duration.ofSeconds(300), Duration.between(admission.admittedAt(), admission.expiresAt()));
assertThrows(PasswordResetRequestRateLimitedException.class, () -> store.admit(emailDigest, ipDigest));
```

- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.repository.PasswordResetRequestStoreTest" --tests "org.example.music.user.PasswordResetAdmissionIntegrationTest" --console=plain`。
- [ ] **步骤3：实现准入协议。** 键前缀`music:auth:password-reset:`，`email-cooldown:<digest>`、`email-daily:<digest>`、`ip:<digest>`；UTC日计数存储日边界与原TTL，Redis TIME计算，先校验三个维度全部结构和受阻等待，再一起写入。Lua内不能依赖运行错误回滚先前写入。所有毫秒限制在整数精确范围，版本不在此脚本处理。
- [ ] **步骤4：真实Redis全绿。** 用Lua测试夹具注入原窗口/边界值，以Redis TIME而非修改应用Clock验证日切。拒绝前后逐键比较计数/PTTL；证明无账户同参数走同准入、旧命名空间不动，伪造IP头留至任务7真实HTTP测试。
- [ ] **步骤5：检查并提交。** `feat: 增加找回密码独立申请限流`。

### 任务4：有界队列、一次领取、替换与原子激活

**Files:** 新增Task/Claim；在Store及common.lua追加队列协议，新增enqueue/claim/prepare/activate/finish/reap.lua；扩展StoreTest，新增src/test/java/org/example/music/user/PasswordResetQueueIntegrationTest.java。

**Interfaces:** Store追加`void enqueue(PasswordResetTask task, int capacity)`、`Optional<PasswordResetClaim> claim(String ownerNonce)`、`boolean prepare(PasswordResetClaim claim, String codeHash)`、`boolean activate(PasswordResetClaim claim)`、`void finish(PasswordResetClaim claim)`、`int reap(int batchSize)`。prepare/activate false仅表示已失效/替换/过期；损坏或未知回复必须Unavailable，不混为false。finish幂等，只结束自身，不操作ACTIVE。

**固定状态/索引：** `task:<digest>`HASH含schema=1、emailDigest/email、admittedAt/expiresAt毫秒字符串、accountPresent、快照字段、traceId、phase、ownerNonce、可选codeHash；无账户字段固定空串。`pending:<emailDigest>`当前任务摘要；`latest:<emailDigest>`保存最新入队的admittedAt至其原deadline，任务提前结束仍保留；`ready`ZSET score=admittedAt，`outstanding`ZSET score=expiresAt。ACTIVE使用`active:<emailDigest>`摘要指针及`challenge:<digest>`HASH（schema/完整账户绑定/受理与到期时间/hash/attempts=0），TTL至原deadline；后续确认按摘要定位。task/claim以完整不可变字段+ownerNonce CAS，旧摘要不能误操作替换状态；哈希有字段白名单与规范值检查。

- [ ] **步骤1：写失败测试。** `should_replace_queued_task_without_changing_active()`、`should_keep_claimed_replaced_task_in_capacity_until_finish_or_expiry()`；`should_reject_late_old_enqueue_after_new_task_has_finished()`；`should_claim_once_across_two_store_instances()`；`should_reap_expired_indexes_without_deleting_new_pending()`。用容量2的夹具固定容量判断和拒绝原子性，不需创建1000份测试数据。

```java
// 两个Store实例指向同一真实Redis；使用仅一个未结束任务的夹具。
assertTrue(firstStore.claim(firstOwner).isPresent());
assertTrue(secondStore.claim(secondOwner).isEmpty());
```

- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.repository.PasswordResetRequestStoreTest" --tests "org.example.music.user.PasswordResetQueueIntegrationTest" --console=plain`。
- [ ] **步骤3：实现enqueue/claim/reap。** enqueue在同脚本检查截止、水位、容量和可替换旧任务；同邮箱60秒准入隔离提供严格不同的先后时间，旧admittedAt≤latest拒绝503。未领取旧任务可抵扣容量；领取中旧任务不能抵扣。所有key类型及待写值先校验，完成脚本后才返回成功。claim原子QUEUED→CLAIMED并去ready，保留outstanding；同脚本确认最新归属及未过期，不改变owner或续TTL。reap每次最多batchSize个到期成员，逐项CAS清指针，仅清自己，永不误删ACTIVE。
- [ ] **步骤4：实现prepare/activate/finish。** prepare只允许同一owner的CLAIMED→PREPARED一次，hash符合真实BCrypt格式；activate检查最新pending/水位、完整绑定、期限、PREPARED、owner与hash，原子替换ACTIVE及challenge索引，删除旧challenge只能匹配旧指针。完成任务释放outstanding/ready及自己的pending，不删latest水位；后续finish不能误删可能已经成功的ACTIVE。旧ACTIVE及其他用途始终不受未激活失败影响。
- [ ] **步骤5：补并发/故障全绿。** `should_not_send_after_claim_executed_but_reply_lost()`在Store层验证Unavailable；`should_not_restore_old_active_after_activation_reply_lost()`执行真Lua后抛错并确认新ACTIVE仍在；同code不同digest、第五次/消费的后续能力本轮不实现，但ACTIVE字段attempts=0且隔离可检查。WRONGTYPE/损坏/无TTL、Long.MAX_VALUE版本原样保存不舍入、原始期限与PEXPIRETIME一致、到期后不可领取/准备/激活均实测。闩锁控制多实例顺序，不sleep。
- [ ] **步骤6：检查并提交。** `feat: 增加找回密码有界任务队列与一次激活`，旧Redis脚本零修改。

### 任务5：专用有界SMTP投递及配置

**Files:** 新增上表5个邮件文件、PasswordResetProperties、SendFailed异常；修改application.properties与.env.example只追加本功能占位。测试src/test/java/org/example/music/user/email/BoundedPasswordResetEmailDeliveryTest.java、SmtpPasswordResetEmailSenderTest.java、IsolatedPasswordResetMailClientFactoryTest.java，src/test/java/org/example/music/user/config/PasswordResetPropertiesTest.java。

**Interfaces:** `PasswordResetEmailSender.send(PasswordResetMessage): void`；`BoundedPasswordResetEmailDelivery.send(PasswordResetMessage): void`，失败抛SendFailed；隔离工厂`JavaMailSenderImpl createClient(Duration sendTimeout)`；Properties通过`@ConfigurationProperties("app.password-reset")`、@Validated注册，字段`int queueCapacity=1000`、`int workerConcurrency=4`、`Duration sendTimeout=20s`，三个均正数，Duration可转换为有界毫秒。沿用JavaMailSender来源与app.mail.from，不改其他用途client。

- [ ] **步骤1：写失败测试。** `should_send_only_password_reset_template_without_challenge_id()`断言用途/有效期/最新申请说明；`should_bound_concurrency_and_timeout_without_automatic_retry()`断言一次sender调用、拒绝/超时不重试、关闭执行器、中断标记恢复；`should_isolate_smtp_timeouts_without_mutating_original_client()`；`should_reject_non_positive_or_unrepresentable_configuration()`。

```java
assertThrows(PasswordResetSendFailedException.class, () -> delivery.send(message));
verify(sender, times(1)).send(message); // 可控sender失败夹具，不进行重试。
```

- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.email.*PasswordReset*Test" --tests "org.example.music.user.config.PasswordResetPropertiesTest" --console=plain`。
- [ ] **步骤3：实现邮件与配置。** `app.password-reset.queue-capacity=${PASSWORD_RESET_QUEUE_CAPACITY:1000}`、worker-concurrency对应4、send-timeout对应20s；.env.example只放示意默认值。投递器固定线程数等于workerConcurrency，SynchronousQueue+AbortPolicy，无缓冲排队；Future等待至sendTimeout，超时cancel(true)并终止本次激活资格，即使底层迟到也不能回调激活。复制client的smtp/smtps连接/读/写超时均受该正数上限控制，保持凭据不输出。
- [ ] **步骤4：运行同命令全绿。** 真实密码/验证码不进toString/日志；发送器不含数据库或Redis操作，@PreDestroy受控关闭，线程名可辨识且不带账户信息。
- [ ] **步骤5：检查并提交。** `feat: 增加找回密码专用有界邮件投递`。

### 任务6：后台处理协调与生命周期

**Files:** 新增PasswordResetTaskProcessor、PasswordResetWorkerLifecycle；测试src/test/java/org/example/music/user/service/PasswordResetTaskProcessorTest.java、PasswordResetWorkerLifecycleTest.java。

**Interfaces:** Processor注入Store/Reader/PasswordHasher/SecureVerificationCodeGenerator/Delivery，`boolean processNext()`（未领取返回false，领取并结束/失败返回true，存储异常可抛Unavailable交调度层脱敏记录）；Lifecycle实现SmartLifecycle，注入Processor/Store/Properties，提供标准start/stop/isRunning，负责领取轮询和到期维护。测试可替换Lifecycle bean停止自动调度，再显式调用真实Processor；不得mock Processor/Store代替主链路验证。

- [ ] **步骤1：写失败测试。** `should_finish_unmatched_account_task_without_smtp()`；`should_recheck_before_send_and_before_activation()`用inOrder证明；`should_not_activate_or_retry_after_smtp_timeout()`；`should_finish_only_own_task_after_replacement()`；`should_not_send_when_claim_reply_is_unknown()`；`should_preserve_active_when_activation_reply_is_unknown()`。无账户任务不在处理时重新capture账户。

```java
assertTrue(processor.processNext()); // 真实领取到一个无账户任务的夹具。
verifyNoInteractions(delivery);
verify(store).finish(claim);
```

- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.PasswordResetTaskProcessorTest" --tests "org.example.music.user.service.PasswordResetWorkerLifecycleTest" --console=plain`。
- [ ] **步骤3：实现Processor。** 原子claim→无账户结束或stillMatches→生成6位ASCII码并BCrypt→明确prepare成功→有界send→stillMatches→activate；每次Store操作继续核对期限和最新归属。SecureVerificationCodeGenerator若产出非ASCII十进制字符，逐位Character.digit转ASCII并验证六位，无效生成结果安全失败，不发送。无自动重试，不从失败任务重新claim；finally只finish自身，不清ACTIVE/latest，finish失败独立日志不掩盖原故障。设置并恢复受理traceId的MDC，异常链统一脱敏。
- [ ] **步骤4：实现Lifecycle。** 专用ScheduledThreadPoolExecutor(workerConcurrency)启动4个固定延迟250ms的处理循环，每循环最多一个processNext，另一个单线程每秒reap(100)；延迟为内部调度默认值，不影响Redis时间语义。捕获每轮异常并安全记录，避免定时任务因未捕获异常永久停摆；不做进程内无限重试/消息缓存。stop先停止领取/维护，再停止投递器和本地执行器；中断中的任务不归还队列，执行器关闭有界等待，失败仍靠原deadline回收。
- [ ] **步骤5：同命令全绿。** 用可注入执行器/可控调度测试start幂等、stop不再领取、任务单轮异常后下一轮仍可运行、最多workerConcurrency并发和sendTimeout后不激活。不使用长sleep模拟进程崩溃。
- [ ] **步骤6：检查并提交。** `feat: 增加找回密码异步发码任务处理`。

### 任务7：受理协调、公开端点与API契约

**Files:** 新增RequestService/Controller；修改SecurityConfig与GlobalExceptionHandler只追加；测试src/test/java/org/example/music/user/service/PasswordResetRequestServiceTest.java、src/test/java/org/example/music/user/controller/PasswordResetRequestControllerTest.java；新增API文档。

**Interfaces:** `PasswordResetRequestService.request(PasswordResetRequest request, String clientIp, String traceId): PasswordResetRequestResponse`，注入Store/Reader/Properties；Controller仅解析、ClientIpResolver取remoteAddr、调用Service并202/no-store，无Principal/Session参数。Service执行Identifiers生成标识/摘要、admit→capture→enqueue，空账户也构造同Task；response的expiresAt仅取Admission，不由应用Clock重算。

- [ ] **步骤1：写失败测试。** `should_admit_capture_enqueue_before_returning_202_for_both_account_outcomes()`，inOrder且空账户同参数计数；`should_not_refund_or_retry_after_enqueue_uncertainty()`；HTTP未认证202、仅两字段且规范标识、429含Retry-After、400/415/503/500稳定code/type。仅新增POST公开，其他现有受保护路径仍401。

```java
mvc.perform(post("/api/v1/users/password-reset-requests").contentType("application/json")
    .content("{\"email\":\"alice@example.com\"}"))
    .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"));
```

- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.PasswordResetRequestServiceTest" --tests "org.example.music.user.controller.PasswordResetRequestControllerTest" --console=plain`。
- [ ] **步骤3：实现Service/Controller及专用handler。** 不调用邮件/Processor，不在controller处理业务错误；新Validation/415/限流/Unavailable映射保持旧接口title和code不变。@Tag/@Operation/@Schema/请求体及202、400、401、403、415、429、500、503全部响应声明；安全链401可无ProblemDetail，不能声明全部认证错误一定有业务正文。带有效Token不改变输入目标账户，也不要求近期认证。
- [ ] **步骤4：写API文档并同命令全绿。** 路径docs/api/user/request-password-reset.md，含字段与16KiB解释、202不保证送达、期限/限流/重发/旧码语义、无账户统一响应、历史空白不匹配、完整错误表及curl。补401/403沿用行；列INVALID_TOKEN/TOKEN_REVOKED/FORBIDDEN及认证状态503 AUTH_STATE_STORE_UNAVAILABLE，说明正常无Token请求无需登录，无额外正常角色403。
- [ ] **步骤5：检查并提交。** `feat: 暴露找回密码申请接口`。Controller切片测试不能代替任务8真实安全链验证，禁止为过测试移除Filter。

### 任务8：真实链路、故障并发、部署与全量送审

**Files:** 新增src/test/java/org/example/music/user/PasswordResetRequestIntegrationSupport.java、PasswordResetRequestIntegrationTest.java、PasswordResetRequestConcurrencyIntegrationTest.java、PasswordResetRequestFailureIntegrationTest.java；新增部署与审核材料，更新本计划执行状态。集成夹具复用概念、不修改现有确认测试夹具。

**Interfaces:** @SpringBootTest+MockMvc/MySQL8.4/真实Redis，具体测试类显式Docker条件，@DirtiesContext防复用已停止容器。仅替换SMTP sender及自动Lifecycle调度，实际调用Processor；需要调度证据的测试独立创建两个真实Lifecycle/Store实例共享Redis并可控关停。JWT由实际JwtTokenService签名，测试走真实SecurityConfig和TokenVersionFilter，不用手写认证绕过链路。

- [ ] **步骤1：写真实HTTP/SQL测试。** `should_return_same_202_shape_for_existing_and_missing_email()`，两者均查额度/任务/容量；`should_not_send_for_padded_or_accent_only_historical_email()`覆盖NOTE-001；`should_keep_user_row_unchanged_after_request_and_delivery()`，USER/ADMIN及未绑定账户、注册后不追认原无账户任务。GET /v3/api-docs锁定唯一新path、schema及8类响应码，NOTE-002真实JWT过期/签名错误/版本撤销被拒绝，未带Token202；403只声明沿用，不编造本接口角色禁止分支。伪造IP头不改变remoteAddr摘要键，客户端email/userId头不能指定其他快照。

```java
assertEquals(beforeRow, jdbc.queryForMap("select * from users where id = ?", userId));
verify(sender, times(1)).send(any(PasswordResetMessage.class)); // 完成真实领取/准备/激活之后。
```

- [ ] **步骤2：写真实替换/容量交错测试。** `should_not_overwrite_newer_finished_request_when_old_capture_returns_late()`；`should_keep_claimed_old_task_counted_while_new_pending_runs()`；`should_not_activate_old_send_after_new_request_is_enqueued()`；`should_preserve_old_active_until_new_activation()`。闩锁控制capture、claim、SMTP、activate，两个实际Store/Processor共享Redis验证同任务恰一次sender调用；到期前/边界后状态不延长，索引清理后容量回收，不误删新版指针。
- [ ] **步骤3：写真实故障测试。** 在真实admit/enqueue/claim/prepare/activate/finish/reap执行后抛确认丢失：受理未知503可能有任务；claim未知零SMTP；prepare未知不发信；activate未知可能新码有效而不恢复；finish/reap未知不重发。领取后退出用已领取任务及新Processor模拟，无重投，待expiry回收；SMTP超时后释放闩锁产生迟到完成仍不激活。DB开始/读事务完成故障、发信前/后改密/换邮箱/删账户均终止，整行无本接口写入；最终DB复核后并发版本变化允许旧版本ACTIVE存在，但绑定必须仍为旧版，不能重绑新版本假装有效。
- [ ] **步骤4：运行新接口全部定向测试至全绿。** `cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordReset*Test" --console=plain`，核对实际XML列出Reader/Rules/Identifiers/Snapshot/Admission/Queue/配置/邮件/Processor/Lifecycle/Service/Controller/真实链路类均运行。若Gradle模式未覆盖嵌套包，补明确--tests逐类运行，不以模式无命中当通过；所有必要容器零跳过。
- [ ] **步骤5：写部署说明。** docs/deployment/password-reset-requests.md固定Redis≥7及单实例/PERSISTENCE条件、TIME/PEXPIREAT/PEXPIRETIME能力、应用/Redis时钟同步，三项环境变量默认/正数、每实例与全局容量区别、有界维护/重启不接管/任务丢失与用户重新申请。NOTE-001如实说明历史空白无自助覆盖，NOTE-002说明主动附带Token的认证链行为；日志traceId及隐私，SMTP未知可能收到死码、激活未知旧码不保证保留，不给生产删键放行命令，不新增Actuator。
- [ ] **步骤6：冻结业务代码并完整回归。** `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`，保存退出码与JUnit XML，按tests−failures−errors−skipped统计通过，列类数及新增数；必要MySQL/Redis/MinIO0跳过。失败先定位修复，再做适用定向和所需完整验证，不删断言、不mock持久化成功。全量后只更新文档，若改业务代码须重新验证。
- [ ] **步骤7：检查并提交真实验证与材料。** `git diff --check`、`git status --short`含untracked、相对0705928的文件清单；确认V1—V8和既有脚本无差异、无新依赖/第二端点。分开中文提交测试与审核材料。docs/reviews/2026-11-04-password-reset-request-review.md按AGENTS第2/14节列权限/行为/全部文件/迁移无/API/实际命令与四项数量/基线/curl/风险/默认项；保留本设计轮次与NOTE原编号，设计accepted不作为实现accepted。
- [ ] **步骤8：交独立实现审核。** Codex不代替Claude结论；needs_revision逐项复核、原编号/分级/轮次，第三轮未通过或escalated依项目规则。未独立accepted不开始重置确认接口，推送遵从用户本任务授权，不挪用前一改邮箱接口推送许可。

## 设计反馈复核与处置

本设计审核第1/3轮accepted，零阻断项；以下保留原编号，不提升为隐含阻断或重置轮次。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断 | 成立。V1邮箱列为utf8mb4_0900_ai_ci，查询输入trim后等值定位；注册服务入库前trim+小写。原句允许首尾空格会误导覆盖范围 | 认可；仅存量非规范地址文档口径，不阻断方案 | 认可；保持索引查询，不加全表扫描或迁移 | 已修正设计§4；任务2/8固定SQL测试和API/部署说明 | 当前仅检查源码/设计，无新测试执行；NO PAD真实MySQL测试待实施验证 |
| NOTE-002 非阻断 | 成立。SecurityConfig公开放行不绕过Bearer处理，TokenVersionFilter可401 INVALID_TOKEN/TOKEN_REVOKED及503 AUTH_STATE_STORE_UNAVAILABLE；现有拒绝处理有403 FORBIDDEN | 认可；行为已定，表格补充 | 认可，明确403仅沿用而非新增正常业务分支 | 已补设计§8；任务7/8要求API/OpenAPI错误表及真实安全链测试 | 当前仅源码核实；无Token202/附带无效Token401的真实链路证据待实施 |

## 自查及交接

覆盖关系：设计§1/2→范围/全局约束；§3→任务1/7；§4→任务2/3/7；§5→任务3；§6→任务4/5/6；§7→任务4/5/6/8；§8→任务1/7/8；§9→文件表及各Interfaces；§10/11→任务7/8。Review Focus五项均有具名测试归属。

类型/协议核对：快照raw邮箱不被normalize覆盖；Task统一Optional账户而非不同响应；Admission为唯一deadline来源；Claim固定ownerNonce；Store仅prepare/activate false代表业务失效，未知均异常；finish永不删ACTIVE/latest；所有后续任务沿用同一模型签名，不包含找回确认的consume/fail方法。全量命令与容器要求未弱化，计划无占位实现或待定业务分支。

执行方式已确定为Codex本会话逐任务实现、Claude独立只读审核。计划编写完成后请用户审阅是否准确表达需求，等待确认才开始业务代码；本次仅文档，不运行或声称新增功能测试通过。
