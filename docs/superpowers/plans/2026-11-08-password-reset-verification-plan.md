# 找回密码独立验证接口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式沿用AGENTS.md，由Codex本会话唯一实现，Claude独立只读审核。上述执行子技能未列为可用，按本会话原生流程逐任务执行。用户已确认开始，任务1至6已实施并定向验证通过，源代码/测试冻结be2f091，完整验证168类1926通过/0失败/0错误/0跳过，第1/3轮独立实现审核accepted，零阻断项。

**Goal:** 实现唯一POST /api/v1/users/password-reset-verifications，将有效邮箱验证码原子兑换为该账户最新一张300秒一次性resetTicket，用户表只读。

**Architecture:** 严格两字段→共享码校验额度→ACTIVE/绑定/BCrypt→第二次只读绑定复核→旧票完整快照→单Lua兑换。旧组合提交入口继续使用原契约和同一组码校验预算，第三步改造独立交付。

**Tech Stack:** Java21、Spring Boot4.1.1、Jakarta Validation、JPA/MySQL8.4、StringRedisTemplate/Lua、既有BCrypt、JUnit5/MockMvc/Testcontainers、springdoc、Gradle Wrapper。无新增依赖、数据库迁移、配置项或后台任务。

**Spec:** [独立验证码验证接口设计](../specs/2026-11-08-password-reset-verification-design.md)，原始1675fae已获第1/3轮书面设计审核accepted，零阻断项。代码基线main @ da146b2，计划开始时HEAD=1675fae。设计审核NOTE-001采纳接口/键前缀/错误码三方对照，落实于任务5/7。

## Global Constraints

- 唯一新增POST /api/v1/users/password-reset-verifications。公开、USER/ADMIN同规则，目标仅取ACTIVE绑定，主动他账户有效Token不能改变目标，主动无效Bearer沿用认证链。
- 请求恰challengeId/code两个字符串，43字符规范Base64URL解码32字节且重编码相同、六位ASCII数字保留零；严格UTF-8 application/json，16384字节预算含尾随空白，16385过读。缺/未知/重复或转义重复/嵌套/多根/错误类型400，媒体类型415。
- 成功200恰resetTicket/expiresAt、no-store，票为独立32字节随机数，前端内存暂存。Redis只存SHA-256摘要及绑定，所有敏感DTO/快照toString及异常链脱敏。
- 共享现有music:auth:password-reset-confirmation:ip:<digest>/user:<id>，IP固定60秒10次、账户固定900秒10次，旧组合入口与新第二步合计，不改键/算法/窗口/TTL、不退款、不续窗。仅IP429+Retry-After，账户统一400无等待/拒绝层字段。
- 复用一次真/伪验证码比较矩阵，IP拒绝及503不补比较，正确码后失败不额外reject。验证码原准入300秒、第五错失效、消费CAS排除旧attempts精确值。
- 两次DB最小账户投影只读，均使用isCurrent。第二步不读密码投影、不调用旧consume、不更新用户/版本、不签发登录Token或角色票。BCrypt和Redis在事务外。
- 单Lua重新核验完整验证码、旧票预期、候选碰撞及低版本保护，明确成功才返回票。票期限为Redis TIME签发起300000毫秒，每账户最新一张；发码不影响已签发票。
- ticket schema=1恰10字段及同用户STRING指针，两个PEXPIRETIME均等于expiresAt。版本字符串比较长度/字典序，不经Lua浮点；原始邮箱不改为规范邮箱。
- 已知失效/并发冲突400；损坏/未知503且不删键放行、不自动重试/恢复/退款。未知兑换未执行时旧码可能仍可成功，已执行时旧码失效，恢复采用重新申请。
- 阶段一复用共享组件但新边界将CONFIRMATION_*异常转为VERIFICATION_*，旧调用方映射保留。当前用户表只读，现有生产者12字段/schema/队列、旧组合接口及MySQL CAS均保留。
- 第三步字段改造、票读取消费、completion额度、密码更新及其故障测试另行任务。本轮只固定schema兼容约束，前端待两个接口均审核通过后整体切换。
- 中文文档/提交，真实MySQL/Redis，完整MINIO_INTEGRATION_TESTS=true cleanTest check零必要跳过。既有158类1825项是基线，设计审核check全部UP-TO-DATE，均不冒充新功能结果。

## Review Focus

1. 旧入口与新第二步跨申请合计耗额，HTTP错误码不同但底层计数/原窗口一致，任务4/6。
2. 旧票读后双键自然到期可继续兑换，只有一键消失为503，旧票被新签发替换为400且不消费当前验证码，任务2/3。
3. 更高版本票保护使用超过2^53的真实数值，候选碰撞或旧票损坏必须在首次写入前退出，任务3/6。
4. 兑换未执行与实际执行后丢回复分别验证，只有明确成功返回新票，重复不重放/续期/自动签新票，任务3/4/6。
5. 证明通过后的第二次绑定变化不补伪比较，200前后用户行、旧登录与角色票均保留原状态，任务4/6。

## 文件与接口协议

下表路径均相对仓库根。同一行的文件名位于列明目录，任务按文件名引用本表，只有当前任务所需文件才创建。

| 目录 | 新增文件 |
| --- | --- |
| src/main/java/org/example/music/user/controller | PasswordResetVerificationRequestReader.java、PasswordResetVerificationController.java |
| src/main/java/org/example/music/user/dto | PasswordResetVerificationRequest.java、PasswordResetVerificationResponse.java |
| src/main/java/org/example/music/user/domain | PasswordResetTicketPeriod.java、PasswordResetTicketSnapshot.java、PasswordResetTicketRules.java |
| src/main/java/org/example/music/user/exception | PasswordResetVerificationRateLimitedException.java、PasswordResetVerificationUnavailableException.java |
| src/main/java/org/example/music/user/repository | PasswordResetTicketStore.java |
| src/main/java/org/example/music/user/service | PasswordResetVerificationService.java |
| src/main/resources/redis/password-reset-verification | common.lua、read-current.lua、exchange.lua |
| docs/api/user | verify-password-reset.md |
| docs/deployment | password-reset-verifications.md |
| docs/reviews | 2026-11-08-password-reset-verification-review.md |

已有生产文件仅修改src/main/java/org/example/music/config/SecurityConfig.java与src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java，分别追加单POST与两个异常映射。共享Store/Verifier/Reader、UserRepository、生产者及旧Lua源文件只读复用。

```java
record PasswordResetVerificationRequest(String challengeId, String code) {}
record PasswordResetVerificationResponse(String resetTicket, Instant expiresAt) {}
record PasswordResetTicketPeriod(Instant issuedAt, Instant expiresAt) {}
record PasswordResetTicketSnapshot(String digest, String emailDigest, String email,
    PasswordResetAccountSnapshot account, PasswordResetTicketPeriod period) {}
```

AccountSnapshot.email是rawEmail，TicketSnapshot.email为规范化邮箱。四个record覆盖toString脱敏。新TicketRules注入PasswordResetEmailRules，`void validate(PasswordResetTicketSnapshot)`，校验整个记录并将内部非法值转换为本步Unavailable。Period及Snapshot均用于本轮实际读取/签发，第三步专用消费成员暂不创建。

### Redis回复与参数

TicketStore注入StringRedisTemplate、PasswordResetTicketRules及既有PasswordResetConfirmationRules。脚本文本顺序为现有redis/password-reset-confirmation/common.lua、新redis/password-reset-verification/common.lua、具体脚本，沿用已有加载方式并避开局部函数重名。新脚本调用既有challenge/active/canonical/digest等只读原语，旧mutation的10参数/2键契约不改。

`Optional<PasswordResetTicketSnapshot> readCurrent(Integer userId)`执行read-current，KEYS恰用户票指针一键，ARGV恰规范userId。成功回复`[0, 十个字段字符串]`，顺序schema/digest/userId/email/emailDigest/rawEmail/role/tokenVersion/issuedAt/expiresAt；无当前指针`[1]`；损坏`[2]`。Java严格检查回复长度/Long状态/String载荷、schema=1、ID/枚举/版本/时间及完整规则。

`Optional<PasswordResetTicketPeriod> exchange(PasswordResetChallengeSnapshot expected, Optional<PasswordResetTicketSnapshot> previous, String candidateDigest)`执行exchange，KEYS固定4项，依次为challenge键、该邮箱ACTIVE、该用户票active、候选ticket键。ARGV固定22项，前10项沿用旧不可变challenge参数顺序digest/emailDigest/email/admittedAtMs/expiresAtMs/userId/rawEmail/role/tokenVersion/codeHash，第11项candidateDigest、第12项previous标记0或1、第13至22项为上述旧票10字段字符串；无旧票时全部空串。旧票记录键由已校验摘要在脚本内构造，符合单Redis前提。

兑换成功`[0,issuedAtMs,expiresAtMs]`，时间均Long且间隔300000；已知失效`[1,0,0]`；损坏`[2,0,0]`。任何null/未知/错型/额外数据/不合法成功时间为Unavailable，零自动重试。Java仅在明确成功后以候选及原绑定构造Snapshot再validate，返回Period。Lua计算时间并设置两个PEXPIREAT，HASH时间字段使用string.format('%.0f',value)保存规范十进制。

无活动指针时按“无当前票”读取，不扫描随机摘要键空间。没有指针定位的孤立记录不能授予资格，按原TTL保留；已有previous快照时兑换可检查该确定摘要的双键，已知一键消失503、两键同时消失允许继续。票消费方后续必须同时验证摘要记录及当前指针。这是可定位状态的校验边界，任务2/3锁定测试。

### 任务1 严格输入、凭据模型与规则

**Files:** 新增RequestReader、两个DTO、两个票record、TicketRules及两个异常。测试src/test/java/org/example/music/user/controller/PasswordResetVerificationRequestReaderTest.java、src/test/java/org/example/music/user/domain/PasswordResetTicketRulesTest.java。

**Interfaces:** Reader(Validator)，`PasswordResetVerificationRequest read(HttpServletRequest)`；TicketRules(PasswordResetEmailRules)，`void validate(PasswordResetTicketSnapshot)`；RateLimited(long retryAfterSeconds)暴露`long retryAfterSeconds()`；Unavailable(Throwable)通过PasswordResetDiagnostics.sanitized构造安全cause。请求校验与415复用已有ConfirmationValidation/UnsupportedMediaTypeException映射。

- [x] **步骤1 写失败测试。** `should_preserve_leading_zero_and_reject_noncanonical_identifier()`，`assertEquals("012345",reader.read(valid).code())`；`should_enforce_two_fields_utf8_and_16384_bytes()`，16384可读/16385抛Validation，覆盖字段顺序、缺/null、数字/数组/嵌套、转义重复、未知newPassword/email/userId/role、多根、代理和非法媒体类型。Rules测试`should_preserve_raw_email_long_max_and_redact_ticket()`、`should_reject_invalid_email_digest_role_period_or_version()`，0/Long.MAX_VALUE合法，负版本/非规范邮箱/错误摘要/时差非300000/非法ID均Unavailable。
- [x] **步骤2 观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.PasswordResetVerificationRequestReaderTest" --tests "org.example.music.user.domain.PasswordResetTicketRulesTest" --console=plain`，期望缺实现编译失败。
- [x] **步骤3 实现模型与Reader。** 两字段Bean Validation和Identifiers.isCanonicalDigest，code为WRITE_ONLY Schema；响应票正常序列化但toString脱敏。严格Reader只改本接口字段集，复用原预算/UTF-8/流解析方式及Diagnostics。Rules复用EmailRules规范化算法，时间非负、精度为整毫秒且在Lua精确毫秒范围，expiry=issued+300000；未来性/实时到期由Redis验证，不用应用时钟重新裁定。
- [x] **步骤4 同命令全绿。** 验证字段边界及所有record/异常链无秘密，RateLimited可供header取秒数。无新业务默认或公共Parser重构。
- [x] **步骤5 检查并提交。** git diff --check，精确暂存本任务文件，中文提交`feat: 增加找回密码独立验证请求与票规则`。

### 任务2 当前重置票原子读取

**Files:** 新增TicketStore及新common.lua/read-current.lua；测试src/test/java/org/example/music/user/repository/PasswordResetTicketStoreTest.java、src/test/java/org/example/music/user/PasswordResetTicketReadIntegrationTest.java。

**Interfaces:** TicketStore(StringRedisTemplate,PasswordResetTicketRules,PasswordResetConfirmationRules)，本任务先实现`readCurrent(Integer userId)`及共享脚本加载/严格解码。读取模型与协议见上节。

- [x] **步骤1 写失败测试。** `should_read_exact_ten_fields_with_matching_pointer_and_expiry()`；真实Redis夹具写规范10字段HASH和用户STRING指针，`assertEquals(Long.MAX_VALUE,result.account().tokenVersion())`。`should_reject_missing_record_extra_field_owner_mismatch_or_ttl_damage()`分别覆盖指针存在而记录缺失、错类型、schema、字段、规范整数、角色、邮箱摘要关联、rawEmail关系和TTL损坏，断言Unavailable且原键未删。
- [x] **步骤2 观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.repository.PasswordResetTicketStoreTest" --tests "org.example.music.user.PasswordResetTicketReadIntegrationTest" --console=plain`，缺Store编译失败。容器Redis8.10.2，采用现有Lettuce连接生命周期规范，独立测试夹具，不修改旧测试。
- [x] **步骤3 实现只读协议。** 单Lua读指针/记录/Redis TIME及两个PEXPIRETIME，发现指针缺失返回empty，合法存在才返回十字段。Java再校验规范化/摘要及模型。当前指针定位不到记录为503，无扫描/删除/修复。只读异常和不明回复统一本步Unavailable。
- [x] **步骤4 同命令全绿。** `should_return_empty_when_no_pointer_or_both_keys_expired()`以合法近到期夹具、有界定时闩锁等待自然过期；`should_leave_unreachable_orphan_untouched_when_pointer_is_absent()`断言empty、孤立记录仍在且TTL不变。unit假回复验证状态/长度/类型/schema/数字越界，实际调用一次不重试，响应异常不含数据。
- [x] **步骤5 检查并提交。** `feat: 增加重置凭据原子读取与完整校验`，仅当前读取职责，无票消费代码。

### 任务3 完整归属的验证码兑换

**Files:** TicketStore追加exchange、common.lua追加实际所需校验，新exchange.lua；追加TicketStoreTest，新增src/test/java/org/example/music/user/PasswordResetTicketExchangeIntegrationTest.java。

**Interfaces:** `Optional<PasswordResetTicketPeriod> exchange(PasswordResetChallengeSnapshot, Optional<PasswordResetTicketSnapshot>, String)`，空仅已知失效，损坏/未知总是异常。完整22参数/4键及三种回复按前述固定协议。

- [x] **步骤1 写失败测试。** `should_exchange_real_active_code_for_one_new_ticket_without_extending_code_deadline()`，真实旧生产者admit/enqueue/claim/prepare/activate准备ACTIVE；成功票十字段、两键PEXPIRETIME一致、票expiry=Redis签发+300000、验证码双键删除，quota/pending/latest/队列及他用户状态按场景核对不受影响。`should_replace_only_expected_previous_ticket()`断言新指针匹配、旧票删除、他用户票保留。
- [x] **步骤2 观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.repository.PasswordResetTicketStoreTest" --tests "org.example.music.user.PasswordResetTicketExchangeIntegrationTest" --console=plain`，期望缺exchange方法。
- [x] **步骤3 实现写前完整预检与单次兑换。** 校验挑战snapshot/候选/previous所属ID，Lua重验当前challenge/ACTIVE、不可变参数、attempts<5及原deadline；校验候选键不存在、旧票结构与全部预期。仅针对当前实际存在的票以规范串比较版本，较高版本票使迟到低版本兑换失效；previous双键已消失时按无票处理。旧票被替换返回失效；有previous时旧双键同时缺失允许继续、单键缺失503；无previous却出现新指针为失效。全部预检后单Lua写新票/指针与同一绝对期限、删确认归属旧票及原验证码。脚本使用Redis TIME，保留旧mutation约定。
- [x] **步骤4 验证并发/预检全绿。** `should_exchange_once_across_two_store_instances()`用闩锁并发至少两路，恰一个period；`should_allow_nonterminal_attempt_change_but_reject_fifth_failure()`；`should_reject_candidate_collision_or_damaged_old_ticket_before_any_write()`；`should_keep_higher_version_ticket_using_values_above_double_precision()`用9007199254740993/9007199254740992及Long.MAX_VALUE验证字典序。预检失败保留挑战/旧票/候选，无额外生成或重试。
- [x] **步骤5 验证旧状态与未知结果。** `should_allow_expected_old_pair_to_expire_but_reject_partial_loss()`、`should_reject_changed_previous_pointer_without_consuming_current_code()`；无指针未知孤立旧记录不会被扫描或删除，新票只能依真实验证码产生。`should_distinguish_before_execution_failure_from_lost_reply_after_real_exchange()`以spy实际Redis execute前/后抛错，两种均Unavailable、零重试，实际执行后码/票推进仍核实，无假持久化成功。过期发生于比较后/兑换前返回empty、票期限独立300秒；错误Lua回包不得解读为成功。
- [x] **步骤6 检查并提交。** `feat: 原子兑换邮箱验证码并替换重置凭据`，旧生产者及confirmation Lua源文件零改动。

### 任务4 验证编排与共享异常转换

**Files:** 新增VerificationService；测试src/test/java/org/example/music/user/service/PasswordResetVerificationServiceTest.java。

**Interfaces:** Service构造器注入PasswordResetConfirmationStore、PasswordResetCodeVerifier、PasswordResetConfirmationSnapshotReader、PasswordResetTicketStore；`PasswordResetVerificationResponse verify(PasswordResetVerificationRequest request,String clientIp)`。请求与两个成功字段类型按任务1。

- [x] **步骤1 写失败测试。** `should_reuse_quota_and_verify_binding_twice_before_exchange()`以inOrder验证IP/read/account/isCurrent/matches/isCurrent/readCurrent/exchange；response.resetTicket规范43字符，digest(response.resetTicket)等于exchange捕获候选，response.expiresAt等于返回period。`should_translate_shared_rate_and_unavailable_without_changing_ordinary_400()`断言旧Rate秒数保留、旧Unavailable变新Unavailable且cause脱敏、统一VerificationFailed仍原类型。
- [x] **步骤2 观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.PasswordResetVerificationServiceTest" --console=plain`，期望缺实现。
- [x] **步骤3 实现顺序。** 格式由入口处理；IP摘要→旧store.read→账户额度→第一次isCurrent→验证码比较→第二次isCurrent→readCurrent→Identifiers.generate/digest→exchange→仅明确period成功后返回新票。普通早退调用verifier.reject一次，真实错码recordFailure后直接抛统一失败，证明通过后失败直接统一失败不补reject；exchange空同样统一失败。捕获共享ConfirmationRateLimited/Unavailable并在新边界转换，保留新异常和统一失败，未预期Runtime用Diagnostics包装安全500。
- [x] **步骤4 同命令全绿。** 覆盖缺挑战/账户拒绝/第一次绑定变化一次reject，IP/存储503无reject，真实错码只recordFailure，第二次绑定变化与交换冲突不reject且不返回票；unknown交换不重试/恢复、不调用旧consume。验证没有prepare密码投影、PasswordResetConfirmationTransactionService或Token签发依赖。第三步并发事务行为属于后续范围。
- [x] **步骤5 检查并提交。** `feat: 编排找回密码独立验证与凭据签发`。

### 任务5 HTTP入口、错误映射及三方对照文档

**Files:** 新增Controller、docs/api/user/verify-password-reset.md、docs/deployment/password-reset-verifications.md；修改SecurityConfig仅追加本POST、GlobalExceptionHandler仅追加两个异常映射。测试src/test/java/org/example/music/user/controller/PasswordResetVerificationControllerTest.java。

**Interfaces:** Controller(Service,Reader,ClientIpResolver)，从HttpServletRequest读body/remoteAddr，HttpServletResponse入口设置no-store，返回ResponseEntity<PasswordResetVerificationResponse>。新RateLimited→429/Retry-After、Unavailable→503及脱敏traceId日志；400/415复用既有统一类型/文案。OpenAPI恰200/400/401/403/415/429/500/503八响应，200仅两字段，code输入WRITE_ONLY，输出票正常可见。

- [x] **步骤1 写失败HTTP测试。** `should_allow_anonymous_and_return_exact_ticket_and_expiry_without_location()`，200、json字段数2、no-store、无Location，伪造X-Forwarded-For不改变remoteAddr。`should_map_new_codes_while_hiding_account_denial()`，400统一title/detail且无Retry-After，429为VERIFICATION前缀，503新前缀，500脱敏。非法body/媒体类型零service调用，GET该路径匿名仍401。
- [x] **步骤2 观察红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.PasswordResetVerificationControllerTest" --console=plain`，期望缺Controller。使用真实SecurityConfig/TokenVersionFilter及Reader导入，不关闭过滤器。
- [x] **步骤3 实现入口及映射。** Controller只处理HTTP，无Actor目标参数。旧接口异常handler与白名单其余规则保留，两个新错误映射均no-store，普通400原title/detail保持一致。@Tag/@Operation/@ApiResponse/@Schema及媒体类型声明与实际契约一致。
- [x] **步骤4 编写文档并同命令全绿。** API含字段/公开角色归属/额度/一次性/300秒/未知恢复/完整错误表/示例/文件体curl。部署说明记录单Redis≥7/时钟/持久化、当前阶段旧入口与第二步共享预算、后台发码与旧票关系、未知与坏状态关闭放行、账户残余降级自然恢复。按设计NOTE-001分别列当前阶段及最终阶段三方表，区分实际已交付与第三步后续约束。
- [x] **步骤5 检查并提交。** `feat: 暴露找回密码独立验证接口`。

三方表固定内容如下，PASSWORD_RESET_*_RATE_LIMITED/UNAVAILABLE为对应额度与存储错误族，普通验证失败另列PASSWORD_RESET_VERIFICATION_FAILED。当前阶段发码使用request命名空间email-cooldown/email-daily/ip和PASSWORD_RESET_REQUEST_*；新第二步用confirmation键和PASSWORD_RESET_VERIFICATION_*；旧第三步组合入口仍用confirmation键和PASSWORD_RESET_CONFIRMATION_*，两入口合计。最终第三步将用completion键及PASSWORD_RESET_CONFIRMATION_*，明确标注后续交付，不将内部键名与HTTP错误码等同。

### 任务6 真实链路、混合入口预算与故障回归

**Files:** 新增src/test/java/org/example/music/user/PasswordResetVerificationIntegrationSupport.java、PasswordResetVerificationIntegrationTest.java、PasswordResetVerificationConcurrencyIntegrationTest.java、PasswordResetVerificationFailureIntegrationTest.java。沿用已有夹具约定，定义本任务独立支持类，不修改旧测试消除回归。

**Interfaces:** @SpringBootTest/MockMvc/真实MySQL8.4+Redis8.10.2，具体类Docker条件与@DirtiesContext。真实POST申请→Processor激活→新POST验证，SMTP可控捕获，test profile自动worker关闭并显式驱动。spy仅控制实际执行及闩锁，真实SecurityConfig/JWT/Redis脚本不mock成功；旧接口helper按原三字段HTTP提交，用于已存在入口的回归。

- [x] **步骤1 主链及身份。** `should_issue_for_user_and_admin_without_changing_row_or_old_logins()`，200票/TTL、验证码失效、用户行逐列不变、旧Token及原角色选择票仍可按原版本使用；核对零prepared密码查询、零resetPasswordIfSnapshotMatches、零JWT签发（角色票消费验证另在检查后执行以免混淆登录更新时间）。`should_use_challenge_owner_with_another_valid_bearer()`，A有效Token兑换B申请，目标B绑定、A行和Token有效性保持。真实过期/撤销/无效Bearer在额度前401；过期夹具使用当前tokenVersion/loginType字段及真实签名。
- [x] **步骤2 混合入口额度与比较矩阵。** `should_share_original_fixed_window_between_legacy_and_verification()`交替两个入口各5次格式合法提交后两者第11次分别旧CONFIRMATION/新VERIFICATION 429，底层同一IP键count=10/原expiry；跨多次实际申请的各5错累计账户10后，旧/新入口及假邮箱的普通失败均400、无Retry-After，拒绝不增attempts，旧入口仍保持原契约。仅测试夹具清发码冷却/IP窗口以避免分钟等待，固定窗自然恢复由真实Redis测试单独验证。Hashtable完整字段/TTL/其他用途键按场景核对，Hasher spy断言普通失败一次真实或伪比较、伪结果true仍400、IP拒绝零BCrypt。
- [x] **步骤3 票替换及并发。** `should_leave_ticket_valid_when_only_new_email_is_requested_or_sent()`；`should_replace_previous_ticket_only_after_successful_new_verification()`；`should_exchange_once_for_concurrent_correct_requests()`；第5错/新激活先于兑换失败，非终态错码后兑换成功，旧计错迟到不动新票。旧票预期变更/双键消失/单键缺失、跨版本邮箱/角色/删除/改名、候选碰撞与低版本保护均以真实操作和闩锁验证。用户名单独变化不影响绑定且保留新名。
- [x] **步骤4 故障与未知。** `should_return_503_before_exchange_without_consuming_code()`与`should_keep_actual_exchange_effect_after_lost_reply()`分别真实调用前/后注入，验证票/指针/验证码状态，未执行时旧码可再次成功、已执行后重复400，服务端无自动重试。实际IP/账户/计错丢回复保留已推进计数；真实DB只读开始/查询/完成故障零票变更。第二次DB复核后改角色/邮箱/版本生成的票仍保存旧绑定，已有更高版本票时低版本不能覆盖；真实200丢弃后重复不重放/不续期。期限测试使用真实Redis TIME构造合法近到期原申请，受控等待跨deadline，校验兑换前过期拒绝、成功新票独立300秒。
- [x] **步骤5 真实OpenAPI及定向全绿。** 真实Token读取受保护/v3/api-docs，断言新POST八响应、请求两字段和200两字段，旧两条路径契约仍在。`cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordResetVerification*Test" --tests "org.example.music.user.*PasswordResetTicket*Test" --console=plain`，逐份XML核验确实涵盖Reader/Rules/Store/Service/Controller及所有新真实存储类，MySQL/Redis零必要跳过。
- [x] **步骤6 检查并提交。** `test: 验证找回密码凭据兑换真实链路与并发故障`，范围内失败先核实再修复，保留旧测试断言与真实容器。

### 任务7 完整验证与独立审核材料

**Files:** 新增docs/reviews/2026-11-08-password-reset-verification-review.md，更新本计划/设计状态及已列API/部署文档的验证事实，源代码冻结后只收尾文档。

- [x] **步骤1 完整基准。** 记录源代码commit后运行`$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`，保存退出码/BUILD状态/实际XML。汇总通过=tests−failures−errors−skipped，区分新增类数/用例与基线1825，逐项列所有失败或跳过及原因。MySQL/Redis/MinIO必要集成零跳过才提交；实际业务改动后重新验证，不把旧UP-TO-DATE结果作为本轮运行。
- [x] **步骤2 材料与NOTE-001处置。** 相对da146b2列全量文件含untracked、接口/权限/行为、精确命令及通过/失败/错误/跳过、API/部署/无迁移/基线/curl/技术取值与残余风险。按原编号“本任务设计NOTE-001 非阻断”填六列表，证据为当前/最终三方表及实际混合入口共享计数测试；不冒充已获实现accepted。上一任务“无账户”意见已由本次设计审核核实关闭，如实登记来源。
- [x] **步骤3 冻结核对并提交。** git diff --check及全部文件清单核对，确认无旧producer/confirmation脚本源、UserRepository/SQL、配置、迁移或其他路径改动。文档按实际结果写入，中文提交`docs: 记录独立密码验证全量验证与审核材料`。
- [x] **步骤4 Claude独立实现审核。** 唯一新Method+Path审核，反馈按AGENTS原编号/分级/同任务轮次复核。needs_revision修复或举证请求复核；第三轮未解或escalated按规则交人类。accepted后再进入第三步单独任务，推送按本任务用户授权。

## 设计反馈处置与计划自查

设计审核第1/3轮accepted，零阻断项。NOTE-001问题成立，内部兼容键名与公开错误码不同，按码名定位Redis确有混淆风险；认可非阻断分级及三方对照方向。任务5/7在API/部署同时列当前阶段和最终阶段，记录共享旧窗口原因，HTTP测试及混合入口真实额度测试作为实现证据。API与部署三方表已落实，实际混合入口定向测试通过，本次独立实现审核已核实修复并关闭。

自查覆盖映射为设计§1/2→全局及任务5/7，§3→任务1/5/6，§4→任务4/6/7，§5→任务4/6，§6/7→协议及任务2/3/6，§8→任务3/4/6，§9→任务4/5/6，§10→文件清单，§11→所有测试任务及完整基准，§12→兼容schema和范围门禁，§13→任务5文档。五项Review Focus均有具名断言。

参数/类型已固定为两字段DTO、十字段票、22个兑换参数/4键、两种Optional正常结果、两类新异常；AccountSnapshot使用既有userRole/email访问器，Period使用issuedAt/expiresAt，指针所有者为Integer。成功时间仅由Redis产生，Java重新校验模型；任意未知回复均503而非empty。孤立记录定位边界已明确，既有previous快照的一键损坏仍503，无新增扫描或授权捷径。

计划已获用户确认并实施，源代码与测试冻结be2f091。最终定向10类101通过/0失败/0错误/0跳过，完整MINIO_INTEGRATION_TESTS=true cleanTest check已通过，17分47秒、168类1926通过/0失败/0错误/0跳过，结果与全部材料汇总至docs/reviews/2026-11-08-password-reset-verification-review.md。第1/3轮独立实现审核accepted，独立完整验证17分28秒、168类1926通过/0失败/0错误/0跳过。新的实现NOTE-001（Reader重复）核实成立并列第三步设计评估，见审核材料处置表。用户已授权核实后推送。
