# 登录认证时间绑定加固实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式沿用项目约定：Codex唯一实现，Claude独立只读审核，不派发实现子代理；缺失的执行子技能不声称已调用。用户已确认计划并授权实现；实现、文档与完整基准验证已完成，待Claude独立实现审核。

**Goal:** 为现有POST /api/v1/auth/login绑定并传递真实验证时间，JWT携带auth_time，兼容缺失时间的旧Token，严格拒绝非法时间，并在内部认证上下文保留已校验时间与版本。

**Architecture:** 验证服务按注入UTC时钟捕获秒级时间，显式经过不可变LoginPrincipal、五字段Ticket和签发接口传递；过滤器校验auth_time后发布类型化上下文。旧式签发显式改名，缺失时间不补now或iat；不引入会话存储或新业务门禁。

**Tech Stack:** Java21、Spring Boot4.1.1、既有Spring Security7.1.1/Nimbus10.9.1、Redis Lua、MySQL8.4、JUnit5/MockMvc/Testcontainers、BCrypt/JWT、Gradle Wrapper；不新增依赖。

**Spec:** [已accepted设计](../specs/2026-11-02-auth-time-binding-design.md)，设计提交8e652e4；独立设计审核第1轮accepted，零阻断，NOTE-001/002处置见[设计审核记录](../../reviews/2026-11-02-auth-time-binding-design-review.md)。实施前代码基线3a27229；当前HEAD含设计文档，运行时代码未变。

## 全局约束

- 仅现有登录认证时间绑定任务，不新增HTTP接口；不实现再次验证发送/完成、邮箱变更、refresh或300秒业务检查，不新增REAUTHENTICATION_REQUIRED映射。
- 请求/成功DTO、角色选择权限、tokenVersion复核与撤销、登录限流/失败计数、密码规则不变；不修改AuthenticatedUserPrincipal(Integer userId, UserRole loginRole)或UserAuthorizationSnapshot。
- 密码matches成功后立即记时；验证码validate成功后立即记时，consume成功才返回凭据。后续查询、角色选择、Ticket兑换和签发不刷新时间。
- auth_time为UTC Unix整数秒，按秒向下取整；缺失是旧Token，存在但null/字符串/小数/负数/溢出/未来/晚于iat均401 INVALID_TOKEN，不做默认补齐。
- 零未来时间容差是已确认取舍：不改变现有JWT其他时间声明容差，也不以运维偏差阈值放宽auth_time。
- 无迁移、新配置项、新依赖，不修改V1—V8、业务数据库字段、全局JSON绑定或歌曲生产代码。
- 因旧签发入口显式改名，允许下列测试夹具作编译适配；不改其业务断言。涉及track测试，最终必须设置MINIO_INTEGRATION_TESTS=true执行cleanTest check，跳过不计通过。
- 中文文档、提交、计划与审核材料；自验不是accepted，代码完成后独立按单任务送审。

## 审核关注点

1. 解码器可能宽松转换或补iat：实际原始载荷中的字符串/1.0/null/缺iat必须得到设计错误，而不是由测试构造器提前转换成合法值；任务1/4。
2. 零容差在秒边界可能误拒，正数时钟偏差阈值不能保证绝无误拒；任务1可控时钟复现、任务5部署验收明确风险。
3. 管理员选择角色或数据库延迟不能刷新auth_time；任务2/3/4覆盖多次推进时钟和旧验证版本撤销。
4. 遗留issue重载不能静默赋now，实际登录不能调用旧式签发；任务1显式改名、任务3交互验证及源码调用检查。
5. Authentication.details不能继承客户端或前序伪造对象；只有身份/角色/版本/时间通过后发布可信类型，旧Token缺失时间仍保持版本撤销；任务1/4。

## 文件与职责

生产路径以src/main/java/org/example/music为前缀：

| 操作 | 相对路径 | 职责 |
| --- | --- | --- |
| 修改 | config/SecurityConfig.java | 命名UTC Clock bean；仅对带auth_time声明保留真实iat缺失，不接受转换器补值 |
| 新增 | security/AuthenticatedSessionContext.java | 不可变已校验版本/可缺失时间，类型化读取Authentication.details |
| 修改 | security/JwtTokenService.java | 显式时间签发，旧式签发显式改名 |
| 修改 | security/TokenVersionAuthenticationFilter.java | 严格解析auth_time并发布内部上下文 |
| 修改 | user/domain/LoginPrincipal.java、user/domain/LoginTicketContext.java | 新增显式Instant authenticatedAt，无默认时间构造器 |
| 修改 | user/service/PasswordLoginService.java、user/service/EmailCodeLoginService.java | 记时点、保留原版本快照 |
| 修改 | user/service/LoginTicketService.java | 五字段原子发布/严格消费，原TTL和一次性语义 |
| 修改 | user/service/UserLoginService.java | 传递原认证时间，保留角色/版本复核 |
| 修改 | user/controller/AuthController.java | 仅同步登录OpenAPI描述 |

测试路径以src/test/java/org/example/music为前缀：

- 新增security/AuthenticatedSessionContextTest.java、user/AuthTimeBindingIntegrationTest.java。
- 扩展security/JwtTokenServiceTest.java、security/TokenVersionAuthenticationFilterTest.java。
- 扩展user/service/PasswordLoginServiceTest.java、EmailCodeLoginServiceTest.java、LoginTicketServiceTest.java、UserLoginServiceTest.java。
- 适配user/AuthVersionBindingIntegrationTest.java：有效Ticket夹具新增时间、JWT spy改四参；旧四字段作为显式拒绝用例保留，版本/角色/并发断言不删除。
- user/PasswordChangeIntegrationTest.java：仅旧式JWT夹具方法改名、签发spy四参适配，保留所有改密断言。
- 下列11个既有集成文件仅JWT测试夹具方法改名：user/CurrentUserIntegrationTest.java、user/CurrentUserUpdateIntegrationTest.java、user/UserRoleAuthorizationIntegrationTest.java；track/TrackStreamIntegrationTest.java、track/TrackPublishIntegrationTest.java、track/TrackListIntegrationTest.java、track/TrackDetailIntegrationTest.java、track/TrackDeleteIntegrationTest.java、track/TrackCoverIntegrationTest.java、track/TrackArchiveIntegrationTest.java、track/TrackAdminAuthenticationIntegrationTest.java。
- 如构造器编译适配还需其他文件，先按rg核对直接调用证据，再记录实际范围；不批量改不相关断言或格式。

文档：修改docs/api/auth/login.md；新增docs/deployment/auth-time-binding.md、docs/reviews/2026-11-02-auth-time-binding-review.md；本计划执行进度随实现更新。原设计审核回执独立文档，不混成实现accepted。

### 任务1：UTC时钟、显式签发与可信认证上下文

**接口：** SecurityConfig提供命名bean `Clock authenticationClock()`，值Clock.systemUTC()；相关构造器Clock参数统一使用@Qualifier("authenticationClock")。JwtTokenService新增 `JwtTokenResult issue(UserAuthorizationSnapshot snapshot, UserRole role, LoginType type, Instant authenticatedAt)`；原三参方法移除，改为 `JwtTokenResult issueLegacyWithoutAuthTime(UserAuthorizationSnapshot snapshot, UserRole role, LoginType type)`，没有生产登录调用。AuthenticatedSessionContext为 `record(long tokenVersion, Instant authenticatedAt)`，后者null明确表示旧Token，并提供 `static Optional<AuthenticatedSessionContext> from(Authentication authentication)`，仅已认证、principal为AuthenticatedUserPrincipal且details为该类型时返回。

任务1—3共享不可拆的签名迁移：按各任务先写红灯测试、依次实现，但完成任务3后才统一编译、执行这三个任务的测试并提交。任务1/2的末尾验证步骤推迟到这个联合验证点，不单独声称通过，也不提交中间不可编译状态；不为让中间状态编译而新增默认认证时间重载。

- [x] 先写JwtTokenServiceTest：显式时间签发后原始JSON auth_time为整数，值与输入按秒截断后相同；iat/exp使用当前Clock，旧验证时间不会被替换；null/负时间/未来/晚于iat拒绝签发。旧式方法仍校验角色与快照版本，JWT不带auth_time；不保留原三参issue或隐式now重载。
- [x] 写过滤器测试：缺失时间成功且上下文authenticatedAt为空，合法时间与tokenVersion保留；存在但null/字符串/Double/BigDecimal/布尔/数组/对象/负数/溢出/未来/晚于iat/缺iat均INVALID_TOKEN，无下游调用或新可信上下文。保留原版本撤销和角色限制测试。0及很旧时间允许普通访问。
- [x] 写AuthenticatedSessionContextTest：未认证、无details、错误details类型返回empty；合法类型可读。JWT Authentication上预放任意details、请求参数/头部时间均不能影响最终上下文；过滤器仅在全部校验通过后替换认证。
- [x] 增加时钟边界：签发节点01.010秒、验证节点00.990秒时，auth_time向下取整为01.000秒仍会被验证节点拒绝。固定/可变Clock测试，不sleep，不引入容差来让测试通过。
- [x] 运行 `cmd /c gradlew.bat test --tests "org.example.music.security.JwtTokenServiceTest" --tests "org.example.music.security.TokenVersionAuthenticationFilterTest" --tests "org.example.music.security.AuthenticatedSessionContextTest"` 确认未实现行为失败；编译红灯和行为红灯分别记录。
- [x] 实现Clock bean、签发接口与过滤器。auth_time存在时仅接受整数类型（Byte/Short/Integer/Long/可精确转long的BigInteger），不得对任意Number调用longValue或String.valueOf强制归一化；Instant范围及与iat/Clock比较失败映射既有INVALID_TOKEN。所有时间检查后才发布AuthenticatedSessionContext，不保留原始Token。
- [x] 在SecurityConfig既有NimbusJwtDecoder上保留默认claim转换，但转换前记录auth_time与iat是否真实存在；若auth_time存在且原iat缺失/null，转换后删除自动补出的iat，使过滤器明确拒绝。旧Token不带auth_time时，默认转换行为不变；不更改issuer/audience/HS256/exp/nbf验证。
- [x] 将文件表列出的旧JWT夹具调用逐处改成issueLegacyWithoutAuthTime；只是保留旧Token测试语义，禁止填now使旧夹具变成新验证。实际登录暂在任务3切换新接口，任务间接口迁移编译依赖可一并完成，但不能保留缺时间的交互登录作为最终状态。
- [x] 在任务3联合验证点重跑本任务定向测试全部通过；与任务2/3合并提交，记录签名依赖理由，不单独提交不能编译的中间状态。

### 任务2：在真实验证点记时与五字段Ticket

**接口：** LoginPrincipal在现有五字段末尾增加 `Instant authenticatedAt`；LoginTicketContext在现有四字段末尾增加同字段。PasswordLoginService、EmailCodeLoginService、LoginTicketService构造器注入上述Clock，业务方法签名不变。时间不可省略，不增默认构造器。

- [x] 先扩展PasswordLoginServiceTest：matches回调推进Clock，返回true后捕获的时间是新Clock秒值；身份和版本仍来自matches前复制的快照，不重新读取Entity。密码错误不返回principal。
- [x] 扩展EmailCodeLoginServiceTest：validate回调推进时钟，查询和consume再推进时钟，最终时间必须等于validate完成时刻；消费false/异常不返回principal、不恢复验证码，版本仍为既有consume前快照。
- [x] 扩展LoginTicketServiceTest：精确五字段及规范authTime文本；0、过去时间成功，缺失/空白/+/-/前导零/Unicode数字/小数/科学计数/超Long或Instant/未来拒绝；四字段旧票据、重复或额外字段、非字符串仍拒绝。未知发布/消费结果503，业务失效401。
- [x] 运行对应三个Service类测试确认失败，再实现不可变时间捕获。UserLoginService构造新principal时必须带原时间，不从数据库授权快照伪造；迁移相关单测构造器并保留所有旧断言。
- [x] 将Ticket Lua改为五字段＋TTL的原子发布；ARGV依次userId、role、loginType、tokenVersion、authTime、300，共6项。hash为原四字段加authTime，消费仍检查类型/PTTL、取出并删除、精确解析；不刷新时间/TTL、不恢复、不重试、不改key命名空间。
- [x] 在任务3联合验证点重跑本任务三类测试通过，与任务1/3合并成同一可编译提交。

### 任务3：贯穿登录签发与原安全回归适配

**接口：** UserLoginService私有 `completeLogin(UserAuthorizationSnapshot snapshot, UserRole role, LoginType type, Instant authenticatedAt)`；构造器也注入Clock，仅用于既有RoleSelectionResponse到期时间的统一测试时钟，不以它刷新认证时间。登录公开方法与成功DTO不变。

- [x] 扩展UserLoginServiceTest：普通登录issue第四参必须等于principal时间，管理员Ticket必须等于原principal时间，兑换JWT第四参等于Ticket时间；推进Clock和修改数据库授权快照不能改变原验证时间或升级验证版本。
- [x] 保留版本/角色/账户复核、403选择限制、失败计数和最终复核后旧版本签发边界；Mockito断言迁移为四参签发，并verify legacy方法never。生产src/main只允许旧式方法声明，不应出现其调用。
- [x] 为AuthVersionBindingIntegrationTest中的有效新票据夹具增加明确authTime，所有原无版本/无TTL/错误类型/非规范版本等用例继续在五字段基础上测试原问题；不能因为统一补时间而消除显式旧四字段拒绝用例。
- [x] PasswordChangeIntegrationTest仅适配fixture/spy调用，不能删改925基线中既有撤销和并发断言。其他11个fixture文件只做显式方法重命名，无歌曲业务或存储实现改动。
- [x] 运行 `cmd /c gradlew.bat test --tests "org.example.music.user.service.UserLoginServiceTest" --tests "org.example.music.security.*" --tests "org.example.music.user.service.PasswordLoginServiceTest" --tests "org.example.music.user.service.EmailCodeLoginServiceTest" --tests "org.example.music.user.service.LoginTicketServiceTest"`，覆盖任务1—3联合验证；通过后检查无旧三参issue、无生产legacy调用、无验证时间默认重载，中文提交 `feat: 贯穿登录验证与签发的原始认证时间`。

### 任务4：真实载荷、过滤链与时间/版本并发测试

**文件：** 新增user/AuthTimeBindingIntegrationTest.java；@SpringBootTest＋@AutoConfigureMockMvc，真实MySQL8.4、Redis8.10.2、BCrypt/JWT，命名Clock使用可控测试bean，SMTP仅捕获邮件。测试不新增生产HTTP入口。

- [x] 正常两种密码登录、EMAIL_CODE、USER及ADMIN各登录角色，经真实登录与角色选择，检查原始JWT载荷整数时间、实际decoder值和受保护GET /users/me均成功。用服务/Token签发spy断言真实登录不调用legacy入口。
- [x] 用闩锁暂停验证之后、查询/消费之后、签发之前或角色选择，推进可控时钟；JWT auth_time恒为原验证时间，iat随签发变化。验证时间已超过300秒但Token仍有效时，普通接口仍成功；本轮不创建伪敏感接口或提前返回REAUTHENTICATION_REQUIRED。
- [x] 使用Nimbus JWSObject＋原始JSON Payload签名构造类型边界，不能用可能丢弃null或归一化小数的JWT构造器冒充原载荷。真实签名载荷中明确覆盖auth_time:null、数字字符串、1.0/1e0、小数、负数、超Long/Instant、未来、晚于iat、auth_time存在但iat缺失/null；期望401 INVALID_TOKEN。签名错误等仍沿用既有资源服务器行为，不强求新增ProblemDetail。
- [x] 缺auth_time的旧JWT访问普通GET/改密回归正常；合法上下文在真实过滤链后通过测试spy捕获Authentication.details验证，不添加调试Controller。时间存在合法/缺失但版本撤销，均不得通过；伪造头/参数不影响上下文。
- [x] 真实Redis验证五字段发布和TTL，四字段拒绝、未知字段拒绝、并发兑换恰一次成功，不恢复凭据；旧验证/旧Ticket在真实改密后仍不能升级版本，保留最终复核后旧Token后续401的诚实边界。
- [x] 用例每例清理自己容器数据/Redis key并重置Clock，所有闩锁/线程有超时与finally。Redis TTL用真实TTL验证，不能靠推进Java Clock假装Redis已过期；无sleep。
- [x] 设置MINIO_INTEGRATION_TESTS=true后定向运行AuthTimeBindingIntegrationTest、AuthVersionBindingIntegrationTest、PasswordChangeIntegrationTest、UserLoginIntegrationTest及fixture适配的集成类，零失败/必要用例零跳过；中文提交 `test: 验证认证时间传递与旧凭据兼容`。

### 任务5：部署要求、错误文档与设计反馈闭环

**文件：** docs/api/auth/login.md、docs/deployment/auth-time-binding.md，AuthController仅登录OpenAPI描述；设计审核处置记录保持NOTE编号。

- [x] 文档明确登录JSON不变、JWT新声明、旧Token缺失兼容/非法拒绝、四/五字段Ticket互不兼容、未知Redis结果、角色/版本复核不变；本次没有300秒业务门禁或refresh/reauth接口。
- [x] NOTE-001：零容差保持。运维验收要求所有认证节点时间同步健康，连续15分钟观测节点间估计偏差不超过100ms；超过100ms告警，超过500ms或失去同步时摘除异常认证节点并排查。阈值为本项目运维目标，不是“零误拒保证”；任意正偏差仍可能在秒边界误拒，新令牌需要跨节点烟测。监控由部署环境时间同步工具/现有外部监控提供，不新增Actuator暴露、应用依赖或配置开关。
- [x] 验收保留同步状态、采样窗口、测得偏差、时间源及跨节点登录/普通访问结果；旧Token、全实例切流/旧请求排空、旧Ticket重新登录、混合部署和回滚停用敏感资格分别说明，不以修改容差或iat补齐掩盖环境问题。
- [x] NOTE-002：声明三参issue已移除，旧式入口issueLegacyWithoutAuthTime仅显式兼容；生产调用检查和测试never作为证据。未来refresh必须继承可信原时间和版本，不能用legacy入口或now假装新验证。
- [x] 文档curl保留既有公开登录示例与虚构凭据，不增加未实现接口调用。中文提交 `docs: 明确认证时间兼容与时钟部署验收`。

### 任务6：完整构建与实现审核

- [x] 检查git diff --check、相对3a27229的全部文件含untracked，逐项解释测试夹具改名；无无关格式化、迁移/配置项/新依赖，无其他接口实现。Clock bean不是可配置业务策略开关，旧exp/nbf容差不变。
- [x] 执行 `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`，强制实际执行全部MySQL/Redis/MinIO回归；汇总本次XML通过/失败/错误/跳过和类数，列出跳过，不预填数量或复用旧报告。
- [x] 准备docs/reviews/2026-11-02-auth-time-binding-review.md：唯一现有Method/Path、权限与不变契约、全行为、全部文件、无迁移、API/部署文档、实际命令/数量/提交/基线、curl、零容差风险/部署条件；设计NOTE-001/002逐项写事实、原分级、修改方向、实际处置与验证。设计accepted不等于实现accepted。
- [x] 中文提交计划进度与审核材料，交用户提供Claude独立只读审核；尚有阻断不开始下一接口，是否推送由用户决定。

## 计划自审

任务1对应严格JWT与可信上下文，任务2对应真实记时与五字段Ticket，任务3对应全链传递与最小编译适配，任务4对应真实载荷/过滤链/版本回归，任务5落实两条非阻断建议，任务6完成基准与独立送审。整数声明不会经String.valueOf放宽，iat默认补齐已明确处理；没有引入近期资格默认值、300秒业务门禁或新接口。任务间签名编译依赖允许合并可编译提交，不为分提交保留错误兼容路径。用户已确认本计划并授权编码。

## 实施与验证记录

- 用户确认后实施。源码与签名适配63a9d92、真实集成c4c93ef、API/OpenAPI/部署说明5c4fe17；任务1—3按已确认计划合并为一个可编译提交，未保留时间默认重载。
- 首次新增上下文测试因类型缺失编译失败；后续联合补实现和测试。修正过测试签名机械适配及Mock时钟未使用stub问题，未删除业务断言。未对每条新断言分别追溯修改前失败，未把编译红灯声称为业务断言红灯。
- 联合单测命令同任务3：115通过/0失败/0错误/0跳过，8秒。真实定向AuthTimeBindingIntegrationTest、AuthVersionBindingIntegrationTest、PasswordChangeIntegrationTest：135通过/0失败/0错误/0跳过，1分41秒。
- 最终设置MINIO_INTEGRATION_TESTS=true执行 .\gradlew.bat cleanTest check --console=plain，BUILD SUCCESSFUL，9分钟。本次XML汇总999通过/0失败/0错误/0跳过、88类，较925基线净增74项。
- 任务4所列其他fixture集成与UserLoginIntegrationTest由最终全量实际覆盖，未重复另跑一遍宽泛定向组；八个track文件每个只改一处旧式fixture方法名，无歌曲生产逻辑或断言变化。
- NOTE-001时钟零容差、观测/告警和烟测要求已落部署文档，实际环境验收尚未执行。NOTE-002已显式命名旧式入口并移除旧三参issue，生产rg仅发现定义，无调用；实际登录unit/integration均verify never。
- 首轮实现材料docs/reviews/2026-11-02-auth-time-binding-review.md，设计建议处置另同步设计审核回执。不将设计accepted或本次自验当作实现accepted；未实现后续接口、未推送。
- 本地没有executing-plans子技能文件，沿用Codex原生执行，没有派发子代理或声称调用缺失技能。