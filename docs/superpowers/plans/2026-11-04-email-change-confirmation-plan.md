# 本人邮箱变更确认接口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式沿用项目约定：Codex唯一实现，Claude独立只读审核，不派发实现子代理。以上执行子技能当前未列为可用，不声称已调用。用户已批准书面设计；用户已批准本计划并再次以“开始吧”授权实施。

**Goal:** 实现唯一PUT /api/v1/users/me/email，验证新邮箱专用码后更新本人邮箱并递增认证版本，204成功后全部旧登录失效。

**Architecture:** 一次近期验证、延迟解析和独立提交额度通过后，读取现有ACTIVE并匹配验证码，复核账户与目标可用性，Redis明确消费成功后才进入MySQL短写事务。MySQL用消费前保存的原始邮箱/角色/版本作CAS，明确提交才返回204；已消费码不恢复，未知结果不重试。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Gradle Wrapper、JPA/MySQL 8.4、StringRedisTemplate/Lua、既有BCrypt及Jakarta Validation/JavaMail地址校验、JUnit 5/MockMvc/Testcontainers、springdoc。无新依赖、迁移、配置或后台任务。

**Spec:** [书面设计](../specs/2026-11-04-email-change-confirmation-design.md)，提交189367a，用户已以“开始吧”批准；未提供本接口独立设计审核结论，不冒称accepted。代码基线97215a0，编写计划前HEAD为189367a、工作区干净；既有独立全量1445/0/0/0、114类及97215a0定向53/0/0/0均是前置链路证据，不是本接口实现验证。

## Global Constraints

- 唯一PUT /api/v1/users/me/email；USER/ADMIN仅本人，允许首次绑定；不增加查询、恢复、refresh或其他端点。
- 可信auth_time的0≤now−authenticatedAt≤300秒准入，含300秒端点，精确Duration比较，不使用iat，不在解析/匹配/提交后重算窗口。
- 请求仅challengeId/code两个字符串；challengeId为规范32字节Base64URL无填充43字符；code恰好6位ASCII数字、保留前导零、不trim。严格JSON/UTF-8、含尾随空白正文最多16384字节；400/415沿用已确认流程契约。
- Redis准入独立于发码及再次验证，每用户首个获准起900秒最多10次；跨Token/设备/角色，失败不退额，拒绝不计数不续窗，429附至少1秒且向上取整的Retry-After。
- ACTIVE仍在`music:auth:email-change:active:<userId>`；新额度键`music:auth:email-change-confirmation:user:<userId>`。现有reservedAt+300000ms绝对到期、状态schema和生产者重发语义不变。
- 同一申请第五错失效，正确码最多一次原子消费；旧challenge不得计错或删除新申请，即使新旧六位码相同。不返回剩余次数或任何内部凭据。
- Redis消费先于SQL：只有明确成功回包才写数据库。消费后回滚、冲突或结果未知均不恢复码、不退款、不自动重试；MySQL提交未知503可能已经修改邮箱。
- SQL匹配原始邮箱（NULL安全且二进制精确）、版本、角色和本人ID，只更新email/token_version/UTC updated_at；版本不溢出，0行只诊断，不换新快照重试。
- 204无正文、Cache-Control: no-store，无Location或新Token；数据库版本恰加1，全部旧Token后续认证被拒绝，不修改密码/用户名/登录时间或签发新auth_time。
- 原普通认证和生产者端点行为保持；无SecurityConfig/JWT/CORS/SMTP/Actuator变更，V1—V8不改，不创建V9或操作记录表。
- DTO/状态toString及异常链脱敏，SLF4J含traceId；不记录邮箱、验证码、challengeId、摘要、Token或原始SQL/Redis消息。
- 中文文档/提交/审核材料；最终带MINIO_INTEGRATION_TESTS=true执行cleanTest check，真实MySQL/Redis及必要MinIO不跳过，自验不替代Claude独立实现审核。

## Review Focus

1. NULL、空白或大小写原邮箱：绑定比较用规范值，CAS仍用原始数据库值；任务3/6验证二者不能混用。
2. 正确匹配期间第五错或重发先发生：任务2/4/6验证消费时重新CAS，旧快照不能越过失效或误改新申请。
3. 消费已执行但回包丢失，与数据库已提交但回包丢失：任务4/6分别验证前者禁止SQL、后者503但真实行已更新，不能混为同一回滚结果。
4. 原始邮箱/角色在消费后变化，而用户名同时变化：任务3/6验证原CAS冲突分类及不覆盖用户名，禁止以新快照重试写入。
5. 当前Token有效但ACTIVE属于旧认证版本，或44字符带填充/43字符末位非规范：任务1/2/4/6分别固定400失效凭据及400格式错误，不误引导重新登录或将其放行。

## 文件与职责

以下Java路径以src/main/java/org/example/music为前缀；未标“修改”的均新增。每个组件构造器注入、字段final，不做模块级无关重构。

| 文件 | 职责/接口 |
| --- | --- |
| user/dto/EmailChangeConfirmationRequest.java | record(String challengeId, String code)，Bean Validation/@Schema及敏感toString |
| user/controller/EmailChangeConfirmationRequestReader.java | EmailChangeConfirmationRequest read(HttpServletRequest)，严格有界解析，不负责身份门禁 |
| user/domain/EmailChangeChallengeSnapshot.java | record(EmailChangeBinding binding, String challengeDigest, String codeHash, Instant reservedAt, Instant expiresAt, int attempts)，复用现有Binding、全敏感字段脱敏 |
| user/domain/EmailChangeConfirmationRules.java | 纯值规则组件：合法/规范地址、原邮箱归一及绑定对照；注入现有Validator，无IO |
| user/repository/EmailChangeConfirmationStore.java | admit/read/recordFailure/consume、专用脚本加载与严格RESP解析，无DB/邮件能力 |
| user/service/EmailChangeConfirmationSnapshotReader.java | 短只读事务load及prepare，返回原始四字段EmailChangeAccountSnapshot |
| user/service/EmailChangeConfirmationTransactionService.java | 独立短写事务、条件更新、0行分类及邮箱唯一冲突识别，不依赖Redis/哈希/Token服务 |
| user/service/EmailChangeConfirmationService.java | 一次门禁、延迟解析、额度、快照/验证码匹配、消费与更新协调 |
| user/controller/EmailChangeConfirmationController.java | 唯一PUT映射、可信主体/上下文、204/no-store/OpenAPI |
| user/repository/UserRepository.java（修改） | 仅追加changeEmailIfSnapshotMatches，不改既有投影或更新方法 |
| common/exception/GlobalExceptionHandler.java（修改） | 仅追加本接口400/409/429/503业务映射，复用既有401/415/EMAIL_ALREADY_EXISTS及problem构造器 |

新增user/exception下四类：EmailChangeVerificationFailedException（400 EMAIL_CHANGE_VERIFICATION_FAILED）、EmailChangeConflictException（409 EMAIL_CHANGE_CONFLICT）、EmailChangeConfirmationRateLimitedException（429及long retryAfterSeconds）、EmailChangeConfirmationUnavailableException（503，构造器Throwable用既有EmailChangeDiagnostics安全副本）。复用既有EmailChangeValidationException、EmailChangeUnsupportedMediaTypeException、EmailChangeEmailAlreadyExistsException、ReauthenticationRequiredException、InvalidTokenException、CurrentUserRevokedException及EmailChangeDiagnostics；不改这些既有契约。

新增src/main/resources/redis/email-change-confirmation/下common.lua、admit.lua、read.lua、fail.lua、consume.lua。仓储加载顺序为既有redis/email-change/common.lua＋本目录common.lua＋单操作脚本；本目录仅补确认约束，现有生产者五个脚本及common不修改，避免改变发码行为。缺classpath资源启动失败，不留空脚本或开发机路径。

新增测试根src/test/java/org/example/music下：

- user/controller/EmailChangeConfirmationRequestReaderTest.java、EmailChangeConfirmationControllerTest.java。
- user/domain/EmailChangeConfirmationRulesTest.java。
- user/repository/EmailChangeConfirmationStoreTest.java；user/EmailChangeConfirmationStoreIntegrationTest.java。
- user/service/EmailChangeConfirmationSnapshotReaderTest.java、EmailChangeConfirmationTransactionServiceTest.java、EmailChangeConfirmationServiceTest.java。
- user/EmailChangeConfirmationTransactionIntegrationTest.java、EmailChangeConfirmationIntegrationTest.java、EmailChangeConfirmationConcurrencyIntegrationTest.java、EmailChangeConfirmationFailureIntegrationTest.java。

文档新增docs/api/user/confirm-email-change.md、docs/deployment/email-change-confirmations.md、docs/reviews/2026-11-04-email-change-confirmation-review.md；本计划/设计按实际阶段更新。没有待提供的审核报告则不创建虚构结论或提前关闭问题。

以下各任务先记录真实红灯（可为缺实现编译失败），再最小实现、同范围绿灯及中文提交。绿灯预期均为BUILD SUCCESSFUL，所选测试失败/错误/必要容器跳过为0；实际数量从JUnit XML统计，不预填预计测试数。

### 任务1：严格确认请求读取与DTO契约

**Files:** ConfirmationRequest、ConfirmationRequestReader、ConfirmationRequestReaderTest。复用既有400/415异常，不修改旧读取器。

**Interfaces:** `EmailChangeConfirmationRequest read(HttpServletRequest request)`；record字段按@NotBlank、@Pattern约束43位Base64URL字符及`[0-9]{6}`，@Schema分别固定minLength/maxLength为43/43及6/6，Reader额外解码32字节并重编码等值，不trim任何字段。后续Service用Supplier延迟调用，Reader不自行计算近期资格。

- [x] **步骤1：写失败测试。** `should_accept_canonical_id_and_leading_zero()`用现有示例43位ID和012345，断言`assertEquals("012345", reader.read(request).code())`；`should_reject_noncanonical_last_character()`使用`"A".repeat(42)+"B"`，断言EmailChangeValidationException。参数化44位带填充、42/44位、Unicode数字、数字类型、首尾空格、缺/未知/重复（含转义同名）字段、null/数组/嵌套/多根及非法UTF-8；有效正文加尾随空白至16384成功、16385拒绝，未知Content-Length同样检查。缺失/非法媒体类型、显式非UTF-8均专用415。
- [x] **步骤2：运行并记录红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.EmailChangeConfirmationRequestReaderTest" --console=plain`。
- [x] **步骤3：实现两个接口文件。** 沿用现有Jackson流式token方式与严格UTF-8解码，最多readNBytes(16385)，解析唯一两字段且检查EOF；局部StreamReadConstraints，不改全局配置。Validator显式校验DTO后进行Base64规范性校验；已知格式异常400，意外provider异常安全包装为500，不保留含凭据的原异常链。
- [x] **步骤4：跑同命令全绿。** 断言请求toString不含ID和code；合法转义后的同值可接受、重复转义键拒绝。格式检查不产生任何账户或额度操作。
- [x] **步骤5：检查并提交。** `git diff --check`，仅暂存本任务文件，中文提交 `feat: 增加邮箱变更确认严格请求校验`。

### 任务2：独立提交额度、ACTIVE读取与一次性消费

**Files:** ChallengeSnapshot、ConfirmationRules、ConfirmationStore、四个新异常、五个新Lua；RulesTest、StoreTest、StoreIntegrationTest。

**Interfaces:** Store构造器注入StringRedisTemplate及EmailChangeConfirmationRules；`void admit(Integer userId)`、`EmailChangeChallengeSnapshot read(Integer userId, String challengeDigest)`、`void recordFailure(EmailChangeChallengeSnapshot snapshot)`、`void consume(EmailChangeChallengeSnapshot snapshot)`。Rules注入现有Validator，提供`String normalizeStoredEmail(String raw)`、`void requireCanonicalBinding(EmailChangeBinding binding)`、`void requireBinding(EmailChangeChallengeSnapshot challenge, EmailChangeAccountSnapshot account, long tokenVersion)`。Rules只做值比较，数据库权限复核归任务3。

- [x] **步骤1：写值规则、RESP和真实Redis失败测试。** 规范空原邮箱映射为null，目标必须非空且已规范化；原始数据库空白/大小写可归一，ACTIVE的非规范地址、CR/LF、孤立代理字符、显示名称/多地址/非法单地址及超过120字符503。`should_fail_on_old_active_version_with_valid_token()`断言EmailChangeVerificationFailedException；原邮箱绑定不符409。`should_preserve_deadline_when_recording_failure()`对比PEXPIRETIME不变；`should_consume_only_once()`两次consume第二次断言400。状态通过已有发码Store.reserve/prepare/activate创建，不能仅mock成功。
- [x] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.domain.EmailChangeConfirmationRulesTest" --tests "org.example.music.user.repository.EmailChangeConfirmationStoreTest" --tests "org.example.music.user.EmailChangeConfirmationStoreIntegrationTest" --console=plain`。
- [x] **步骤3：实现Rules与严格快照解析。** Rules用局部值record的@NotBlank/@Email/@Size(max=120)及InternetAddress严格单地址校验，不新建ValidatorFactory或依赖HTTP DTO。normalizeStoredEmail仅对DB raw值trim/Locale.ROOT小写，null/全空白为null；requireCanonicalBinding要求非空原邮箱及新邮箱已经等于规范值，不能修复后放行。requireBinding检查本人一致、当前数据库版本等于输入Token（否则TOKEN_REVOKED）、ACTIVE旧版本400、规范原邮箱不符409。内部值record及ChallengeSnapshot的toString隐藏敏感字段。
- [x] **步骤4：实现admit.lua。** 单用户额度hash精确count/resetAtMs，TIME首次获准起900000ms绝对到期，0到10次允许，第11次429；已有计数规范1—10、期限有效且不超过now+900000、PEXPIRETIME相符。拒绝不HINCRBY、不改TTL；无自动退款或重试。RESP固定`[0,0]`获准、`[1,retrySeconds]`限流（1—900）、`[2,0]`损坏；Java未知/空/错类型/异常范围回包503。
- [x] **步骤5：实现read.lua及消费者common校验。** KEYS仅本人ACTIVE，ARGV为规范userId和challengeDigest；沿用现有十字段ACTIVE，进一步要求attempts为0—4及challengeDigest为规范32字节摘要编码。缺失/不匹配challenge→`[1]`，损坏schema/TTL/本人键内部ID不符→`[2]`；成功RESP依次为`[0,state,challengeDigest,userId,currentEmail,newEmail,tokenVersion,reservedAtMs,expiresAtMs,codeHash,attempts]`，共11项。Java检查status为Long、其余为String、ID和digest匹配、规范Long版本/安全毫秒/300000差值、BCrypt及地址规则；空currentEmail转null。保留原时间，不使用应用Clock代替Redis过期。
- [x] **步骤6：实现fail/consume完整归属CAS。** ARGV固定userId/currentEmail（null编码空串）/newEmail/tokenVersion/challengeDigest/codeHash/reservedAtMs/expiresAtMs八项。先校验结构/TTL/次数，再区分不同digest的新请求（失配不修改）；同digest但不可变字段不符视损坏503。fail第1—4次HINCRBY，第5次DEL，回包`[0,count]`；无/替换归属`[1,0]`，损坏`[2,0]`。consume只在仍有效且次数<5时DEL，`[0]`成功、`[1]`失效400、`[2]`损坏503，不比较旧attempts精确值。所有版本保留规范十进制字符串到Long.MAX_VALUE，不经Lua double转换；回包、字段和资源读取错误都关闭放行。
- [x] **步骤7：补并发/故障测试并跑同命令全绿。** 10/11次、拒绝不续窗、跨Token主体同ID额度、码状态不受准入失败影响、真实TTL自然失效夹具、无TTL/字段/计数/哈希损坏、未知回包、Long.MAX_VALUE；并发最多一次消费、错误5次与正确交错；同码不同challenge，旧fail/consume不动新ACTIVE。正码读取后错码由0变1仍可消费，第五错先删则消费失败。Redis故障不重试、不清状态后放行。
- [x] **步骤8：检查并提交。** `git diff --check`，中文提交 `feat: 增加邮箱确认限流与一次性验证码消费`；生产者旧脚本不变，并运行已有EmailChangeRequestStoreIntegrationTest回归。

### 任务3：原始账户快照、条件更新及短事务

**Files:** SnapshotReader、TransactionService、UserRepository新增方法；SnapshotReaderTest、TransactionServiceTest、TransactionIntegrationTest。复用现有EmailChangeAccountSnapshot，不增字段或修改其余接口投影。

**Interfaces:** Reader构造器注入UserRepository/PlatformTransactionManager/Rules，提供`EmailChangeAccountSnapshot load(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session)`和`EmailChangeAccountSnapshot prepare(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, EmailChangeChallengeSnapshot challenge)`。TransactionService构造器只注入UserRepository/PlatformTransactionManager，提供`void update(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, EmailChangeAccountSnapshot expected, String newEmail)`。Repo新增`int changeEmailIfSnapshotMatches(Integer userId, String previousEmail, long expectedVersion, String previousRole, String newEmail)`，各参数@Param显式命名。

- [x] **步骤1：写失败测试。** `should_preserve_raw_email_for_cas()`断言`assertEquals(" Old@Example.com ", prepared.email())`，同时绑定比较通过；null/空白/大小写参数化。只读事务启动/查询/完成错误503；缺行、版本变化及登录角色不再允许401；目标命中他人409 EMAIL_ALREADY_EXISTS，命中本人或原邮箱变动409 EMAIL_CHANGE_CONFLICT。prepare发现Long.MAX_VALUE503。真实MySQL测试先写SQL调用失败红灯，验证同一版本只更新一次，email/version/updated_at一起提交。
- [x] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.EmailChangeConfirmationSnapshotReaderTest" --tests "org.example.music.user.service.EmailChangeConfirmationTransactionServiceTest" --tests "org.example.music.user.EmailChangeConfirmationTransactionIntegrationTest" --console=plain`；集成类用MySQL8.4及真实Redis测试配置，不用H2，JDBC核对真实行。
- [x] **步骤3：实现load与prepare。** TransactionTemplate短只读事务的外层catch覆盖开始和完成故障；load读取已有findEmailChangeSnapshot，检查ID/字段/角色/版本及原地址合法，但返回原始record。prepare在新的短只读事务重新读取并核验授权、Rules.requireBinding、版本可递增、findEmailOwnerId原数据库排序规则；返回该次raw快照，作为后续唯一CAS expected。不得在这些事务内做BCrypt或Redis。
- [x] **步骤4：实现Repo条件更新。** @Modifying(clearAutomatically=true)的原生SQL按ID定位，要求`(CAST(email AS BINARY) <=> CAST(:previousEmail AS BINARY))`、token_version=:expectedVersion、账户角色二进制等于previousRole且token_version<Long.MAX_VALUE；只SET email=:newEmail、token_version=token_version+1、updated_at=UTC_TIMESTAMP(6)。不按规范化邮箱做SQL预期，不保存整个Entity，不增加审计列/迁移。
- [x] **步骤5：实现update短写事务与错误分类。** 校验传入expected与可信主体/版本/原登录角色相容，transaction.executeWithoutResult中只调用CAS及0行后同表快照诊断。1行提交；0行缺行/版本不符/登录角色失效→TOKEN_REVOKED，其余快照冲突409；异常行数安全500并回滚。不重取快照后重试。事务外统一转换DataAccessException/TransactionException；只在MySQL重复键1062及明确ix_users_email约束证据同时成立时409，其他完整性/启动/执行/提交故障503。约束名可用Hibernate异常元数据，驱动回退只识别消息末尾的精确索引名，不将任意包含该文本的错误当邮箱冲突，不输出原消息。
- [x] **步骤6：补齐真实SQL及失败测试并全绿。** 精确raw CAS拒绝大小写/空白后改动，NULL正确匹配；username/password/created_at/last_login_at不变，正常版本恰加1、MAX不溢出；并发更新唯一性、并发改名不覆盖；邮箱唯一冲突409、其他完整性503、事务开始/执行/提交异常，以及0行不重试。用spy真实事务管理器在真实commit后抛TransactionSystemException，JDBC证明行确已提交且方法返回503；不以mock行数1冒充真实提交。
- [x] **步骤7：检查并提交。** `git diff --check`，中文提交 `feat: 增加邮箱与认证版本的条件事务更新`。TransactionService不注入Redis、密码哈希或Token签发服务，测试显式核对依赖边界。

### 任务4：一次门禁与确认业务协调

**Files:** ConfirmationService、ConfirmationServiceTest；复用既有EmailChangeRecentAuthenticationGuard、PasswordHasher及安全诊断，不改变其行为。

**Interfaces:** `void confirm(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, Supplier<EmailChangeConfirmationRequest> input)`；构造器注入Guard、Store、SnapshotReader、Rules、PasswordHasher、TransactionService。后续Controller仅提供`() -> reader.read(request)`，不先读取正文。

- [x] **步骤1：写顺序失败测试。** `should_reject_missing_recent_auth_before_parsing()`断言ReauthenticationRequiredException及`verifyNoInteractions(input, store, snapshots, hasher, updater)`。`should_update_only_after_explicit_consumption_success()`用InOrder核对guard→input→admit→read→load→requireBinding→matches→prepare→consume→update；expected必须是prepare返回的同一raw快照，不能使用load旧值或再次load。正确匹配后consume抛503时`verifyNoInteractions(updater)`。
- [x] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.EmailChangeConfirmationServiceTest" --console=plain`。
- [x] **步骤3：实现协调接口。** guard先于Supplier且只调用一次，input只读取一次；admit后对规范challengeId做SHA-256摘要，read只取本人ACTIVE；load与Rules.requireBinding核验，BCrypt.matches使用六位原字符串。错码仅recordFailure后抛统一400，不prepare、不consume、不写DB。正确码prepare复核通过后consume，只有正常返回才update(actor,session,prepared,challenge.binding().newEmail())，正常提交才返回void。
- [x] **步骤4：写故障与不恢复断言。** 参数化准入/读取/计错/匹配/prepare/consume/update失败，逐个断言精确业务异常与后续未调用。update异常后无任何Redis写入/恢复/清理ACTIVE或pending；无finally删除。未知RuntimeException以EmailChangeDiagnostics安全包装进入500，已知错误原语义保留；没有sleep、重试、补偿签发Token或重新核验近期时间。解析后/哈希后推进Clock过300秒仍按初始准入，但脚本真实到期仍拒绝。
- [x] **步骤5：跑同命令全绿并提交。** 包含旧版本ACTIVE但Token仍有效为400、当前DB版本变化为401、原邮箱变动409、MAX prepare503且consume未调用；中文提交 `feat: 协调本人邮箱变更确认与一次性消费`。

### 任务5：204 HTTP入口、错误映射与中文API文档

**Files:** ConfirmationController、GlobalExceptionHandler必要追加、ConfirmationControllerTest、docs/api/user/confirm-email-change.md。

**Interfaces:** `ResponseEntity<Void> confirm(HttpServletRequest request, Authentication authentication)`。不声明consumes以保持近期门禁先于媒体类型检查；请求OpenAPI显式声明application/json及ConfirmationRequest。提取可信session/actor，调用Service.confirm后返回noContent().cacheControl(noStore())，不使用ClientIpResolver或返回DTO。

- [x] **步骤1：写HTTP失败测试。** `should_return_204_without_body_or_token()`断言status().isNoContent()、空正文、no-store、无Location/新Token字段。对400两种code、409两种code、401近期不足/普通撤销、415、429及Retry-After、503确认/前置认证故障、500校验application/problem+json及精确urn:problem:music:小写下划线type；无认证与伪造上下文不调用Service。输入延迟Supplier交给真实Reader做解析，不能mock整个解析为成功。
- [x] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.EmailChangeConfirmationControllerTest" --console=plain`。
- [x] **步骤3：实现入口及四个新异常映射。** Service专用400/409/429/503经GlobalExceptionHandler统一映射，复用既有401/415及邮箱占用映射，原其他处理器不变。所有503/未知诊断用安全异常链，429返回正整数Retry-After；不添加全局HttpMediaTypeNotSupportedException处理，不改SecurityConfig或JWT过滤器。
- [x] **步骤4：完成OpenAPI与中文API文档。** Tag/Operation/参数及Schema齐全；声明204、400、401、403（沿用安全链，不虚构实际403业务）、409、415、429、500、503；204无JSON schema。文档写请求、规范ID/六位码、16KiB、900秒10次、五错、失败不恢复、两种重发顺序、提交未知核实流程、全部旧登录失效、curl；普通JWT过期/签名错误可能无ProblemDetail按现状说明。
- [x] **步骤5：跑Controller与旧读取器回归并提交。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.EmailChangeConfirmationControllerTest" --tests "org.example.music.user.controller.EmailChangeRequestControllerTest" --tests "org.example.music.user.controller.ReauthenticationControllerTest" --console=plain`。日志抓取断言无邮箱/码/ID/摘要/Token及原异常cause/suppressed；`git diff --check`，中文提交 `feat: 暴露本人邮箱变更确认接口`。

### 任务6：真实链路、交错故障与最终实现送审

**Files:** ConfirmationIntegrationTest、ConfirmationConcurrencyIntegrationTest、ConfirmationFailureIntegrationTest、必要StoreIntegrationTest补充、docs/deployment/email-change-confirmations.md、实现审核记录及本设计/计划执行状态。

**Interfaces:** 沿用@SpringBootTest/@AutoConfigureMockMvc/@EnabledIfDockerAvailable及Testcontainers MySQL8.4/Redis8.10.2配置，真实JWT/BCrypt/数据库/Redis；EmailChangeEmailSender仅在SMTP边界受控捕获验证码。使用真实发码端点创建ACTIVE并返回challengeId，真实PUT确认，不手工造唯一成功链路；不同集成类使用各自动态容器配置和测试上下文，避免缓存已停止容器。

- [x] **步骤1：写真实链路测试并运行。** `cmd /c gradlew.bat test --tests "org.example.music.user.EmailChangeConfirmationIntegrationTest" --console=plain`。断言USER、ADMIN/保留USER登录角色、未绑定NULL及空白初次绑定成功；JDBC前后比较仅邮箱/版本/更新时间变化；同一正确码仅一次更新，204无Token，当前/其他旧Token真实GET /api/v1/users/me均拒绝。通过现有POST /api/v1/auth/login以loginType=USERNAME_PASSWORD或EMAIL_PASSWORD、account=用户名或新邮箱、password=测试密码重新登录；管理员继续用ROLE_SELECTION/loginTicket/loginRole完成既有第二阶段。从authentication.accessToken核对版本并走真实资料认证；契约见docs/api/auth/login.md。仅测试本接口造成的兼容行为，不新增登录能力。
- [x] **步骤2：补真实认证、绑定与OpenAPI验证。** 299/300/超窗/缺auth_time、新旧Token差异；401近期不足不读体不扣额；有效Token配旧版本ACTIVE400；原邮箱变化409、目标被他人占用409和真实MySQL大小写/重音唯一语义、请求绑定他人/已过期/被替换400。实际GET /v3/api-docs核对请求两个属性、标识和code长度、204无JSON及全部错误响应声明；不将403文档声明当已触发测试。
- [x] **步骤3：写真实并发顺序测试。** `cmd /c gradlew.bat test --tests "org.example.music.user.EmailChangeConfirmationConcurrencyIntegrationTest" --console=plain`。闩锁分别控制正确matches后重发先激活（旧400、零SQL）、旧consume后新申请先激活再允许旧SQL提交（新ACTIVE保留但旧绑定版本失效）。同码多路并发最多一次consume和version+1；第五错先删则正确400、正确先删则旧错不能动新记录。与真实改密/角色更新/删除/原邮箱更改交错，核对条件更新结果；同时改名不覆盖用户名。测试结束释放所有线程和闩锁，不用sleep制造顺序。
- [x] **步骤4：写真实故障边界测试。** `cmd /c gradlew.bat test --tests "org.example.music.user.EmailChangeConfirmationFailureIntegrationTest" --console=plain`。spy仅用于在真实Redis命令执行后抛确认丢失，断言额度/次数可能已变化、消费未知不进SQL。消费后注入事务开始失败、真实唯一冲突、实际回滚，均验证用户行不变及ACTIVE仍已消费。真实事务manager仅对写事务实际commit后抛TransactionSystemException，断言HTTP503但行已更新/版本+1/旧Token被撤销，不用mock提交成功冒充。丢弃真实204响应后以旧Token重试401，重新登录后重放旧申请400；所有场景均不恢复、不退款、不自动写入重试。
- [x] **步骤5：跑上述三类及Store/Transaction集成类全绿。** `cmd /c gradlew.bat test --tests "org.example.music.user.EmailChangeConfirmation*IntegrationTest" --console=plain`。同时确认实际运行类包含Store、Transaction、主链路、Concurrency、Failure五类；聚合实际通过/失败/错误/跳过，容器跳过则恢复环境实跑，不算完成。发生新失败按影响范围修复后再验证，不删除失败断言。
- [x] **步骤6：写部署与恢复说明。** 单Redis、现有PEXPIRETIME支持、两套独立额度/已有ACTIVE结构、应用Clock与Redis TIME职责；503消费未知无SQL、DB提交未知可能已更新的差异，客户端恢复有效登录后核实本人邮箱，旧Token被拒绝不单独作为成功证明。日志traceId/脱敏，既有SMTP及生产者配置不改；不增加Actuator或调试/恢复接口，不提供生产删键清额步骤。
- [x] **步骤7：运行最终全量基准。** PowerShell先`$env:MINIO_INTEGRATION_TESTS = "true"`，再`.\gradlew.bat cleanTest check --console=plain`。保存Gradle退出码与JUnit XML：通过=tests−failures−errors−skipped，逐类列新增测试数量及总类数；必要容器0跳过。检查无Flyway/依赖/配置/SecurityConfig/旧生产者脚本变动；只有测试确实通过才登记结果，历史1445不作为新数量。
- [x] **步骤8：整理并提交审核材料。** 对照代码基线97215a0运行git diff --check、git status --short（含untracked）、文件清单及迁移diff；按AGENTS.md第2/14节提供Method/Path、需求权限、行为、全部文件、无迁移、API路径、实际命令及四项数量、基线和curl、默认项/风险。首次本接口审核记录“不涉及既往反馈”，不挪用前一个发码接口accepted。中文提交 `test: 验证邮箱变更确认真实链路与故障边界`，材料/状态可单独中文提交。
- [ ] **步骤9：独立审核交接。** 将完整实现材料交Claude只读审核；不自行代替其结论。needs_revision逐项核实并保留原编号/分级/轮次，第三轮或escalated遵从项目规则。用户对上一发码接口的推送已完成，本接口是否推送依当前任务授权明确后处理，不在设计/计划阶段自动发布。

## 计划自查与交接

覆盖对应：设计§1/2/10→范围与文件表；§3→任务1/5/6；§4→复用门禁及任务4/6；§5/6→任务2/4/6；§7→任务3/4/6；§8→任务4/6；§9→任务2/5/6；§11→任务5/6。五项Review Focus均有对应具名测试及真实交错验证，没有未归属的需求。

类型核对：Rules、Store及后续Service均使用同一个EmailChangeChallengeSnapshot/EmailChangeBinding；prepare返回原始EmailChangeAccountSnapshot，update只接收该快照，脚本参数/RESP字段顺序固定，attempts不作为消费精确CAS条件。无实现占位类、无额外业务分支，无需新迁移；计划仅固定实现与验证决策，不展开可由签名和测试确定的函数体。

用户已批准本计划并授权实施。任务1—5及真实链路已完成；五类集成80项初次79通过/1夹具失败，修正故障注入后Failure类10/0/0/0。代码固定94ac5fe，带MinIO的全量cleanTest check已生成126类1599通过/0失败/0错误/0跳过（新增12类154项）。结束后的长会话输出未能再次读取，不臆造其退出码；随后同环境check退出0、BUILD SUCCESSFUL且test/check均UP-TO-DATE。审核材料已整理，任务6步骤9仍待Claude独立实现审核交接，不自动视为accepted。
