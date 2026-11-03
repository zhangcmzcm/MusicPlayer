# 再次验证身份邮件请求接口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.
>
> 按既定方式由Codex唯一实现，Claude独立只读审核，不派发实现子代理；缺失的执行子技能不声称已调用。用户确认本书面计划后才编码。

**Goal:** 实现POST /api/v1/users/me/reauthentication-email-requests，向本人当前邮箱发送专用身份验证码，发送确认后原子激活唯一请求，不修改账户或认证时间。

**Architecture:** 可信主体/版本与只读账户快照核对后，由Redis Lua原子准入并预留；BCrypt完成后置PREPARED，独立有界发送确认、再次复核绑定，最后按归属CAS激活。邮件任务不持有激活能力；所有租约/有效期锚定准入Lua的Redis TIME。

**Tech Stack:** Java21、Spring Boot4.1.1、既有Spring Data Redis/JPA/JavaMail、MySQL8.4、Redis7.4（Compose）及8.10.2测试镜像、JUnit5/MockMvc/Testcontainers、BCrypt/JWT；不新增依赖或配置项。

**Spec:** [书面设计](../specs/2026-11-03-reauthentication-email-request-design.md)，bad68f3已获第1轮设计审核accepted，零阻断/两非阻断。[设计反馈记录](../../reviews/2026-11-03-reauthentication-email-request-design-review.md)。代码基线22af54e，999/0/0/0、88类；当前HEAD仅比代码基线多设计文档，不把基线测试当成新接口证明。

## 全局约束

- 唯一接口POST /api/v1/users/me/reauthentication-email-requests；普通有效Bearer JWT、USER/ADMIN本人，不要求近期auth_time；只从主体/可信上下文/DB取ID、版本、当前邮箱。
- 零字节请求体合法，任何非空正文400；无业务参数，头/查询不能指定收件人。201/no-store仅challengeId、UTC Instant expiresAt，不编造GET或Location。
- 每人60秒冷却、每UTC自然日10次、每连接IP固定60秒窗口5次；同一Lua检查并计数/预留，超限不改状态，失败不退已消费额度。
- 验证码6位ASCII数字、BCrypt摘要；challengeId为32随机字节Base64URL无填充，Redis只存摘要；本人＋规范邮箱＋可信版本绑定，单active和最多单pending。
- **预留/准入时间**只指reserve.lua读取Redis TIME的时刻；expiresAt=reservedAt+300000ms，leaseUntil=reservedAt+60000ms。哈希完成、发信、重试、激活都不续命。
- 发送执行器4槽、零队列，等待SMTP结果20秒，连接/读/写分别5000ms；取消不等于已终止底层发送，实际任务退出前槽位不释放给额外线程。任务无Redis/DB能力。
- 不写用户表、不更新auth_time/版本、不签Token；MySQL复核和Redis激活非跨存储原子。后续兑换仍须复核绑定，本次不实现验证或其他后续接口。
- REGISTER/LOGIN公开枚举、现有完整发送/消费/限流、JWT/过滤器/主体、既有SMTP客户端行为不变；无迁移/依赖/配置项/Actuator新增，V1—V8不动。
- 中文文档、提交和审核材料；基准check、必要MySQL/Redis真容器不跳过。不得记录验证码、摘要、完整邮箱、challengeId或原始SMTP/SQL/Redis异常链。

## 审核关注点

1. 哈希/SMTP慢不能把期限改成PREPARED或确认时起算；任务1/3检查绝对到期与预留时间。
2. 超时Future可能仍占用真实线程，迟到结果不能激活或释放虚假容量；任务2有真实4槽阻塞测试，任务3/5测状态隔离。
3. Redis回包未知可能已计数/预留/激活，不能当成功、退额度或恢复旧active；任务1/3/5分别验证。
4. 无Content-Length或chunked不能绕过零正文，旧JWT缺auth_time不能被误当需近期验证；任务4/5实际入口与真过滤链验证。
5. 当前邮箱/版本在发信后变化、旧清理覆盖新请求、共享代理IP瓶颈；任务3/5验证CAS绑定，任务6部署文档落实NOTE-001。

## 文件与职责

生产Java路径以src/main/java/org/example/music为前缀；全部新增，只有GlobalExceptionHandler作映射修改：

| 路径 | 职责/接口 |
| --- | --- |
| user/controller/ReauthenticationEmailRequestController.java | 零正文、主体/可信上下文/IP、201/no-store及OpenAPI |
| user/dto/ReauthenticationEmailRequestResponse.java | record(String challengeId, Instant expiresAt)，toString隐藏ID |
| user/domain/ReauthenticationEmailBinding.java | record(Integer userId, String email, long tokenVersion)，隐藏邮箱 |
| user/domain/ReauthenticationEmailReservation.java | record(String challengeDigest, Instant reservedAt, Instant expiresAt, Instant leaseUntil)，隐藏摘要 |
| user/service/ReauthenticationEmailRequestService.java | 协调准入→哈希→发送→复核→激活；finally归属清理 |
| user/repository/ReauthenticationEmailRequestStore.java | 四操作Lua、严格回包、私有状态与配额，不发信 |
| user/email/ReauthenticationEmailMessage.java | record(String recipient, String code, Instant expiresAt)，敏感toString脱敏 |
| user/email/ReauthenticationEmailSender.java | void send(ReauthenticationEmailMessage message)，仅同步邮件传输SPI |
| user/email/IsolatedReauthenticationMailClientFactory.java | 从已有JavaMailSenderImpl复制隔离客户端，不注册第二个公共JavaMailSender bean |
| user/email/SmtpReauthenticationEmailSender.java | 专用MIME/用途，捕获邮件发送异常 |
| user/email/BoundedReauthenticationEmailDelivery.java | 独立4槽零队列执行器、20秒等待、取消及生命周期 |
| user/exception/ReauthenticationEmailInvalidRequestException.java | 400 VALIDATION_FAILED |
| user/exception/CurrentEmailNotBoundException.java | 409 CURRENT_EMAIL_NOT_BOUND |
| user/exception/ReauthenticationEmailRequestConflictException.java | 409 REAUTH_EMAIL_REQUEST_CONFLICT |
| user/exception/ReauthenticationEmailRateLimitedException.java | 429 REAUTH_EMAIL_RATE_LIMITED，正retryAfterSeconds |
| user/exception/ReauthenticationEmailSendFailedException.java | 502 EMAIL_SEND_FAILED，独立类型，不进入旧发送异常的原链日志 |
| user/exception/ReauthenticationEmailRequestUnavailableException.java | 503 REAUTH_EMAIL_REQUEST_UNAVAILABLE |
| user/exception/ReauthenticationEmailDiagnostics.java | 仅本任务复用的安全Throwable副本：通用消息、原调用位置，无cause/suppressed/原消息 |
| common/exception/GlobalExceptionHandler.java（修改） | 六个新异常映射及安全诊断，不改既有映射 |

新增资源目录src/main/resources/redis/reauthentication-email/：common.lua、reserve.lua、prepare.lua、activate.lua、cancel.lua。仓储按classpath读取common前缀拼接各脚本；资源缺失即启动失败，不留空脚本或开发机路径。

新增测试路径以src/test/java/org/example/music为前缀：

- user/repository/ReauthenticationEmailRequestStoreTest.java：回包/异常翻译。
- user/ReauthenticationEmailRequestStoreIntegrationTest.java：真Redis脚本、配额、状态CAS/TTL。
- user/service/ReauthenticationEmailRequestServiceTest.java：流程、前后绑定、清理和隐私。
- user/email/IsolatedReauthenticationMailClientFactoryTest.java：复制与隔离。
- user/email/SmtpReauthenticationEmailSenderTest.java：真实MimeMessage配受控JavaMailSender。
- user/email/BoundedReauthenticationEmailDeliveryTest.java：真实有界线程/超时，均有释放清理。
- user/controller/ReauthenticationEmailRequestControllerTest.java：HTTP与错误契约。
- user/ReauthenticationEmailRequestIntegrationTest.java：真MySQL/Redis/BCrypt/签名JWT，受控邮件边界，不mock外部状态存储。

文档新增docs/api/user/request-reauthentication-email.md、docs/deployment/reauthentication-email-requests.md、docs/reviews/2026-11-03-reauthentication-email-request-review.md；本计划和设计反馈记录随执行更新。没有发送之外的Controller或生产读取/兑换调试入口。

### 任务1：Redis原子准入、状态与绝对期限

**接口：** 构造器注入StringRedisTemplate。`ReauthenticationEmailReservation reserve(ReauthenticationEmailBinding binding, String challengeDigest, String ipDigest)`；`void prepare(ReauthenticationEmailBinding binding, ReauthenticationEmailReservation reservation, String codeHash)`；`void activate(ReauthenticationEmailBinding binding, ReauthenticationEmailReservation reservation)`；`void cancel(ReauthenticationEmailBinding binding, String challengeDigest)`。业务冲突抛409、额度抛429、存储/回包损坏抛503。cancel只删除匹配归属pending，不修改active和计数。

- [x] 先写仓储回包测试与真Redis测试：首次预留、无active改变、PREPARING不可当active、prepare保持原时间、激活唯一active、取消不恢复旧请求；0/空/null/错类型/未知状态/异常时间回包与DataAccessException均fail-closed。
- [x] 同一个共同Lua验证层固定schema：pending八字段state/challengeDigest/userId/email/tokenVersion/reservedAtMs/expiresAtMs/leaseUntilMs；PREPARED再加codeHash；active为state=ACTIVE、challengeDigest/userId/email/tokenVersion/reservedAtMs/expiresAtMs/codeHash/attempts（初始化0）。userId为正Integer、attempts为规范非负Integer，codeHash须符合BCrypt编码形态，摘要为43位Base64URL。严格HLEN/TYPE后再读，拒绝额外/缺失/错误类型/非规范数值，不对无界hash直接HGETALL；这里只验证计数字段结构，不提前实现后续兑换次数门禁。
- [x] 固定key前缀music:auth:reauth-email:，尾部为cooldown:<userId>、daily:<userId>、ip:<ipDigest>、pending:<userId>、active:<userId>。配额hash只存count/resetAtMs（cooldown仅resetAtMs）；pending/active过期与各字段相符。tokenVersion按规范十进制文本/长度字典序检查至Long.MAX_VALUE，不能经Lua double丢失精度；毫秒时间限制在安全整数范围，按规范十进制保存，不用可能输出科学计数的tostring格式化大时间。
- [x] reserve.lua对五个key先检查既有类型、schema、期限再改写，读取TIME为整数毫秒reservedAt；日界为(floor(reservedAt/86400000)+1)*86400000，IP首次窗口60000ms、用户冷却60000ms。配额达到上限不再递增；阻塞时返回最大等待秒数且不写入。获准时同时计数、设绝对到期并写唯一PREPARING；已有live pending且冷却状态不相符视异常503，不抢占。
- [x] 使用PEXPIREAT及PEXPIRETIME校验实际绝对到期与记录一致，无TTL/提前或异常延长均503，不自动修复。此命令Redis7.0起支持，当前Compose7.4满足，[官方命令说明](https://redis.io/docs/latest/commands/pexpiretime/)；不新增Redis服务或Cluster兼容改造。
- [x] prepare.lua要求本归属、PREPARING、租约有效，写codeHash转PREPARED且不重设租约。activate.lua读取pending/active，要求本归属PREPARED及原期限有效，原子替换active（attempts=0）、按原expiresAt绝对到期、删本pending。cancel.lua仅在绑定＋digest匹配时删pending；无/不同归属noop，坏状态503，不无条件DEL。
- [x] 统一RESP协议：reserve数组[0,reservedAtMs,expiresAtMs,leaseUntilMs]成功，[1,retryAfterSeconds,0,0]限流，[2,0,0,0]损坏；其余或null异常503。prepare/activate数组[0]成功、[1]冲突、[2]损坏；cancel数组[0]完成/noop、[2]损坏。Java严格检查长度/Long类型/取值/300000及60000差值和范围，不能默认成功。
- [x] 写限流边界/损坏/并发测试：每人60秒、UTC日10次、IP固定窗5次，同用户跨Token共享、不同用户共IP；拒绝不改计数或延长TTL，准备/发信/失败不退额度。UTC日界用同一纯Lua日界helper测试00:00及23:59:59.999，再核对实际TIME预留的resetAt；不在生产脚本增加测试时间覆盖参数。真实过期用Redis过期指令/合法过去期限夹具，不sleep、不改Java Clock假装Redis过期。
- [x] 执行 `cmd /c gradlew.bat test --tests "org.example.music.user.repository.ReauthenticationEmailRequestStoreTest" --tests "org.example.music.user.ReauthenticationEmailRequestStoreIntegrationTest"`，先记录未实现红灯再补全上述实现，所有真实用例通过后中文提交 `feat: 增加再次验证邮件原子请求存储`。

### 任务2：隔离SMTP封装与真实有界执行器

**接口：** factory提供 `JavaMailSenderImpl createClient()`；SmtpReauthenticationEmailSender实现ReauthenticationEmailSender.send。BoundedReauthenticationEmailDelivery公开生产构造器仅注入transport，提供 `void send(ReauthenticationEmailMessage message)`；包内测试构造器接收ExecutorService和Duration，不新增外部配置。生产固定4槽/SynchronousQueue/AbortPolicy/20秒，应用关闭停止接单并shutdownNow。

- [x] 工厂测试使用真实JavaMailSenderImpl源：复制host/port/protocol/username/password/defaultEncoding及Session properties，源对象和源Properties完全不变，新Session独立。新客户端smtp/smtps连接、read、write各5000ms，禁用mail.debug；TLS/auth参数保留，敏感配置不日志输出。源不是JavaMailSenderImpl时以通用启动错误拒绝，不静默退回无超时共享客户端。
- [x] MIME测试捕获真实MimeMessage，收件人仅Message中的DB地址，UTF-8文案明确再次验证而非直接改邮箱，包含6位码和原expiresAt；没有Token/密码。MailException/MessagingException→专用502异常，不透传原消息。
- [x] 执行器测试先写4个实际阻塞任务、第五个立即503；20秒等待预算使用包内测试短预算验证超时，不等待真实20秒。不可中断的受控任务在逻辑超时后继续占槽，再次请求仍503；迟到任务只发信，无DB/Redis/激活依赖。finally释放所有闩锁、结束线程，不sleep。
- [x] 实现等待Future.get：超时/取消→502，InterruptedException恢复中断标记并请求取消；RejectedExecutionException→503。ExecutionException解包已知发送失败，未知运行故障以安全诊断包装后保持500。Future.cancel不等同任务退出，禁止超时后另开线程补偿或手动提前释放容量。
- [x] Message/Response/Binding/Reservation字符串表示脱敏。新增ReauthenticationEmailDiagnostics.sanitized(Throwable)供本任务错误/清理使用，复制安全调用位置，去掉原消息、cause、suppressed；不重构既有诊断工具。
- [x] 运行三个email包测试类，先观察未实现失败，再完成实现并全部通过；中文提交 `feat: 增加有界再次验证邮件发送`。

### 任务3：绑定核对与发信后激活协调

**接口：** `ReauthenticationEmailRequestResponse ReauthenticationEmailRequestService.request(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, String clientIp)`；注入UserRepository、ReauthenticationEmailRequestStore、SecureVerificationCodeGenerator、PasswordHasher、BoundedReauthenticationEmailDelivery。无覆盖整个方法的事务，不修改UserRepository或用户实体。

- [x] Service测试先写无绑定409、坏邮箱503、上下文缺失/负版本401、账户/版本/角色失效401，全部在Redis准入/哈希/发信前拒绝。复用findAuthorizationSnapshot；normalize为trim＋Locale.ROOT小写，非空地址用InternetAddress严格校验，异常存储值不回显。
- [x] 写调用顺序：首轮快照→生成32随机字节challengeId及SHA256/Base64URL摘要→reserve→生成6位码/hash→prepare→delivery.send→第二轮快照→activate→返回两字段。仅digest进Redis。现有生成器依默认Locale格式化，新的私有ASCII转换将其六个十进制数字逐个映射到0—9，非法输出500；不修改共享生成器或归一化用户输入。测试阿拉伯数字夹具的输出仍为ASCII并保留前导零，熵不由固定值替代。
- [x] 写后复核：账户/版本/角色变化401；邮箱未改版本却变化/解绑409；数据库故障503；所有已准入失败不补偿。激活未知503不伪称旧active保留，known租约/归属冲突409，不重试。
- [x] 实现finally：从开始尝试reserve起保存本digest；未确认激活时尽力cancel(binding,digest)，包含reserve回包未知的情况，绝不恢复active或退额度。清理失败只记安全诊断，不覆盖原502/409/503/500；成功激活后无取消。
- [x] 意外哈希/随机输出等未知运行故障保持500但以通用消息和安全Throwable包装，避免GlobalExceptionHandler兜底原链泄漏；已知业务异常不误包装。测试嵌套/抑制异常中带完整邮箱、码、摘要、ID的响应/日志均不泄漏。
- [x] 执行 `cmd /c gradlew.bat test --tests "org.example.music.user.service.ReauthenticationEmailRequestServiceTest"`，红灯→实现→通过；中文提交 `feat: 协调再次验证邮件发送与归属激活`。

### 任务4：无正文HTTP契约与统一错误映射

**接口：** Controller为POST固定路径，响应application/json，无consumes限制。`ResponseEntity<ReauthenticationEmailRequestResponse> request(HttpServletRequest request, Authentication authentication)`：通过首字节read检查真实输入流，非EOF或读取异常400；从认证对象取actor和类型化session，ClientIpResolver取连接地址；调Service返回201/no-store。这是无字段请求的等价入口校验，不创建假的空请求DTO。

- [x] MVC测试：零字节201仅两字段，旧authenticatedAt=null仍可调用；空白/{} /null/任意字段/大正文400且Service无调用，缺Content-Length和chunked也不能绕过首字节检查；不读取整个正文。无认证401、错误details401；参数/头部伪造不改变actor/版本/IP或收件人。
- [x] 六个新异常映射：400 VALIDATION_FAILED、409 CURRENT_EMAIL_NOT_BOUND/REAUTH_EMAIL_REQUEST_CONFLICT、429 REAUTH_EMAIL_RATE_LIMITED＋Retry-After、502 EMAIL_SEND_FAILED、503 REAUTH_EMAIL_REQUEST_UNAVAILABLE；已有InvalidToken/CurrentUserRevoked与认证链错误复用，未知500。全部业务错误ProblemDetail、下划线urn、no-store、无敏感信息。
- [x] 新发送失败类型独立于旧EmailSendFailedException，添加本接口专门映射、安全诊断；原公开发送异常映射不动。用ListAppender断言cause/suppressed含邮箱/码/摘要/ID时日志不泄漏。
- [x] 添加Tag/Operation/Schema/ApiResponse，声明普通有效JWT、无正文、201、400/401/409/429/502/503/500、Retry-After、SMTP接受不等于送达、结果不确定性；不得描述尚未实现的兑换端点为可用。
- [x] 运行 `cmd /c gradlew.bat test --tests "org.example.music.user.controller.ReauthenticationEmailRequestControllerTest"`，红灯→实现→通过；中文提交 `feat: 暴露本人再次验证邮件请求接口`。

### 任务5：真实过滤链与跨存储/迟到结果验证

**文件：** ReauthenticationEmailRequestIntegrationTest使用@SpringBootTest/MockMvc、真实MySQL8.4/Redis8.10.2/BCrypt/JWT。只控制专用邮件边界，Redis和数据库不能mock替代正常流程；SMTP封装与执行器的真实边界由任务2单测证明。

- [x] USER/ADMIN、带auth_time与缺失auth_time的有效签名Token均成功；当前邮箱来自DB，伪造body400，查询/头部无效；no-email409；错误签名/过期/声明/版本撤销401且无发送。整行比较用户表不变，源码/spy证明未签新Token或更新auth_time。
- [x] 201后读私有Redis状态验证只有一个ACTIVE、codeHash可真实匹配受控邮件捕获的ASCII码，明文/原challengeId不存Redis，摘要与响应ID对应，IP key非原地址；响应只有challengeId/expiresAt，不添加生产查询测试接口。
- [x] 旧active存在时，阻塞新发送，观察PREPARED不可替换active；失败502保持旧active原字段/到期，不恢复已替换记录。成功201才替换，active绝对到期等于reserve返回值而非SMTP确认时间；prepare/激活不刷新租约或300秒有效期。
- [x] 用真实Redis过期指令模拟旧pending/冷却已经过期，再准入新请求；新请求激活后释放旧发送，旧请求409，旧取消/迟到结果不能覆盖或删新active。另测真实并发同用户只有一个准入，其余429，满足单pending/active；不依赖sleep。
- [x] 首查与邮件后复核间修改角色/版本/邮箱/删除账户，断言401或409且不激活；受控Redis/DB故障503、回包未知可能已激活不恢复、清理失败不掩盖主错误，额度保留。故障注入要区分实际执行后丢回包与未执行，不把mock空值当作真实网络丢确认。
- [x] 控制邮件边界模拟超时502及满载503后无激活；与任务2物理4槽和不可中断迟到任务测试共同证明资源有界。所有线程/闩锁/容器数据都有finally清理和超时；不把受控SMTP成功说成真实外部邮箱送达。
- [x] 执行本任务集成及Store集成、既有EmailSendCodeIntegrationTest、AuthTimeBindingIntegrationTest、PasswordChangeIntegrationTest，正常MySQL/Redis/BCrypt/JWT链全实跑；中文提交 `test: 验证再次验证邮件请求并发与故障边界`。

### 任务6：文档、设计意见与完整送审

- [x] API文档固定Method/Path、无正文、身份、仅当前邮箱、所有状态码/示例、准入锚定期限、三个限流桶、不退额度、SMTP接受/超时/响应丢失语义、后续双方式契约但未实现，不含真实凭据。提供POSIX/PowerShell无正文curl。
- [x] NOTE-001落docs/deployment/reauthentication-email-requests.md：当前remoteAddr模式在无real-IP配置时，会把所有经同一代理的用户压入同一5次/60秒桶，形成共享可用性瓶颈。部署前留存直连/代理路径下两个用户实际连接地址、桶共享/隔离的烟测结果，确认是否符合预期；不能只填“已确认代理”。本任务不信任任意X-Forwarded-For、不加代理配置开关，若需改可信代理规则另立任务。
- [x] NOTE-002统一“预留/准入时间”；标明Redis7+权限需允许TIME/PEXPIREAT/PEXPIRETIME/相关hash与Lua操作，既有Compose7.4满足；SMTP隔离客户端前提JavaMailSenderImpl、4槽/零队列/20秒等待和网络超时仅约束本封装。代码测试不替代真实邮件与部署验收。
- [x] git diff --check，列全部文件含untracked，无迁移/公开枚举/原发送Service/安全链/其他接口变化。执行 `.\gradlew.bat check`，未实际运行则cleanTest check；建议直接设置MINIO_INTEGRATION_TESTS=true做全量，若触及track/storage测试则此设置必需。统计新XML通过/失败/错误/跳过及类数，必要集成跳过不能声称完成。
- [x] 编写docs/reviews/2026-11-03-reauthentication-email-request-review.md，按AGENTS完整列Method/Path/权限、声称行为、全文件清单、无迁移、API/部署路径、实际命令/数量、实现提交/基线、curl与风险；逐项回应设计NOTE编号和原分级，首次实现审核不涉及旧实现反馈。
- [x] 更新本计划和设计反馈处置记录，中文提交 `docs: 登记再次验证邮件请求验证与审核材料`；交用户提供Claude独立实现审核，不将自验或设计accepted当实现accepted，不推进下一接口，不自动push。

## 计划自审

已将设计各节映射到任务：Redis状态/时间/额度在任务1，有界邮件在任务2，账户与故障协调在任务3，HTTP/错误在任务4，真实并发/过滤链在任务5，文档与NOTE-001/002/基准在任务6。已固定类/接口、隔离客户端复制、Lua时间/回包与线程权限边界，无使用now回填、无隐式续期或提前改邮箱。未来兑换不在本次范围，不为测试制造生产兑换入口。用户已确认本计划并授权实施；执行完成，自验及首次独立实现审核均通过。

## 实施结果（2026-11-03）

实现提交：b4f9113、d06767e、5cb1bfb、3e36944、3d1f6d0，送审材料6fc1ddf。Codex带MINIO_INTEGRATION_TESTS=true的cleanTest check --console=plain实际通过，9分47秒；1105通过/0失败/0错误/0跳过、96类，本任务8类106项。Claude已独立重跑同等全量验证并给出第1/3轮accepted，零阻断、零非阻断；设计NOTE-001代码侧关闭、生产代理烟测另行跟踪，NOTE-002关闭。用户已授权推送；本次仅登记审核闭环，不开始后续接口。无迁移、依赖或受版本控制的配置变更。

执行顺序偏差：仓储/工厂/Service/Controller的首次红灯是缺类编译失败，不能称作业务断言先红；真实Redis、MIME及物理线程测试在同阶段补齐，未逐项严格先测试后实现。测试lambda括号和HTTP重复设桩错误已修正。checkbox表示该步最终交付物/验证已落实，不代表每个过程顺序逐字执行；完整命令、计数、范围、设计意见处置与边界见[首次实现送审材料](../../reviews/2026-11-03-reauthentication-email-request-review.md)。
