# 密码重置确认接口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式沿用AGENTS.md：Codex本会话唯一实现，Claude独立只读审核，不派发实现子代理。上述执行子技能未列为可用，不声称已调用。当前仅编写计划，用户审阅确认后才实施。

**Goal:** 实现唯一公开POST /api/v1/users/password-resets，验证既有找回申请后一次性消费验证码、条件更新密码及认证版本，204成功并撤销目标账户全部旧登录。

**Architecture:** 严格三字段→IP额度→ACTIVE→账户额度→绑定/验证码/密码复核→新摘要→明确Redis消费→短MySQL CAS事务。无效申请及账户拒绝统一400并使用单次真实/伪比较；只有IP拒绝429，未知结果不放行、不恢复、不退款、不重试。

**Tech Stack:** Java21、Spring Boot4.1.1、Jakarta Validation、JPA/MySQL8.4、StringRedisTemplate/Lua、既有BCrypt、JUnit5/MockMvc/Testcontainers、springdoc、Gradle Wrapper。无新依赖、迁移、配置或后台任务。

**Spec:** [密码重置确认接口设计](../specs/2026-11-05-password-reset-confirmation-design.md)，原始c2530f3第1/3轮设计审核accepted；NOTE-001明确确认code前缀、NOTE-002记录残余降级场景，均在本文及设计反馈表落实。代码基线main @ 10e38d7；计划开始时HEAD=c2530f3，未推送。

## Global Constraints

- 唯一POST /api/v1/users/password-resets，公开、无旧密码/登录/auth_time；USER/ADMIN同规则，目标只从服务端申请绑定取得，忽略JWT主体作为目标的可能性。
- 仅challengeId/code/newPassword三个字符串；43字符规范Base64URL解码32字节且重编码相同；6位ASCII码保留零；密码8—72可打印ASCII、非全空格、原值不trim/截断/预哈希。
- 严格UTF-8 application/json、16384字节（尾随空白计入）、16385过读，拒绝重复/转义重复/未知/缺字段/嵌套/多根/错误类型，非支持媒体类型415。
- 原Redis准入后300秒deadline，SHA-256标识摘要，现有12字段challenge及ACTIVE指针结构不变；attempts 0—4，第5错原子失效；只兑换ACTIVE，不读取任务当凭据。
- 独立IP固定60秒10次，账户固定900秒10次；格式通过先扣IP，读到完整ACTIVE绑定再扣账户，拒绝当前层不增不续，前层及所有后续失败不退款。跨重发/设备/版本不清额度。
- 仅IP拒绝429 PASSWORD_RESET_CONFIRMATION_RATE_LIMITED，Retry-After为IP真实PTTL向上取整至少1秒；账户拒绝400 PASSWORD_RESET_VERIFICATION_FAILED，同title/detail/字段，无Retry-After/scope/剩余次数。
- IP获准后、真实验证码比较前确定普通失败：一次同成本伪比较且忽略结果；真实错码不再补伪比较。IP429与503不做伪比较，无sleep或无界后台填时，不承诺完全等时。
- 验证码正确且新旧密码等价：400 NEW_PASSWORD_SAME_AS_OLD，不计错/消费/续期，但扣本次额度。MAX版本及摘要损坏503，消费前拒绝。
- 完成新摘要计算并明确Redis消费成功才进入写事务；消费时仍有效即可，SQL提交可跨原deadline。消费或提交未知503，不恢复码、不退额度、不自动重试。
- CAS匹配prepared用户ID/原始邮箱NULL安全二进制/角色二进制/密码摘要二进制/版本及<Long.MAX_VALUE；只SET password_token、token_version+1、UTC updated_at；0行统一400，无404/409/401细分，不重新取预期重试。
- 204空正文/no-store，无Location/Token；旧Token及旧版本角色选择Ticket不能升级；不承诺取消已通过认证的在途请求。其他账户有效Token不改变重置目标或被连带撤销。
- 不改旧changePasswordIfSnapshotMatches契约、生产者Lua/schema、队列/邮件/任务生命周期/JWT策略/Actuator，不创建其他HTTP接口。日志、DTO、状态、异常链无秘密，保留traceId。
- 中文文档/提交，真实MySQL/Redis/MinIO；最终MINIO_INTEGRATION_TESTS=true cleanTest check零必要跳过，历史1718不冒充新结果。

## Review Focus

1. 跨多个新申请耗尽账户额度后，与不存在邮箱的假申请仍完全相同400且无等待字段；伪比较即使true也不授予权限：任务4/6。
2. 正码读出后非终态计错允许消费，第5错或新激活先发生必须拒绝，迟到旧操作不碰新状态：任务2/6。
3. 新旧密码等价、摘要计算故障、MAX版本在消费前退出；伪比较不误执行两次或误计错：任务1/4/6。
4. 实际消费丢回包零写事务；消费后真实回滚/提交丢确认不恢复、不重试：任务3/4/6。
5. 主动附带另一账户有效Token仍重置申请账户，目标管理员保持原登录角色选择机制；旧Token/Ticket不升级：任务5/6。

## 文件与数据协议

所有路径相对仓库根。表内同一目录后的文件名均位于该明确目录；任务以该表作为完整新增范围，不为搭建目录生成空类。

| 职责 | 新增文件 |
| --- | --- |
| HTTP | src/main/java/org/example/music/user/controller/PasswordResetConfirmationRequestReader.java、PasswordResetConfirmationController.java；src/main/java/org/example/music/user/dto/PasswordResetConfirmationRequest.java |
| 模型/规则 | src/main/java/org/example/music/user/domain/PasswordResetChallengeSnapshot.java、PasswordResetPreparedSnapshot.java、PasswordResetConfirmationRules.java |
| Redis | src/main/java/org/example/music/user/repository/PasswordResetConfirmationStore.java；src/main/resources/redis/password-reset-confirmation/common.lua、admit-ip.lua、admit-account.lua、read.lua、fail.lua、consume.lua |
| 协调 | src/main/java/org/example/music/user/service/PasswordResetCodeVerifier.java、PasswordResetConfirmationSnapshotReader.java、PasswordResetConfirmationTransactionService.java、PasswordResetConfirmationService.java |
| 异常 | src/main/java/org/example/music/user/exception/PasswordResetVerificationFailedException.java、PasswordResetConfirmationValidationException.java、PasswordResetConfirmationUnsupportedMediaTypeException.java、PasswordResetConfirmationRateLimitedException.java、PasswordResetConfirmationUnavailableException.java |
| 文档 | docs/api/user/confirm-password-reset.md、docs/deployment/password-reset-confirmations.md、docs/reviews/2026-11-05-password-reset-confirmation-review.md |

仅修改UserRepository追加投影/CAS、SecurityConfig追加单POST公开、GlobalExceptionHandler追加新类型映射。不改原NewPasswordSameAsOldException及其handler，不改发码/其他敏感接口文案。复用PasswordResetEmailRules、Identifiers、AccountSnapshot、Admission、PasswordResetDiagnostics及PasswordHasher，不复制邮箱规则或重构旧Store。

```java
record PasswordResetChallengeSnapshot(String digest, String emailDigest, String email,
    PasswordResetAdmission admission, PasswordResetAccountSnapshot account, String codeHash, int attempts) {}
record PasswordResetPreparedSnapshot(Integer userId, String rawEmail, UserRole role,
    long tokenVersion, String passwordHash) {}
record PasswordResetConfirmationRequest(String challengeId, String code, String newPassword) {}
```

全部敏感record覆写toString脱敏。account.email是生产者rawEmail，challenge.email是规范化投递邮箱，Admission保留原admittedAt/expiresAt，不能因读取重建期限。Prepared保留该次DB原始值及摘要，不由后续读取刷新。

### 任务1：严格请求、共享绑定规则及安全模型

**Files:** 新增HTTP表中RequestReader/DTO、模型/规则表3文件及5异常；测试src/test/java/org/example/music/user/controller/PasswordResetConfirmationRequestReaderTest.java、src/test/java/org/example/music/user/domain/PasswordResetConfirmationRulesTest.java。

**Interfaces:** Reader注入Validator，`PasswordResetConfirmationRequest read(HttpServletRequest)`；Rules注入既有EmailRules，`void validateChallenge(PasswordResetChallengeSnapshot)`、`boolean matches(PasswordResetChallengeSnapshot, PasswordResetAccountSnapshot)`、`void requirePasswordHash(String)`。RateLimited暴露`long retryAfterSeconds()`，Unavailable以既有Diagnostics.sanitized包装cause。VerificationFailed固定统一文案；无账户限流专用对外异常。

- [ ] **步骤1：写失败测试。** `should_preserve_code_zero_and_password_spaces()`、`should_accept_8_and_72_printable_ascii_but_reject_blank_or_unicode()`、`should_reject_duplicate_escaped_fields_noncanonical_id_and_extra_root()`、`should_enforce_16384_byte_budget_and_utf8_json()`；断言密码原值`assertEquals("  new-pass  ", result.newPassword())`，标识末位非规范但长度合法仍抛Validation；16384通过/16385拒绝。Rules验证摘要与邮箱关系、attempts 0—4、raw/规范化邮箱、角色/版本及脱敏toString；`assertFalse(rules.matches(challenge, changedVersion))`。
- [ ] **步骤2：观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.PasswordResetConfirmationRequestReaderTest" --tests "org.example.music.user.domain.PasswordResetConfirmationRulesTest" --console=plain`，期望缺实现编译失败。
- [ ] **步骤3：实现Reader/DTO/Rules/异常。** 三属性Bean Validation，code/newPassword为WRITE_ONLY Schema；复用Identifiers.isCanonicalDigest作格式/重编码检查；Rules不重复邮箱算法。账户缺行/邮箱解绑/合法版本或角色变化为不匹配，字段非法/摘要坏为Unavailable；BCrypt允许现有2a/2b/2y、60字符、成本4—31。原始raw邮箱不normalize覆盖，版本不经double。Reader只解析，拒绝异常不回显正文。
- [ ] **步骤4：同命令全绿。** 密码7/73、控制字符、中文/emoji/孤立代理，码数字类型/Unicode数字、缺/未知字段（含email/userId/confirmPassword），非法媒体类型415均覆盖；日志异常不含秘密，不写只为镜像实现的测试。
- [ ] **步骤5：检查并提交。** `git diff --check`，中文提交`feat: 增加密码重置确认严格请求与绑定规则`，仅暂存本任务文件。

### 任务2：独立额度与原子读/计错/消费协议

**Files:** 新增Store及6个Lua；测试src/test/java/org/example/music/user/repository/PasswordResetConfirmationStoreTest.java、src/test/java/org/example/music/user/PasswordResetConfirmationStoreIntegrationTest.java。

**Interfaces:** `PasswordResetConfirmationStore(StringRedisTemplate, PasswordResetConfirmationRules)`；`void admitIp(String ipDigest)`、`boolean admitAccount(Integer userId)`（false仅额度拒绝）、`Optional<PasswordResetChallengeSnapshot> read(String digest)`（empty仅正常缺失/失效）、`void recordFailure(PasswordResetChallengeSnapshot)`（旧归属正常失配不改状态）、`boolean consume(PasswordResetChallengeSnapshot)`（false仅正常失效）。损坏/不明回复统一Unavailable，绝不转成false/empty放行。

**固定Lua协议：** 额度新前缀`music:auth:password-reset-confirmation:`，ip:<digest>固定60000ms、user:<id>固定900000ms，HASH恰count/resetAtMs，Redis TIME+PEXPIRETIME校验，count规范1—10。IP拒绝回包`[1,waitSeconds]`，成功`[0,0]`，损坏`[2,0]`；账户同协议但拒绝仅返回false，不构造RateLimited。read先读既有challenge:<digest>，验证12字段，再用其中emailDigest核对既有ACTIVE指针。成功`[0,十二字段字符串]`，顺序schema/digest/emailDigest/email/admittedAt/expiresAt/userId/rawEmail/role/tokenVersion/codeHash/attempts；正常失效`[1]`、损坏`[2]`。

fail/consume的ARGV固定10项：digest/emailDigest/email/admittedAtMs/expiresAtMs/userId/rawEmail/role/tokenVersion/codeHash，不含旧attempts；KEYS为本challenge与对应ACTIVE，schema恒1。回包`[0]`成功、`[1]`旧归属/失效、`[2]`损坏；fail的0只表示本次计错成功，不把错误次数外泄。所有回复长度/类型/规范值Java二次检查。专用common自行支持900秒计数，不修改生产者common；可只读借鉴现有原语，不拼入含任务维护逻辑的大段脚本。

- [ ] **步骤1：写失败测试。** `should_limit_ip_to_ten_per_60_seconds_and_account_to_ten_per_900_seconds()`、`should_return_false_for_account_limit_without_rate_limited_exception()`、`should_keep_original_expiry_on_rejection()`。真实Redis断言第11次IP抛RateLimited且PTTL不延；`assertFalse(store.admitAccount(userId))`且计数仍10；Long.MAX_VALUE版本从生产者ACTIVATION读取仍原字符串。错类型/额外字段/缺TTL/未知回复503不清键。
- [ ] **步骤2：观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.repository.PasswordResetConfirmationStoreTest" --tests "org.example.music.user.PasswordResetConfirmationStoreIntegrationTest" --console=plain`，期望缺Store；容器用真实Redis8.10.2与既有生产者Store准备ACTIVE，不用mock生产者写成功。
- [ ] **步骤3：实现额度与read。** 先检验类型/全部字段再写，计数不退款/不续窗；ACTIVE与challenge均核对原deadline及PEXPIRETIME，emailDigest=SHA256规范邮箱，raw邮箱规范化等于email，版本保留十进制。正常不存在/过期/替换返回empty，活跃键矛盾/损坏503，不读取任务/伪造账户。
- [ ] **步骤4：实现fail/consume。** 对完整不可变字段及当前指针CAS，1—4错HINCRBY不改TTL，第5错原子删除本challenge和匹配ACTIVE；consume要求当前attempts<5且期限有效，最多一次删除两键。失配不动新challenge/pending/latest/ready/outstanding或任何quota，读到attempts0后变1仍可消费。
- [ ] **步骤5：并发/损坏全绿。** `should_consume_once_across_two_store_instances()`、`should_allow_correct_consume_after_nonterminal_failure()`、`should_reject_correct_after_fifth_failure()`、`should_not_touch_new_active_when_old_failure_or_consume_arrives()`。还覆盖同码不同digest、原TTL、没有ACTIVE的任务不能兑换、记录/指针半损坏503、假回复与命令执行后丢回复不自动重试。另以结构合法、接近到期的旧账户窗口夹具验证：拒绝不续窗、按原PEXPIREAT自然过期后可重新准入且count=1；以有界定时闩锁等待已知期限，不修改生产窗口算法、不用sleep猜竞态。用闩锁控制，不sleep猜顺序。
- [ ] **步骤6：检查并提交。** `feat: 增加密码重置确认限流与一次性消费`，生产者脚本和12字段schema零变更。

### 任务3：短只读快照与条件改密事务

**Files:** 新增SnapshotReader/TransactionService；修改UserRepository仅追加prepared投影和新CAS；测试src/test/java/org/example/music/user/service/PasswordResetConfirmationSnapshotReaderTest.java、PasswordResetConfirmationTransactionServiceTest.java，src/test/java/org/example/music/user/PasswordResetConfirmationTransactionIntegrationTest.java。

**Interfaces:** Reader注入Repository/PlatformTransactionManager/Rules，`boolean isCurrent(PasswordResetChallengeSnapshot)`（复用findPasswordResetSnapshotById，不读密码）、`Optional<PasswordResetPreparedSnapshot> prepare(PasswordResetChallengeSnapshot)`；Repo新增`Optional<PasswordResetPreparedSnapshot> findPasswordResetPreparedSnapshot(Integer userId)`及`int resetPasswordIfSnapshotMatches(Integer userId, String previousEmail, String previousRole, String previousHash, long expectedVersion, String newHash)`，全部@Param；事务Service仅注入Repo/Manager，`void update(PasswordResetPreparedSnapshot expected, String newHash)`，不接收Actor/Session。

- [ ] **步骤1：写失败测试。** `should_preserve_raw_email_and_hash_in_prepared_snapshot()`，`assertEquals("ALICE@example.com", prepared.rawEmail())`；`should_map_missing_or_changed_binding_to_empty_without_rebinding()`；DB开始/读完成失败Unavailable；坏hash不误作同密码检查false。真实MySQL `should_update_only_hash_version_and_utc_timestamp()`、`should_reject_binary_email_role_hash_or_version_change()`，更新前后按列核对用户名/邮箱/角色/创建/登录时间不变。
- [ ] **步骤2：观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.PasswordResetConfirmationSnapshotReaderTest" --tests "org.example.music.user.service.PasswordResetConfirmationTransactionServiceTest" --tests "org.example.music.user.PasswordResetConfirmationTransactionIntegrationTest" --console=plain`，真实MySQL8.4，禁止H2。
- [ ] **步骤3：实现Reader及Repo。** 两次只读TransactionTemplate外侧覆盖启动/读取/结束错误；prepare将Prepared的userId/rawEmail/role/tokenVersion构造为既有AccountSnapshot交Rules.matches复核，绑定符合才验hash，不改变prepared。新原生SQL：ID、`(CAST(email AS BINARY) <=> CAST(:previousEmail AS BINARY))`、`CAST(user_role AS BINARY)=CAST(:previousRole AS BINARY)`、`CAST(password_token AS BINARY)=CAST(:previousHash AS BINARY)`、token_version=:expectedVersion及<9223372036854775807；SET password_token、token_version+1、updated_at=UTC_TIMESTAMP(6)，@Modifying(clearAutomatically=true)。不修改旧改密CAS签名，不save Entity。
- [ ] **步骤4：实现事务Service。** 写事务只执行一次CAS，1行提交、0行抛统一VerificationFailed，无诊断回读/重试；非1/0安全IllegalStateException并回滚。DataAccessException/TransactionException在事务外Unavailable，MAX防御拒绝，不碰Redis/BCrypt。Service层会在消费前先检查MAX，此处是额外防御。
- [ ] **步骤5：同命令全绿。** 真实binary大小写变化/角色/摘要/版本/删除CAS0，非目标账户不变；并发仅改名仍成功且新名保留；MAX无溢出，真实SQL后回滚整行不变、真实commit后抛TransactionSystemException为Unavailable但行已更新。通过实际事务manager故障夹具，不对Spring Data抽象查询callRealMethod冒充真实SQL。
- [ ] **步骤6：检查并提交。** `feat: 增加密码重置快照与条件事务更新`。

### 任务4：同成本伪比较及重置协调

**Files:** 新增CodeVerifier/ConfirmationService；测试src/test/java/org/example/music/user/service/PasswordResetCodeVerifierTest.java、PasswordResetConfirmationServiceTest.java。

**Interfaces:** Verifier注入既有PasswordHasher及Rules，初始化`hasher.hash(PasswordResetIdentifiers.generate())`为私有内存dummyHash，经Rules验证；`boolean matches(String code, String encoded)`真实比较；`void reject(String code)`始终抛VerificationFailed，只做一次hasher.matches(code,dummyHash)并忽略返回值，异常转Unavailable。Service注入Store/Reader/Rules/Verifier/Hasher/TransactionService，`void reset(PasswordResetConfirmationRequest request, String clientIp)`。构造器无额外配置/硬编码伪密码。

- [ ] **步骤1：写失败测试。** `should_reject_even_when_dummy_comparison_returns_true()`，`assertThrows(PasswordResetVerificationFailedException.class, () -> verifier.reject("012345"))`；清除初始化hash调用记录后验证每次reject只有一次matches、零真实消费。`should_apply_one_comparison_for_absent_challenge_account_denial_or_stale_binding()`；IP429/503零伪比较，真实错码不额外reject。`should_keep_code_when_password_is_equivalent_or_hashing_fails_or_version_is_max()`；`should_not_enter_write_transaction_when_consume_is_unknown_or_false()`。
- [ ] **步骤2：观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.PasswordResetCodeVerifierTest" --tests "org.example.music.user.service.PasswordResetConfirmationServiceTest" --console=plain`。
- [ ] **步骤3：实现明确顺序。** IP→read，empty调用reject；账户false调用reject；isCurrent false调用reject；真实code比较false调用recordFailure后直接抛VerificationFailed，不再reject；prepare empty直接统一失败（已通过code）；新密码matches旧hash则抛既有NewPasswordSameAsOldException；MAX拒绝；hash新密码并验证生成摘要；consume false直接统一失败，明确true才updater.update(prepared,newHash)。不在通用catch里盲目reject，避免重复BCrypt；已获验证码证明后的密码运算不补伪比较。
- [ ] **步骤4：单测同命令全绿。** inOrder锁定准入/绑定/比较/prepare/新摘要/消费/更新；所有早退零更新；账户拒绝400且无RateLimited、无计错或消费；等价密码保留原码、坏新摘要不提前消费；消费后updater异常不恢复、不退款、不重试。hash/matches未知异常链脱敏，DTO/快照不泄密。Verifier用真实BCrypt另验dummy成本与生产者同源、并发安全，不写时钟性能断言代替调用/状态证据。
- [ ] **步骤5：检查并提交。** `feat: 协调密码重置确认与统一失败比较`。

### 任务5：公开Controller、错误映射与API文档

**Files:** 新增Controller；修改SecurityConfig仅追加本POST、GlobalExceptionHandler仅追加5类型映射；新增API文档；测试src/test/java/org/example/music/user/controller/PasswordResetConfirmationControllerTest.java。

**Interfaces:** Controller注入Service/Reader/ClientIpResolver，读取HttpServletRequest、reader.read并传resolve出的remoteAddr，不收Principal/Session、不用JWT主体定位，调用reset后204/no-store。新异常中只有IP RateLimited携带Retry-After；VerificationFailed固定title“密码重置未完成”、detail“验证码或找回申请无效，请检查或重新申请”，账户拒绝不自带任何可识别字段。

- [ ] **步骤1：写失败HTTP测试。** 匿名合法输入204，`assertEquals("", response.getContentAsString())`、无Location/Token、no-store；400 VALIDATION_FAILED/VERIFICATION_FAILED/NEW_PASSWORD_SAME_AS_OLD、415、429、503、500的code/type/正文脱敏，`PASSWORD_RESET_CONFIRMATION_RATE_LIMITED`仅IP，错误表不误用REQUEST或旧短前缀。对账户拒绝断言400与普通VerificationFailed body同一契约且无Retry-After；测试导入真实SecurityConfig/Filter及Reader，不关闭认证来伪造公开可用。
- [ ] **步骤2：观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.PasswordResetConfirmationControllerTest" --console=plain`。
- [ ] **步骤3：实现Controller/配置/handler。** 新建@Tag/@Operation、严格请求schema三属性、204/400/401/403/415/429/500/503共8响应声明，204无content；401/403只是主动Bearer的既有链路，不制造正常角色403；503同时说明附带Token时AUTH_STATE_STORE_UNAVAILABLE。专用Validation/415不复用旧发码异常而改其title；原NewPasswordSameAsOld handler不变，验证响应含禁止缓存语义。
- [ ] **步骤4：API文档并同命令全绿。** docs/api/user/confirm-password-reset.md含权限/原值密码/限流与伪比较/204及重新登录/完整错误表/示例/可执行文件体curl。按NOTE-001列对照：发码PASSWORD_RESET_REQUEST_*，确认PASSWORD_RESET_CONFIRMATION_*；账户拒绝只用PASSWORD_RESET_VERIFICATION_FAILED，不能提示等待时间；不宣称完全等时或失败一定未改密码。
- [ ] **步骤5：检查并提交。** `feat: 暴露密码重置确认接口`。

### 任务6：真实链路、反枚举、并发及故障验证

**Files:** 新增src/test/java/org/example/music/user/PasswordResetConfirmationIntegrationSupport.java、PasswordResetConfirmationIntegrationTest.java、PasswordResetConfirmationConcurrencyIntegrationTest.java、PasswordResetConfirmationFailureIntegrationTest.java。不修改旧发码测试/夹具消除失败。

**Interfaces:** 新抽象夹具@SpringBootTest/MockMvc/真实MySQL8.4+Redis8.10.2，具体类显式Docker条件、@DirtiesContext。生产者真实POST申请→真实Processor领取/发送/激活，SMTP可控捕获，后台沿用test profile关闭自动调度并显式驱动；确认Store/事务/JWT安全链均真实，spy仅用于实际执行前后控制故障/闩锁。原始用户行比较在重新登录前完成，避免把登录更新时间误认本接口覆盖。

- [ ] **步骤1：真实成功及认证用例。** `should_reset_user_and_admin_and_revoke_only_target_old_logins()`：204、真实BCrypt新密码匹配、旧密码不能登录，用户名/邮箱新密码可登录，ADMIN按ROLE_SELECTION完成原流程，原目标USER/ADMIN登录角色Token均401。`should_ignore_other_valid_token_as_target_identity()`：用A有效Token配B合法申请重置B，A行/Token有效性不变；主动签名错/过期/撤销Token401且零额度/消费，无Token正常。旧角色Ticket/旧版本校验结果不能签成新版本有效登录。
- [ ] **步骤2：真实额度及失败统一。** `should_hide_exhausted_account_across_new_requests_like_missing_email()`通过真实生产者生成多次申请，每次五错，保留账户900秒额度，测试夹具仅清冷却/模拟IP窗口结束以免等一分钟；第3个有效申请在账户拒绝时与无账户假申请同400/type/title/detail/无Retry-After，attempts不增加且未消费。IP第11次单独429+正确PTTL，无账户不造用户键；伪比较真实Hasher spy核对每普通预验证码失败一次matches，伪结果true也不得消费，IP拒绝零比较。
- [ ] **步骤3：真实重发与竞态。** `should_reject_old_consume_if_new_activation_wins()`；`should_finish_old_sql_and_keep_new_active_if_old_consume_wins()`，新ACTIVE保留旧版本且后续不可兑换；新任务仅pending旧码仍可用。六路正确提交最多一次CAS；第5错先完成正确失败、非终态错码后仍可消费、旧错/消费迟到不能改新状态；角色/原始邮箱/摘要/版本变化及删除拒绝，只改用户名不覆盖。使用闩锁与两个真实Store实例，无sleep猜顺序。
- [ ] **步骤4：真实存储故障。** 实际admit/recordFailure/consume执行后抛丢回复；消费未知断言更新方法从未调用/零写事务，可能已删码。消费后事务开始失败、实际SQL回滚行不变、实际commit后抛TransactionSystemException则503但密码/版本已改；全过程不恢复码、不退款、不自动CAS重试。实际原deadline前消费成功后跨窗提交仍允许；过期发生在BCrypt/新摘要计算之后、consume之前则400零写入。丢弃204后重复旧申请400，恢复有效登录核实状态。
- [ ] **步骤5：契约/回归并定向全绿。** 用真实Token读受保护/v3/api-docs，核对8响应、三属性/writeOnly/密码边界/204无JSON。`cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordResetConfirmation*Test" --tests "org.example.music.user.service.PasswordResetCodeVerifierTest" --console=plain`；检查XML实际包含Reader/Rules/Store/快照/事务/Verifier/Service/Controller/真实链路，必要容器0跳过。发码schema/队列成员/其他用途键/原发码额度在确认前后按相关场景核对未误删。
- [ ] **步骤6：检查并提交。** `test: 验证密码重置确认真实链路与故障边界`。只修复范围内实际失败，不删除断言或mock提交成功，不将准备过的本地数据当真实链路证据。

### 任务7：部署说明、完整基准与审核交接

**Files:** 新增docs/deployment/password-reset-confirmations.md、docs/reviews/2026-11-05-password-reset-confirmation-review.md；更新本计划/设计执行状态。文档不编造单元测试，验证以真实命令、范围和材料核对为准。

- [ ] **步骤1：部署说明及NOTE-002落地。** 保持单Redis/Redis≥7、持久化/时钟/全实例认证版本生效前提；确认不动生产者配置。明确持有真实challengeId者可消耗账户额度，合法用户在当前原900秒窗口剩余时间内统一400，重新发码不会恢复；额度在DB绑定复核前扣取，随后绑定失效也不退款，不能将验证码五错上限误当作账户额度计数上限；单个窗口上限900秒，重复攻击可能在后续窗口再次触发，受发码60秒冷却/每UTC日10次约束，不宣称攻击永久不可重复。原窗口TTL自然恢复，无人工删键清额/新恢复接口、不改变额度；日志脱敏、IP429与账户400区别仅对运维说明，不向客户端回传账户层原因。
- [ ] **步骤2：代码冻结后完整验证。** `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`，保存源代码commit/退出码/BUILD状态/JUnit XML，逐类汇总通过=tests−failures−errors−skipped及新增类数；MySQL/Redis/MinIO必要测试零跳过。失败先定位真实原因，修复及适用定向后重做所需完整验证；全量后只改文档，业务改动则重新验证，历史1718不冒充本接口结果。
- [ ] **步骤3：准备完整同任务材料。** 相对10e38d7列含untracked全部文件，基线/Method+Path/公开权限/声称行为/无迁移/API/实际测试四项数量/配置默认/风险/curl齐全。NOTE-001/002保留原编号、分级及三项判断，设计accepted不冒充实现accepted；表明NOTE-002不构成新阻断。`git diff --check`、状态清单、迁移/依赖/生产者脚本及公开路径差异核对，中文提交文档和验证记录。
- [ ] **步骤4：独立实现审核。** 由Claude只读复核；Codex不代替其结论。needs_revision按AGENTS原编号/分级/轮次核实，第三轮未过或escalated遵规则，不开启新循环。未独立accepted不开始下一接口，推送遵从本任务用户授权，不挪用此前发码推送许可。

## 设计意见复核与计划自查

NOTE-001：认可事实/非阻断分级/确认前缀方向；既有GlobalExceptionHandler发码code为PASSWORD_RESET_REQUEST_*，本接口尚未上线，改为CONFIRMATION前缀不破坏已有客户端。设计§4/§9/§14及本计划/HTTP测试/API对照表一致，实际验证待实现，不声称已关闭。

NOTE-002：认可事实/非阻断分级/部署记录方向；公开发码可获标识、用户额度跨申请失败不退，是当前防护取舍的残余可用性风险。按任务6/7验证不续窗及自然恢复、记录反复攻击可能再次触发，不改额度、不开放绕过或人为重置。完整六列表处置沿用设计§14及最终审核材料。

覆盖映射：设计§1/2→范围/全局约束；§3→任务1/5/6；§4→任务2/4/6/7；§5→任务1/2/6；§6→任务3/4；§7→任务3/6；§8→任务2/6；§9→任务5/6；§10→文件/Interfaces；§11/12→任务6/7；§13/14→自查及意见处置。Review Focus五项均有具名测试归属。

类型核对：Challenge使用现有Admission/AccountSnapshot；Prepared只有rawEmail/role/version/hash预期；Store的false/empty只代表已知正常拒绝/失效，未知都异常；仅IP产生RateLimited，reject始终失败；code验证后路径不再reject；update不接JWT身份、不重取预期。所有参数/RESP顺序已确定，无占位实现/未定业务，不写方法体代替后续实现。

当前只修改设计与计划文档，未运行或声称新功能测试通过。计划完成自查后交用户审阅；执行方式已确定为Codex本会话逐任务实现、Claude独立只读审核，确认计划前不写业务代码。
