# 登录认证版本绑定加固实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.
>
> 执行方式按项目约定：Codex 为唯一实现方，Claude 独立只读审核，不派发实现子代理。当前环境缺少的通用子技能不得声称已调用。

**Goal:** 仅加固现有 `POST /api/v1/auth/login`，将凭据验证结果和管理员 Ticket 绑定验证所依据的 token version，阻止旧验证结果升级为新版本有效 Token。

**Architecture:** 内部 LoginPrincipal 与 LoginTicketContext 增加显式版本；初始登录与 Ticket 兑换先复核账户、角色、版本，再始终使用固定快照。Ticket 采用四字段加 TTL 的 Lua 发布、带 PTTL 校验的一次性消费，不以数据库长锁跨越 BCrypt 或 Redis。

**Tech Stack:** Java 21、既有 Gradle Wrapper、Spring MVC/Security/Data JPA/Redis、MySQL 8.4、Redis 8.10.2、JUnit 5/MockMvc/Testcontainers、现有 BCrypt/JWT/springdoc；不新增依赖。

**Spec:** [已审阅确认设计](../specs/2026-11-01-auth-version-binding-design.md)，提交 `17b5a9a`。用户附件确认可以进入本加固任务的 writing-plans；U03 改密设计补充基线为 `b2c71e4`。

## 全局约束

- 单任务只影响既有 POST /api/v1/auth/login 的版本绑定，不新增接口，不实现改密或注册密码修复。
- 客户端请求、成功 DTO、JWT 声明格式、角色选择交互不变；版本只由服务端凭据快照或 Ticket 提供。
- 内部版本显式传递，不保留缺省 0 构造器，不用当前版本回填旧凭据；合法版本 0 与缺失不同。
- 复核必须同时检查账户存在、角色相同、版本相同；JWT 始终取已经匹配验证结果的不可变快照，签发路径不再查库升级版本。
- 初始复核失效为 401 INVALID_CREDENTIALS；Ticket 格式/复核失效为 401 LOGIN_TICKET_INVALID；不新增业务错误码，保留原有限流与失败计数。
- Ticket 四字段及 300 秒 TTL 原子发布；消费一次，PTTL 无有效值则删除并拒绝；不恢复已消费凭据、不清空整个 Redis。
- 无迁移、无新依赖、无认证缓存、无 PasswordHasher/共享认证主体改动；不改已执行 V1—V8。
- 复核后撤销允许返回旧版本 Token，但该 Token 后续必须被拒绝；不能断言所有竞态都立即使登录返回 401。
- 全部旧登录实例退出服务后才能宣告保证生效，混合部署和回滚边界须入文档。
- 文档与提交说明使用中文；最终基准 .\gradlew.bat check，必要集成测试跳过不能计为通过。

## 审查重点

1. ADMIN 原路径在复核前生成 Ticket：任务 3、4 钉住管理员验证后撤销/改角色不能绕过状态检查。
2. parseLong 会接受 +1/007、版本 0 容易被当缺省值：任务 2 按规范字符串和 long 范围独立校验并逐项验证。
3. HGETALL 的顺序不稳定、数量为 8 不代表字段正确：任务 2 校验精确字段集合并拒绝未知/重复/缺失字段及异常返回类型。
4. 复核后再读取最新版本会重开漏洞：任务 4 在 JWT 编码与 Ticket 发布之前暂停，撤销后验证签出的只能是旧版本结果。
5. Ticket 或验证码消费失败后的自动恢复可能造成重放：任务 2、4 证明一次性消费，以及后续版本/存储失败不恢复凭据。

## 文件边界

路径相对仓库根 `D:/code/Java-code/projects/music`。

| 创建/修改 | 文件 | 职责 |
| --- | --- | --- |
| 修改 | src/main/java/org/example/music/user/domain/LoginPrincipal.java | 增加验证版本 |
| 修改 | src/main/java/org/example/music/user/domain/LoginTicketContext.java | 增加 Ticket 版本 |
| 修改 | src/main/java/org/example/music/user/service/PasswordLoginService.java | 同一用户记录捕获凭据版本 |
| 修改 | src/main/java/org/example/music/user/service/EmailCodeLoginService.java | 验证码消费前捕获版本 |
| 修改 | src/main/java/org/example/music/user/service/LoginTicketService.java | Lua 发布/消费、严格格式与版本解析 |
| 修改 | src/main/java/org/example/music/user/service/UserLoginService.java | 两阶段角色与版本复核 |
| 修改（如注解描述需要） | src/main/java/org/example/music/user/controller/AuthController.java | 仅同步既有 401/503 描述 |
| 创建 | src/test/java/org/example/music/user/service/PasswordLoginServiceTest.java | 原凭据记录版本捕获 |
| 修改 | src/test/java/org/example/music/user/service/EmailCodeLoginServiceTest.java | 消费前版本捕获 |
| 创建 | src/test/java/org/example/music/user/service/LoginTicketServiceTest.java | 字段/版本/故障边界 |
| 修改 | src/test/java/org/example/music/user/service/UserLoginServiceTest.java | 复核与失败计数，适配新增构造参数 |
| 创建 | src/test/java/org/example/music/user/AuthVersionBindingIntegrationTest.java | 真实认证与可控竞态 |
| 修改（必要回归断言） | src/test/java/org/example/music/user/UserLoginIntegrationTest.java、controller/AuthControllerTest.java | 既有成功与错误结构回归 |
| 修改 | docs/api/auth/login.md | 明确版本与边缘错误变化 |
| 创建 | docs/deployment/auth-version-binding.md | 全量升级、旧 Ticket、回滚边界 |
| 创建 | docs/reviews/2026-11-01-auth-version-binding-review.md | 实现后单任务审核材料 |
| 修改 | 本计划文件 | 实施记录与实际验证结果 |

复用 UserAuthorizationSnapshot、UserAuthorizationStateService、JwtTokenService、认证过滤器及既有异常；默认不修改它们。若编译暴露其他 record 构造调用点，仅适配必要版本参数并在审核材料列全，不顺手重构。

## 任务 1：冻结凭据验证版本并适配编译调用点

**文件：** LoginPrincipal、PasswordLoginService、EmailCodeLoginService、对应测试及 UserLoginServiceTest 的 LoginPrincipal 构造调用点。Ticket 上下文与格式在任务 2 一起修改，避免中间提交产生读写格式不一致。

**接口：**
- `LoginPrincipal(Integer id, String username, String email, UserRole maximumRole, long tokenVersion)`。
- PasswordLoginService.authenticate(LoginType, String, String): LoginPrincipal 和 EmailCodeLoginService.authenticate(String, String): LoginPrincipal 的公开方法签名不变。

- [x] 编写失败单测：用户名密码、邮箱密码分别返回读取时的版本 0/7；用密码校验回调模拟记录随后变化，结果仍是校验所依据版本。错误密码不产生 Principal，不重新读取用户刷新版本。
- [x] 扩展 EmailCodeLoginServiceTest：用户记录取得版本 7，在 consume 完成回调后即使模拟状态变化，结果仍为 7；验证 validate→读用户→consume 顺序，消费失败不返回 Principal。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.service.PasswordLoginServiceTest" --tests "org.example.music.user.service.EmailCodeLoginServiceTest"` 确认失败。
- [x] 增加 LoginPrincipal 的 tokenVersion 字段，不能加默认版本重载。密码服务在 matches 前从同一用户记录构造不可变 Principal，校验成功后返回；邮箱验证码服务在 consume 前构造 Principal，消费成功后返回。
- [x] 使用 rg 查全 new LoginPrincipal 调用点，为现有生产与测试调用显式补版本；不修改 Ticket 上下文或生成临时默认版本。
- [x] 重跑上述测试；同时运行 UserLoginServiceTest，现有正常版本 fixtures 显式为 0，确认无遗漏构造参数。本任务验证通过，归入最终单任务原子提交。

## 任务 2：完整 Ticket 发布、严格解析与一次性消费

**文件：** LoginTicketContext、LoginTicketService、LoginTicketServiceTest，以及 UserLoginServiceTest 的 LoginTicketContext 构造 fixture。

**接口：** 保留 `issue(LoginPrincipal principal, LoginType loginType): String`、`consume(String ticket): LoginTicketContext`；上下文改为 `LoginTicketContext(Integer userId, UserRole maximumRole, LoginType loginType, long tokenVersion)`。版本来自任务 1 的显式字段；不新增数据库查询、默认版本重载或客户端版本参数。

- [x] 写失败测试：精确四字段 userId/maximumRole/loginType/tokenVersion 以任意顺序均可解析；版本 "0"、"7"、Long.MAX_VALUE 正常；旧三字段、缺/多字段、重复名称、空或非字符串返回值拒绝。
- [x] 参数化覆盖版本空串、空白、+1、-1、007、00、1.0、1e2、非 ASCII 数字、9223372036854775808；全部 LoginTicketInvalidException。合法 userid 为正 Integer，角色为既有枚举，原始方式为 USERNAME_PASSWORD/EMAIL_PASSWORD/EMAIL_CODE；非法元数据同样拒绝。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.service.LoginTicketServiceTest"` 确认失败。
- [x] 增加 LoginTicketContext 的显式 tokenVersion，使用 rg 适配全部构造调用；UserLoginServiceTest 原有合法上下文显式为 0，与其账户快照一致，不能用缺省构造器隐藏遗漏。
- [x] 用一个 ISSUE Lua 写四字段并 EXPIRE 300 秒，参数先验证再修改；返回明确成功标记，null/非成功标记不得返回 Ticket。保留 32 字节 SecureRandom、SHA-256 key、既有命名空间；不在写前查询数据库升级版本，不用多个 HSET+expire。
- [x] CONSUME Lua 先读取 PTTL：不存在或 PTTL<=0 删除并返回无效；检查 key 类型，非 Hash 异常记录删除并拒绝。正常 Hash 原子 HGETALL+DEL，返回原始字段。PTTL 语义用 -1 无 TTL、-2 不存在的真实用例钉住，不调用 EXPIRE 延长消费窗口。
- [x] Java 对返回 List<?> 要求恰好 8 项，转换为严格键值集合；精确等于四个必要字段，不能忽略多余字段、用位置猜 HGETALL 顺序或覆盖重复字段。版本先验证 `0|[1-9][0-9]*` 再 parseLong 检查范围，用户 ID 同样格式和 int 范围校验。
- [x] Redis 操作异常、发布返回结果不确定映射既有 AuthStateStoreUnavailableException；格式/过期/重放映射 LoginTicketInvalidException。Lua 异常不宣称自动回滚，失败不补发 JWT、不恢复已消费 Ticket、不记录 Ticket 值或敏感 key。
- [x] 重跑单测通过；真实 Lua、TTL、消费及故障验证留在任务 4，不能用本单测 mock Redis 代替它。本任务验证通过，归入最终单任务原子提交。

## 任务 3：初始与兑换复核，固定签发快照和对外文档

**文件：** UserLoginService、UserLoginServiceTest、必要的 AuthController 注解与 HTTP 回归、login.md、部署文档。

**接口：** UserLoginService.login(LoginRequest, String): LoginResponse 不变；内部新增 `findMatchingSnapshot(Integer userId, UserRole expectedRole, long expectedVersion): Optional<UserAuthorizationSnapshot>`，仅调用现有状态服务一次并过滤角色/版本。初始与兑换分别选择各自已有异常。

- [x] 写失败测试：初始 USER/ADMIN 账户不存在、版本不同、角色不同均 InvalidCredentialsException，既不发 JWT 也不发 Ticket；每次失败仍调用原 recordFailure 一次。版本相同才允许进入管理员角色分支。
- [x] Ticket 上下文复核账户不存在/角色或版本变化，均 LoginTicketInvalidException；合法快照但选择越权角色仍为 RoleSelectionNotAllowedException。验证失败计数路径不绕过、不重复消费登录请求额度。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.service.UserLoginServiceTest"` 确认失败，修订现有管理员成功 fixture，明确提供当前匹配快照。
- [x] 在 issueTokenOrRoleSelection 开头取得匹配快照，缺失用 InvalidCredentialsException。普通用户按 USER 完成登录；管理员从已匹配快照构造带同版本的 Principal 交给 issue，其版本因相等条件仍是原验证版本，不允许刷新失效结果。
- [x] completeRoleSelection 消费 Ticket 后取得匹配快照，缺失用 LoginTicketInvalidException，再检查请求 loginRole；将固定快照传给 completeLogin，不在登录活动记录后再查询或升级版本，不修改 JwtTokenService 公开接口或共享主体。
- [x] 测试 JWT 参数使用匹配的同一不可变快照；用户名/邮箱允许采用当前展示资料，角色与版本不能不经相等检查升级。无版本客户端输入不参与比较，不新增 LoginRequest/成功 DTO 字段。
- [x] 在 docs/api/auth/login.md 显式修正初始阶段账户消失的边缘码：由旧普通路径 LOGIN_TICKET_INVALID 改为 INVALID_CREDENTIALS；Ticket 阶段账户消失仍为 LOGIN_TICKET_INVALID。同步写验证后角色变化拒绝、旧 Ticket 拒绝、规范版本、TTL 与一次性凭据失败不恢复。
- [x] 修订文档中“tokenVersion 来自最新快照”的笼统表述：当前快照必须匹配验证/Ticket 版本；最后复核后撤销只会返回旧版本结果，下一次受保护请求拒绝。保留密码、限流等未受本任务影响的既有契约，不顺手修复注册/其他文档问题。
- [x] 部署文档写明所有旧登录实例退出服务后保证才成立；新节点拒绝无版本 Ticket，不清空整个 Redis；回滚旧代码会重开风险，不能继续宣称满足 U03 前置条件。记录 Redis 故障/消费不确定性与无跨存储事务。
- [x] 检查 AuthController 既有 401/503 注解描述，必要时只补行为描述。运行 UserLoginServiceTest、AuthControllerTest 及任务 1、2 的定向测试通过后本任务验证通过，归入最终单任务原子提交。

## 任务 4：真实 MySQL/Redis/JWT 并发证明与完整送审

**文件：** AuthVersionBindingIntegrationTest、必要的 UserLoginIntegrationTest 适配、计划和审核材料。

**环境：** 参考既有登录集成测试配置 MySQL 8.4、Redis 8.10.2、PasswordHasher、JwtDecoder。新增类用 SpringBootTest+AutoConfigureMockMvc；正常登录与 Ticket 操作走真实数据库/Redis/BCrypt。验证码可用真实验证服务创建测试凭据，邮件发送沿用已有测试捕获方式，不以 mock 验证码 Redis 替代真实消费。

- [x] 成功回归用户名密码、邮箱密码、邮箱验证码，ADMIN 以 USER/ADMIN 兑换；测试版本 0 与非零，解码 JWT 并通过真实 `/api/v1/users/me` 认证，断言公开 JSON 字段/原始 loginType/角色及 tokenVersion 不变。
- [x] 用真实 Redis 保存由生产 issue 生成的 Ticket，断言精确四字段、正确绑定版本、0<PTTL<=300000；消费后 key 不存在，再次兑换为 LOGIN_TICKET_INVALID。用两个线程同时兑换同一 Ticket，最多一个获得上下文/成功，另一个拒绝。
- [x] 直接构造缺版本旧 Hash（当前账户版本为 0）、未知额外字段、规范解析反例、无 TTL、过期及错误 Redis 类型记录；通过 HTTP 或生产 consume 验证拒绝，消费后的无效记录不恢复。long 上限用真实数据库和 Ticket 可合法往返，不作加一操作。
- [x] 验证后撤销窗口：在真实 BCrypt matches 成功之后暂停返回，数据库直接递增 token_version，再释放线程进入复核；USER 初始登录返回 401 INVALID_CREDENTIALS，无有效 JWT。ADMIN 同样验证不得拿到可兑换的新版本 Ticket。
- [x] 同样在密码验证后修改角色（使用既有角色服务或受控 SQL），钉住角色变化拒绝行为；再构造仅角色变化但版本相同的故障状态，证明角色相等条件不是依赖版本变化的冗余检查。
- [x] 邮箱验证码消费完成之后暂停，递增版本再复核，返回 INVALID_CREDENTIALS；验证码已消费不可重用，未消费且仍有效的其他验证码仍可按原规则进行新验证。
- [x] Ticket 签发后递增版本再兑换，401 LOGIN_TICKET_INVALID；包括角色不变但版本递增。验证已有 RoleSelection 权限拒绝和账户消失分支不发 Token。
- [x] 最后复核后窗口：在 JwtTokenService.issue 的测试钩子暂停实际编码前，数据库递增版本，再调用真实编码；允许登录 200，但解码只能是旧版本，随后通过实际安全链 GET 得 401 TOKEN_REVOKED。不得强制仅断言登录 401，或用假 JWT 验证安全性。
- [x] 在 LoginTicketService.issue 的测试钩子暂停真实发布前递增版本，真实 Redis 中 Ticket 必须保留旧版本，之后兑换拒绝。测试钩子只控时序，不能替代实际发布/编码。
- [x] 复核状态服务/Redis 故障验证 503；Ticket 或验证码已消费后版本或存储失败，不恢复凭据、不假成功。按既有码区分验证码存储错误与认证/Ticket 存储错误。
- [x] 受控竞态使用 CountDownLatch/CyclicBarrier/Future 超时及 finally 清理，不用 Thread.sleep 碰运气。Repository 为 Spring Data 接口时，通过 spy 的转发默认 Answer 调用真实代理，不能对抽象方法 callRealMethod；具体类 spy 才按实际 Mockito 能力调用真实实现。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.AuthVersionBindingIntegrationTest" --tests "org.example.music.user.UserLoginIntegrationTest"`，确认 MySQL/Redis 实际运行且无跳过；修复本任务范围内缺陷后重跑。
- [x] 设置 `$env:MINIO_INTEGRATION_TESTS = "true"`，执行 `.\gradlew.bat cleanTest check`，强制全量回归既有测试；仅为消除环境门禁跳过，不扩大到存储模块修改。
- [x] 按 build/test-results/test/TEST-*.xml 汇总通过/失败/错误/跳过，列出跳过原因；必要集成验证未执行不能宣称完成。检查 git diff --check、git status --short，列全修改及 untracked，无迁移、依赖、Token/凭据或无关文件。
- [x] 按 AGENTS.md 准备 docs/reviews/2026-11-01-auth-version-binding-review.md：唯一既有接口、任务范围、需求/权限、行为、全部文件、API/部署文档、迁移无、基线/实现提交、真实命令与数量、curl 及并发/部署限制。允许最终一个单任务原子提交，须记录实际提交方式，不虚构任务提交或测试执行。
- [ ] 等待 Claude 独立只读审核结论；accepted 后记录实际实现提交与部署前提，才解除 U03 前置门禁并回到改密设计核对代码基线。needs_revision 修阻断项，最多三轮；escalated 等待人类决策。

## 自查与执行门禁

已对照设计及审阅提醒检查记录版本捕获、ADMIN 分支、精确四字段与规范解析、PTTL 失效、错误语义、角色变化拒绝、两个竞态窗口、真实外部依赖与全量部署边界。五项审查重点均落到测试步骤，不改变公开成功契约或实现额外接口。

用户已确认实施。采用最终单任务原子提交，代码、API/部署文档及测试已完成；实际启用 MINIO_INTEGRATION_TESTS=true 执行 .\gradlew.bat cleanTest check，81 个类、767 通过 / 0 失败 / 0 错误 / 0 跳过（8m 11s）。本任务五类共 117 项，其中新增 109 项：密码版本捕获新增 6、验证码新增 2、Ticket 新增 33、登录复核新增 22、真实集成新增 46；既有 8 项同步回归。此前独立定向真实集成命令通过 51 项（新增 46 与原有登录 5，1m 8s）。git diff --check 通过，无迁移、依赖或改密/注册逻辑改动。首轮审核材料见 docs/reviews/2026-11-01-auth-version-binding-review.md；当前等待 Claude 独立审核，不宣称 accepted，也未确认生产全实例升级，不解除改密前置门禁。
