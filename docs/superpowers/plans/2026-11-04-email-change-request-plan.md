# 新邮箱变更验证码申请接口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式遵从项目既定约定：Codex唯一实现，Claude独立只读审核，不派发实现子代理。以上执行子技能当前未列为可用，不声称已调用；用户审阅计划前不开始实现。

**Goal:** 实现唯一接口POST /api/v1/users/me/email-change-requests，在近期真实认证后向目标新邮箱发送专用验证码，成功激活才返回201，不修改账户或登录状态。

**Architecture:** Service在一次近期验证门禁之后读取延迟提供的请求DTO，核验账户及邮箱占用，再经独立Redis Lua原子扣额和预留。生成摘要、通过独立有界SMTP任务发送、复核绑定后CAS激活；MySQL短只读事务不包围SMTP等待，邮件任务没有激活能力。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Gradle Wrapper、JPA/MySQL 8.4、StringRedisTemplate/Lua、JavaMail、BCrypt、JUnit 5/MockMvc/Testcontainers、springdoc；不新增依赖或配置项。

**Spec:** [书面设计](../specs/2026-11-03-email-change-request-design.md)，设计提交6f29752已获第1/3轮accepted；[审核处置](../../reviews/2026-11-04-email-change-request-design-review.md)记录NOTE-001。代码基线36cf271，计划前HEAD为6f29752；独立基线1285通过/0失败/0错误/0跳过、103类，不是本接口实现证据。

## Global Constraints

- 唯一接口POST /api/v1/users/me/email-change-requests；USER、ADMIN仅本人，可为未绑定用户首次绑定。最终确认、refresh接口均不实现。
- auth_time门禁为0≤now−authenticatedAt≤300秒，包含300秒端点；缺失/超窗401 REAUTHENTICATION_REQUIRED；不以iat替代，不扩大到既有接口。
- 唯一字符串字段newEmail，先trim及Locale.ROOT小写，再校验合法单地址、非空、最长120字符；原输入CR/LF拒绝，不合并点号/+tag。未知、重复、非字符串、空体等400。
- 415固定UNSUPPORTED_MEDIA_TYPE及urn:problem:music:unsupported_media_type，仅新增本接口专用异常映射；其余type同样使用既有code转小写保留下划线规则。
- 201仅challengeId和UTC Instant expiresAt，Cache-Control: no-store，无Location；验证码不返回。6位ASCII数字、BCrypt摘要；challengeId为32随机字节Base64URL无填充，存储只保留SHA-256摘要。
- 每用户60秒一次、每UTC自然日10次；每规范化目标邮箱60秒一次、每UTC自然日10次；每连接IP固定60秒5次。拒绝不扣额、不续窗，准入后失败不退款、不自动重发。
- Redis TIME确定reservedAt；expiresAt=reservedAt+300000ms、leaseUntil=reservedAt+60000ms，准备/发送/激活不续命。每人最多一个ACTIVE和一个pending，成功才替换，激活结果未知不承诺旧申请保留。
- 邮件连接/读/写各5000ms，独立执行器4槽、零队列、等待20秒；超时不等于任务退出，迟到任务无激活权限，不改旧用途容量或客户端配置。
- 不写用户表、不刷新auth_time、不签Token、不递增版本、不撤销登录；无迁移、依赖、配置、Actuator或全局安全规则改动，V1—V8保持不变。
- 验证码、摘要、邮箱、challengeId、Token和底层敏感异常不进入日志，SLF4J日志保留traceId，敏感DTO及状态对象toString脱敏。
- 中文API/部署/审核材料与提交；实现最终执行MINIO_INTEGRATION_TESTS=true的check并如实统计，必要MySQL/Redis/MinIO跳过不算完成。设计accepted不替代独立实现审核。

## Review Focus

1. 近期窗口在解析或SMTP等待中跨界：只在业务准入取一次Clock；任务1/4/6验证300秒可进入并继续，不重复检查导致误拒绝。
2. MySQL不区分重音的唯一规则与纯小写比较不同：任务4/6用真实排序规则检查命中本人为EMAIL_UNCHANGED、他人为EMAIL_ALREADY_EXISTS，不只mock existsByEmail。
3. 多账户向同一目标并发申请：任务2/6验证共享目标桶与全额度预检，拒绝不能留下用户/IP的部分扣额。
4. 激活执行后丢失回包和超时发送迟到：任务2/3/4/6验证503可能已替换、旧清理不动新ACTIVE、真实线程不因取消假释放。
5. 非UTF-8、重复键、非法Content-Type与旧接口回归：任务1/5/6验证415/400稳定区分，旧再次验证端点继续原400而非跟随改变。

## 文件与职责

以下生产Java路径以src/main/java/org/example/music为前缀，除明确标注“修改”外均新增：

| 路径 | 职责与固定接口 |
| --- | --- |
| user/dto/EmailChangeRequest.java | record(String newEmail)，规范化后Bean Validation，toString隐藏邮箱 |
| user/dto/EmailChangeRequestResponse.java | record(String challengeId, Instant expiresAt)，隐藏challengeId |
| user/controller/EmailChangeRequestReader.java | EmailChangeRequest read(HttpServletRequest request)，严格解析及地址规范化 |
| user/service/EmailChangeRecentAuthenticationGuard.java | void requireRecent(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session)，注入已有Clock |
| user/domain/EmailChangeAccountSnapshot.java | record(Integer userId, String email, UserRole userRole, long tokenVersion)，无username/password，邮箱脱敏 |
| user/domain/EmailChangeBinding.java | record(Integer userId, String currentEmail, String newEmail, long tokenVersion)，currentEmail=null表达未绑定，双邮箱脱敏 |
| user/domain/EmailChangeReservation.java | record(String challengeDigest, Instant reservedAt, Instant expiresAt, Instant leaseUntil)，摘要脱敏 |
| user/repository/UserRepository.java（修改） | 新增最小账户投影及邮箱归属ID查询，不改旧查询/写操作 |
| user/service/EmailChangeSnapshotReader.java | 短只读事务读取绑定/查目标归属，事务启动和完成故障同样转503 |
| user/repository/EmailChangeRequestStore.java | reserve/prepare/activate/cancel，独立Lua、严格RESP、状态归属和额度 |
| user/service/EmailChangeRequestService.java | 一次门禁→延迟解析→查库→准入→哈希→发送→复核→激活，finally仅清理本pending |
| user/email/EmailChangeMessage.java | record(String recipient, String code, Instant expiresAt)，敏感字段脱敏 |
| user/email/EmailChangeEmailSender.java | void send(EmailChangeMessage message)，同步传输SPI，无DB/Redis能力 |
| user/email/IsolatedEmailChangeMailClientFactory.java | JavaMailSenderImpl createClient()，复制配置及独立Session，限定本用途超时 |
| user/email/SmtpEmailChangeEmailSender.java | 实现SPI，UTF-8邮件明确验证新邮箱、尚未更新账户 |
| user/email/BoundedEmailChangeEmailDelivery.java | void send(EmailChangeMessage message)，独立4槽/零队列/20秒及生命周期 |
| user/controller/EmailChangeRequestController.java | 唯一POST映射、认证上下文/IP、201/no-store/OpenAPI |
| common/exception/GlobalExceptionHandler.java（修改） | 仅新增当前用途异常映射，不修改既有错误构造器或异常行为 |

新增user/exception下：ReauthenticationRequiredException（401）、EmailChangeValidationException（400）、EmailChangeUnsupportedMediaTypeException（415）、EmailUnchangedException（409）、EmailChangeEmailAlreadyExistsException（409）、EmailChangeRequestConflictException（409）、EmailChangeRateLimitedException（429，long retryAfterSeconds）、EmailChangeSendFailedException（502）、EmailChangeRequestUnavailableException（503）、EmailChangeDiagnostics（安全Throwable副本）。不复用可能打印原始SMTP异常链的旧发送异常。

新增src/main/resources/redis/email-change/下common.lua、reserve.lua、prepare.lua、activate.lua、cancel.lua。独立schema和命名空间，不修改reauthentication或reauthentication-email脚本。

新增测试根src/test/java/org/example/music下：

- user/controller/EmailChangeRequestReaderTest.java、EmailChangeRequestControllerTest.java。
- user/service/EmailChangeRecentAuthenticationGuardTest.java、EmailChangeSnapshotReaderTest.java、EmailChangeRequestServiceTest.java。
- user/repository/EmailChangeRequestStoreTest.java；user/EmailChangeRequestStoreIntegrationTest.java（真实Redis）。
- user/email/IsolatedEmailChangeMailClientFactoryTest.java、SmtpEmailChangeEmailSenderTest.java、BoundedEmailChangeEmailDeliveryTest.java。
- user/EmailChangeRequestIntegrationTest.java（MySQL/Redis/真实认证过滤链/BCrypt，受控邮件传输）。

文档新增docs/api/user/request-email-change.md、docs/deployment/email-change-requests.md、docs/reviews/2026-11-04-email-change-request-review.md。本计划、原设计及设计反馈记录按执行事实更新，不能预填通过数量或审核结论。

以下绿灯命令的预期均为BUILD SUCCESSFUL，所选测试失败/错误/跳过均为0；每个红灯步骤记录实际失败原因。各任务的最小断言锚点在该步骤的具名测试内使用，不是新增生产辅助接口。

### 任务1：一次近期验证门禁与严格请求契约

**Files:** 上表两个DTO、Reader、Guard，以及400/401/415异常；对应ReaderTest和GuardTest。任务5再接入HTTP异常映射。

**Interfaces:** Guard.requireRecent(actor, session)成功返回void，否则抛既有InvalidTokenException或ReauthenticationRequiredException；Reader.read(HttpServletRequest)返回规范化EmailChangeRequest。DTO的newEmail带@NotBlank、@Email、@Size(max=120)，Reader用Validator显式校验。

最小断言锚点：`should_allow_exactly_300_seconds()`使用`assertDoesNotThrow(() -> guard.requireRecent(actor, session))`；`should_reject_300_seconds_plus_one_nanosecond()`使用`assertThrows(ReauthenticationRequiredException.class, () -> guard.requireRecent(actor, session))`；`should_normalize_before_length_validation()`使用`assertEquals("new@example.com", reader.read(request).newEmail())`，request包含JSON字符串`" New@Example.com "`。

- [ ] **步骤1：写门禁和读取器失败测试。** Guard固定Clock并参数化认证距今299秒、300秒、300秒加1纳秒，分别成功、成功、401；authenticatedAt=null为401，actor/可信上下文缺失或版本非法为InvalidTokenException，不回填时间。请求测试断言`" New@Example.com "`→`"new@example.com"`、120字符合法地址成功、121字符拒绝；原CR/LF即使trim可去除也拒绝；数字/null/数组/嵌套/重复或未知字段、尾随对象、非法UTF-8均400，显示名称及多收件人拒绝。
- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.EmailChangeRequestReaderTest" --tests "org.example.music.user.service.EmailChangeRecentAuthenticationGuardTest"`；记录缺实现导致的编译或断言失败，不能用空测试绿灯替代。
- [ ] **步骤3：实现接口。** 使用既有Clock精确Duration比较，不能取整秒放宽超窗。Reader通过UTF-8严格解码器和Jackson流式token检查，先拒绝原CR/LF，再trim/Locale.ROOT小写、Bean Validation及InternetAddress严格单地址校验；不调用旧读取器或修改旧约束。缺失/格式非法/非application/json的Content-Type及显式非UTF-8字符集抛专用415；非法正文/UTF-8仍400。采用本端点局部16384字节正文读取预算（包括尾随空白，超出400），不无界readAllBytes或修改全局Jackson；这是待用户审阅的资源保护默认值，不把规范化前120字符作为业务上限。读到EOF检查预算和尾随内容，不能信任Content-Length；增加正文恰好16384/16385字节及原字符串超过120但trim后合法的测试，并在API文档说明局部上限。
- [ ] **步骤4：运行同一命令至全绿。** 参数化用例包含UTF-8显式参数、大小写媒体类型、非法charset，检查DTO.toString不含邮箱/标识。边界不依赖sleep或真实墙钟。
- [ ] **步骤5：检查本任务实际文件后中文提交。** `git diff --check`；仅暂存上述文件，提交 `feat: 增加邮箱变更近期验证与严格请求校验`。此提交尚不暴露HTTP接口。

### 任务2：独立Redis申请状态与三层原子限流

**Files:** Binding、Reservation、RequestStore、五个Lua、429/409冲突/503异常和Diagnostics；StoreTest、StoreIntegrationTest。

**Interfaces:** `EmailChangeReservation reserve(EmailChangeBinding binding, String challengeDigest, String ipDigest)`；`void prepare(EmailChangeBinding binding, EmailChangeReservation reservation, String codeHash)`；`void activate(EmailChangeBinding binding, EmailChangeReservation reservation)`；`void cancel(EmailChangeBinding binding, String challengeDigest)`。读取classpath common前缀拼接脚本，缺资源启动失败。

最小断言锚点：`should_keep_original_deadline_when_activated()`在reserve/prepare/activate后使用`assertEquals(300000L, reservation.expiresAt().toEpochMilli() - reservation.reservedAt().toEpochMilli())`，并直接查询Redis的PEXPIRETIME等于expiresAt；`should_share_target_cooldown_across_users()`在不同用户、不同IP、同一newEmail的第二次reserve上断言`assertThrows(EmailChangeRateLimitedException.class, () -> store.reserve(secondBinding, secondDigest, secondIpDigest))`，且七项状态快照不变。

- [ ] **步骤1：写存储协议及真Redis失败测试。** 参数化首次准入、用户冷却/第11次日额度、目标跨账户冷却/第11次日额度、IP第6次；每次拒绝对比全部配额内容及PEXPIRETIME不变；至少一个并发测试让多个用户以不同IP申请同一目标，最多一个在冷却期获准。首次绑定与已有邮箱分别验证prepare保持原时间，activate才替换旧ACTIVE，cancel仅动本pending。
- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.repository.EmailChangeRequestStoreTest" --tests "org.example.music.user.EmailChangeRequestStoreIntegrationTest"`；MySQL无关，Redis使用既有Testcontainers模式与redis:8.10.2镜像，不mock脚本执行。
- [ ] **步骤3：固定键与schema。** 前缀music:auth:email-change:，key为user-cooldown:<userId>、user-daily:<userId>、target-cooldown:<SHA256规范化新邮箱>、target-daily:<同摘要>、ip:<ipDigest>、pending:<userId>、active:<userId>。pending字段state/challengeDigest/userId/currentEmail/newEmail/tokenVersion/reservedAtMs/expiresAtMs/leaseUntilMs；currentEmail空字符串代表未绑定，其余邮箱不得为空；PREPARED增加codeHash，ACTIVE移除leaseUntilMs并增加codeHash/attempts=0。冷却仅resetAtMs，日/IP桶为count/resetAtMs，全部验证精确字段数、TYPE、规范数字及真实绝对TTL，不对无界hash直接HGETALL。tokenVersion按十进制字符串验证到Long.MAX_VALUE，不转Lua double；毫秒限制安全整数并用十进制无指数格式保存。
- [ ] **步骤4：实现reserve的全预检与写入。** KEYS按用户冷却、用户日、目标冷却、目标日、IP、pending、active固定七项；先校验所有现存状态完整性，再计算所有阻塞桶，限流返回429及最大等待秒数；额度允许但live pending返回409，两者均不写。成功才一次计数、设绝对TTL并创建PREPARING。Redis TIME决定300000ms期限、60000ms租约/冷却/IP窗口及UTC日界；日额度各10次、IP5次；不使用测试时间覆盖生产参数。
- [ ] **步骤5：实现归属CAS及严格回包。** prepare仅本绑定/digest的PREPARING写摘要；activate仅本PREPARED且租约/原到期仍有效时替换ACTIVE；cancel仅本归属删除pending，缺失或归属不同noop，不改ACTIVE或额度。reserve固定`[0,reservedAtMs,expiresAtMs,leaseUntilMs]`成功、`[1,retryAfterSeconds,0,0]`限流、`[2,0,0,0]`损坏、`[3,0,0,0]`pending冲突；其他操作`[0]`成功、`[1]`确定冲突、`[2]`损坏，cancel仅0/2。Java严格检查Long类型、长度、范围、两个时间差；空/未知回包及Redis异常503，不重试。日志使用Diagnostics安全副本，不保留原消息/cause/suppressed。
- [ ] **步骤6：扩充故障与并发测试并跑同命令至全绿。** 类型/字段/TTL/计数损坏、Long.MAX_VALUE、未知响应、真实绝对过期、UTC日界与冷却不重置、同码不同challenge、旧cancel不删新pending、激活后丢回包不恢复旧ACTIVE。UTC helper用固定输入边界测，生产TIME配合真实TTL验证；用闩锁而非sleep控制交错。损坏任一桶时断言其他桶未部分扣额；未知执行结果不承诺未扣额。
- [ ] **步骤7：检查并提交。** `git diff --check`，只暂存任务文件，提交 `feat: 增加邮箱变更申请原子存储与三层限流`。不增加生产读取/消费/调试端点，不提前实现确认接口。

### 任务3：专用SMTP客户端及有界发送

**Files:** 上表五个email类、EmailChangeSendFailedException、相应三个email测试类；复用任务2的安全诊断与503异常。

**Interfaces:** Factory.createClient()返回私有JavaMailSenderImpl；Sender.send(EmailChangeMessage)同步；Delivery.send(EmailChangeMessage)有界等待。Delivery生产构造器注入Sender，包内测试构造器接收ExecutorService和Duration，生产固定4槽/SynchronousQueue/AbortPolicy/20秒，无外部新配置。

最小断言锚点：`should_keep_slots_occupied_after_logical_timeout()`在四个任务实际进入发送器后使用`assertThrows(EmailChangeRequestUnavailableException.class, () -> delivery.send(message))`；`should_isolate_smtp_timeouts()`断言`assertEquals("5000", client.getSession().getProperty("mail.smtp.writetimeout"))`，并对源客户端的原属性值做相等断言。

- [ ] **步骤1：写邮件失败测试。** 工厂保留原host/port/protocol/username/password/defaultEncoding、TLS/auth属性，源对象/源Properties不变，新Session独立，smtp/smtps三类超时各5000ms、debug关闭。MimeMessage收件人为规范化目标、UTF-8、包含六位码及原expiresAt，明确尚未变更邮箱；发送异常映射专用502且诊断无秘密。
- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.email.IsolatedEmailChangeMailClientFactoryTest" --tests "org.example.music.user.email.SmtpEmailChangeEmailSenderTest" --tests "org.example.music.user.email.BoundedEmailChangeEmailDeliveryTest"`。
- [ ] **步骤3：实现隔离客户端和传输。** 从现有JavaMailSender复制成私有客户端，不注册第二个公共JavaMailSender bean、不修改旧factory；非可隔离源以安全启动错误拒绝。Message/日志不泄漏邮箱与验证码。MessagingException/MailException→EmailChangeSendFailedException，意外程序异常保留安全500诊断。
- [ ] **步骤4：实现执行器并添加真实并发断言。** 四个不可中断受控任务占满，第五个立即503；短测试等待预算触发502后任务仍占槽，后续仍拒绝，释放闩锁后实际退出才恢复容量；迟到任务无存储能力。InterruptedException恢复中断标记并取消，Timeout/Cancellation→502，RejectedExecutionException→503；关闭停止接单并shutdownNow，finally释放测试线程。断言生产参数为4/零队列/20秒，不实际等待20秒做单测。
- [ ] **步骤5：跑同命令全绿并提交。** 旧再次验证邮件配置和容量保持原样；`git diff --check`后提交 `feat: 增加隔离的邮箱变更验证码发送`。

### 任务4：账户快照与发送业务协调

**Files:** AccountSnapshot、修改UserRepository、SnapshotReader、RequestService、邮箱未变化/已占用异常；SnapshotReaderTest、RequestServiceTest。

**Interfaces:** Repository新增`Optional<EmailChangeAccountSnapshot> findEmailChangeSnapshot(@Param("userId") Integer userId)`，JPQL精确投影四字段；`Optional<Integer> findEmailOwnerId(@Param("email") String email)`使用数据库原排序规则。SnapshotReader提供`EmailChangeBinding loadBinding(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, String normalizedNewEmail)`和`void requireTargetAvailable(EmailChangeBinding binding)`，各自短只读TransactionTemplate包住读取及完成，故障统一转本用途503。Service提供`EmailChangeRequestResponse request(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, Supplier<EmailChangeRequest> input, String clientIp)`。

最小断言锚点：`should_not_parse_or_reserve_when_recent_authentication_is_missing()`使用`assertThrows(ReauthenticationRequiredException.class, () -> service.request(actor, legacySession, input, ip))`后`verifyNoInteractions(input, snapshots, store, delivery)`；`should_keep_admission_when_parsing_crosses_window()`让input移动测试Clock，再断言`assertEquals(reservation.expiresAt(), response.expiresAt())`且activate恰好一次。

- [ ] **步骤1：写协调失败测试。** input Supplier带调用计数；超窗/缺auth_time不得调用input、DB、Redis或邮件，合格后input只调用一次。入口恰好300秒、解析后推进Clock至301秒仍继续；SMTP后再推进时间也不重复门禁。成功调用顺序为guard→input→loadBinding→requireTargetAvailable→reserve→prepare→delivery→loadBinding→requireTargetAvailable→activate；响应ID对应摘要和原到期。
- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.service.EmailChangeSnapshotReaderTest" --tests "org.example.music.user.service.EmailChangeRequestServiceTest"`。
- [ ] **步骤3：实现快照读取和归属判定。** loadBinding校验ID/角色/版本、规范化数据库原邮箱，null/空白→null，非空坏地址→503；账户消失或版本失效沿用CurrentUserRevokedException，不升级登录角色。requireTargetAvailable先比较规范化原/新邮箱，再查询ownerId：命中本人→EMAIL_UNCHANGED，命中他人→EMAIL_ALREADY_EXISTS，无命中通过；不加载完整Entity/密码。捕获事务启动/查询/完成故障，不用@Transactional方法内部catch遗漏提交失败。
- [ ] **步骤4：实现协调与清理。** guard使用一次Clock，再调用input完成Controller提供的解析；生成32随机字节challengeId、SHA-256摘要和六位ASCII码，reserve后BCrypt哈希，再prepare和发送。发信后loadBinding与原绑定比较，变化先409，再查目标可用性后activate；标记激活确认后才返回201 DTO。finally仅在已尝试准入且未确认激活时按本binding/digest取消pending；清理异常安全日志且不覆盖主异常，不退款、不恢复ACTIVE。
- [ ] **步骤5：补齐故障验证并跑全绿。** 初次查库/输入失败不reserve；reserve实际执行后丢回包仍尝试归属清理；哈希、发送、复核、prepare/activate异常各走对应边界；activate执行后丢回包返回503且不恢复旧ACTIVE。并发改原邮箱、版本、角色、账户删除、目标被占用均覆盖；即使final cleanup失败也不把PREPARED当有效凭据。快照事务开始和完成失败都503；未知异常以脱敏包装进入500。
- [ ] **步骤6：检查并提交。** `git diff --check`，提交 `feat: 协调本人邮箱变更申请与绑定复核`；不注入JwtTokenService，不提供任何账户更新方法。

### 任务5：HTTP入口、专用错误映射与API说明

**Files:** Controller、GlobalExceptionHandler、ControllerTest、docs/api/user/request-email-change.md。

**Interfaces:** `ResponseEntity<EmailChangeRequestResponse> request(HttpServletRequest request, Authentication authentication)`，Controller提取actor/session及ClientIpResolver结果，调用`service.request(actor,session,() -> reader.read(request),ip)`，不在Controller计算窗口或做邮箱业务判定。映射不声明consumes，保证近期门禁先于严格媒体类型解析；请求体OpenAPI手动声明application/json及EmailChangeRequest。

最小断言锚点：`should_return_stable_415_for_unsupported_media_type()`对近期有效用户的text/plain请求断言`status().isUnsupportedMediaType()`、`jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE")`、`jsonPath("$.type").value("urn:problem:music:unsupported_media_type")`和`content().contentTypeCompatibleWith("application/problem+json")`。

- [ ] **步骤1：写HTTP失败测试。** 201恰好两个字段、no-store、无Location，USER/ADMIN本人；401 REAUTHENTICATION_REQUIRED不解析非法正文；近期有效时错误媒体类型415 UNSUPPORTED_MEDIA_TYPE，type精确为urn:problem:music:unsupported_media_type。其余400/409三码/429（Retry-After）/502/503/500全部断言status、ProblemDetail媒体类型、code/type且无秘密。无认证与已认证但角色不再允许，沿用现有认证错误；不伪造“其他用户参数可访问”的场景。
- [ ] **步骤2：运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.EmailChangeRequestControllerTest"`。
- [ ] **步骤3：实现唯一入口与专用advice映射。** 增加本用途异常处理，415只处理EmailChangeUnsupportedMediaTypeException，不加跨接口HttpMediaTypeNotSupportedException处理。用既有problem构造器生成小写下划线type；429传正整数Retry-After；502/503日志安全。不改SecurityConfig、TokenVersionAuthenticationFilter或旧映射。
- [ ] **步骤4：补齐OpenAPI与中文API文档。** 写Tag/Operation/Schema/ApiResponse，覆盖201、400、401、403、409、415、429、500、502、503及no-store/Retry-After，说明普通JWT过期/签名错误沿用现状，不能假定全部有ProblemDetail。文档给请求规范化、三层限流、失败不退额、发送不保证送达、结果未知、替换规则、未绑定可用、curl、返回示例；确认接口只作后续说明，不虚构路径。
- [ ] **步骤5：跑本任务及旧读取器/Controller回归。** `cmd /c gradlew.bat test --tests "org.example.music.user.controller.EmailChangeRequestControllerTest" --tests "org.example.music.user.controller.ReauthenticationRequestReaderTest" --tests "org.example.music.user.controller.ReauthenticationControllerTest" --tests "org.example.music.user.controller.ReauthenticationEmailRequestControllerTest"`；新415准确，旧端点媒体类型契约不变。检查OpenAPI生成结果，不只核对注释文字。
- [ ] **步骤6：检查并提交。** `git diff --check`，提交 `feat: 暴露本人邮箱变更验证码申请接口`。这一步全绿仍不能宣称接口完成，须任务6真实验证。

### 任务6：真实链路、并发故障、部署说明与实现送审

**Files:** EmailChangeRequestIntegrationTest、必要时扩充StoreIntegrationTest、docs/deployment/email-change-requests.md、实现审核记录及本计划执行记录。

**Interfaces:** 使用既有@SpringBootTest/@AutoConfigureMockMvc/@EnabledIfDockerAvailable/Testcontainers模式，MySQL 8.4和Redis 8.10.2真实运行，受控EmailChangeEmailSender替代真实收件箱，不mock数据库或Redis成功。使用真实签名JWT、BCrypt与过滤链，容器连接和秘密使用既有测试配置机制。

最小断言锚点：`should_send_to_new_email_without_writing_account_or_issuing_token()`在真实POST成功后使用`assertEquals(before, jdbc.queryForMap("select * from users where id=?", userId))`及`assertTrue(hasher.matches(sent.code(), activeCodeHash))`；`should_return_unchanged_for_self_under_mysql_collation()`以重音等同但文本不同的合法地址命中本人，断言`jsonPath("$.code").value("EMAIL_UNCHANGED")`。

- [ ] **步骤1：写真实入口成功及失败测试，先运行红灯。** `cmd /c gradlew.bat test --tests "org.example.music.user.EmailChangeRequestIntegrationTest"`。断言近期USER/ADMIN、未绑定首次申请成功，真实邮件地址为规范化新邮箱；校验Redis摘要匹配及原300000ms期限，数据库整行前后相等，Token服务无签发调用，旧/其他Token仍普通有效。伪造userId/邮箱/版本/转发头不改变本人或IP来源。
- [ ] **步骤2：补齐真实认证/占用交错。** 使用可控Clock签发兼容既有iat/exp的真实Token，验证299秒、300秒、超窗和legacy缺auth_time；近期门禁缺失的旧接口仍可用，旧Token在再次验证成功后仍保留旧时间，新Token可继续。已过期/撤销与REAUTHENTICATION_REQUIRED分开。真实MySQL下使用大小写及重音等同地址对比数据库查询结果，命中自己409未变化、他人409已占用，HTTP拒绝无发信无扣额。
- [ ] **步骤3：补齐真实状态并发与故障。** 用户/目标/IP共享额度、目标跨账户并发、拒绝无部分扣额；发送失败保留旧ACTIVE，成功换目标替换旧请求。闩锁控制发信中变更账户/占用目标、租约失效、旧清理与新申请交错；spy仅在真实Redis执行后注入回包丢失，不以mock成功伪装执行。覆盖reserve未知、activate未知、迟到SMTP、清理故障；断言故障后用户表不变，结果未知不恢复旧状态。所有任务结束、容器和线程可重复清理。
- [ ] **步骤4：运行真实链路及Redis测试至全绿。** `cmd /c gradlew.bat test --tests "org.example.music.user.EmailChangeRequestIntegrationTest" --tests "org.example.music.user.EmailChangeRequestStoreIntegrationTest"`，列明实际通过/失败/错误/跳过。若Docker不可用导致跳过，恢复依赖后实跑，不写“通过”。
- [ ] **步骤5：写部署与操作边界。** 文档写Redis需支持PEXPIRETIME（当前Compose Redis7.4已满足）、单Redis假设及私有命名空间、三层额度非全局总额度、代理remoteAddr瓶颈、独立4槽容量、SMTP各5秒及20秒预算、应用与Redis时钟职责、TLS/发件配置外部化；说明503/超时可能收无效邮件、响应丢失冷却后重发，无生产测试绕过或真实凭据示例。
- [ ] **步骤6：执行最终基准。** PowerShell设置`$env:MINIO_INTEGRATION_TESTS = "true"`后运行`.\gradlew.bat cleanTest check --console=plain`，强制重跑而非沿用历史UP-TO-DATE结果。聚合build/test-results/test/TEST-*.xml：通过=tests−failures−errors−skipped，分别报告四项及类数；跳过须列类#方法和原因。失败修复后按受影响范围验证并再次完成全量，不能预填1285作为新结果。
- [ ] **步骤7：检查范围并整理审核材料。** `git diff --check`、`git status --short`（含untracked）、对照基线36cf271的文件和迁移差异；无新增独立接口、无配置/依赖/已执行迁移变化。实现审核材料按AGENTS.md第2/14节列Method/Path、需求权限、行为、全部文件、无迁移、API路径、实际命令及四项数量、基线和curl、默认项/风险、NOTE-001逐项处置；本阶段标明“待Claude独立实现审核”，不填accepted。
- [ ] **步骤8：提交并等待独立实现审核。** 仅暂存本任务实际文件，提交 `test: 验证邮箱变更申请真实链路与故障边界`；若文档需独立提交用中文消息。实现由Codex完成后交Claude只读审核，不向未授权服务发消息、不自动推送；收到needs_revision先逐项复核，第三轮规则与escalated边界按AGENTS.md执行。

## 计划自查与交接

设计§1/2→全局约束及文件表；§3/4→任务1/4/5/6；§5→任务4/6；§6→任务2/4/5/6；§7→任务2/6；§8→任务3/4/6；§9→任务1/5/6；§10仅保留兼容数据与原始错误次数，不实现最终确认；§11→任务5/6。NOTE-001映射、媒体类型回归及下划线type由任务1/5/6闭环。

已核对各任务使用的类名、record字段、Supplier输入和四个存储操作签名一致；五项Review Focus均有归属测试，暂无未覆盖的设计要求。每个任务按红灯、最小实现、绿灯、范围检查及中文提交执行；不为计划阶段创建空生产类或假实现。

本轮只编写和自查计划、修正文档声明并登记审核处置；未运行新接口测试或构建。请用户审阅本计划是否准确表达需求；批准后按既定Codex唯一实现、Claude独立只读审核方式继续。
