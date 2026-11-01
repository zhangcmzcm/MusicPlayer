# 登录认证版本绑定加固设计

日期：2026-11-01。

状态：用户已确认方案、错误契约、Ticket 原子保存、并发与部署边界；书面设计待审阅。

基线：main @ b2c71e4。开始本设计时无未提交修改；本地尚有改密设计的两个文档提交未推送。

## 1. 目标与范围

本任务是 U03 修改密码的独立认证加固前置任务，仅加固现有 `POST /api/v1/auth/login`。覆盖 USERNAME_PASSWORD、EMAIL_PASSWORD、EMAIL_CODE 和 ROLE_SELECTION，不新增 HTTP 接口。

目标是使一次凭据验证及其派生的管理员角色选择 Ticket 绑定验证所依据的账户 token_version，禁止后续读取的新版本将旧验证结果升级为新的有效登录。

现有登录请求、成功响应结构、角色选择交互、JWT 声明格式与算法、Ticket 300 秒有效期均保持不变。不要求客户端提交 token version，不向登录响应增加独立版本字段。

不实现修改密码，不修改注册密码边界、PasswordHasher、密码算法、数据库结构、迁移或其他账户接口；不借本任务提取资料映射、引入认证缓存或新增依赖。

## 2. 已核对的现状与风险

- PasswordLoginService 读取用户行并验证 password_token，但返回的 LoginPrincipal 只有 id、username、email、maximumRole，没有验证时版本。
- 普通用户随后由 UserLoginService 另行读取 UserAuthorizationSnapshot，并以其中的当前版本签发 JWT。若凭据校验和后续快照之间发生版本递增，旧密码验证结果可能获得新版本有效 Token。
- 管理员在第一次验证后取得 LoginTicketService 生成的 Ticket；其 Redis Hash 和 LoginTicketContext 都只有 userId、maximumRole、loginType，没有版本。
- ROLE_SELECTION 消费 Ticket 后重新读取最新快照，仅比较账户角色。因此旧 Ticket 可能在密码或其他认证状态撤销后换取新版本有效 Token。
- 当前 Ticket 保存是多次 HSET 后单独设置 TTL，不是一个完整发布操作；消费已有 HGETALL 加 DEL 的 Lua 一次性逻辑。
- EmailCodeLoginService 先验证验证码、再读取用户行、再一次性消费验证码，返回同样不带版本的 LoginPrincipal，需要适配同一内部数据流。
- JwtTokenService 已从不可变 UserAuthorizationSnapshot 写入 tokenVersion；TokenVersionAuthenticationFilter 每次受保护请求读取数据库版本检查。无须新增 JWT 声明或修改共享认证主体。

## 3. 方案比较与选择

| 方案 | 收益 | 代价 |
| --- | --- | --- |
| 绑定验证时版本，签发前复核（选定） | BCrypt 和 Redis 不持有用户行长锁；无迁移；覆盖跨请求 Ticket | 需显式传递版本并保留最后复核之后的并发边界 |
| 数据库锁贯穿凭据校验与签发 | 可以串行化部分用户状态操作 | BCrypt 期间锁占用长，跨请求 Ticket 仍需版本绑定，成本更高 |

本任务采用第一种，不引入跨 MySQL/Redis 的事务或数据库长锁。认证加固的成功定义是旧验证结果不能获得撤销后仍有效的新版本 Token，不是所有并发撤销都必须让原登录请求立刻返回 401。

## 4. 内部验证结果绑定

### 4.1 LoginPrincipal

在现有字段后增加 long tokenVersion，作为凭据验证所依据的账户版本。该对象仍是内部不可变验证结果，不进入 HTTP 请求或响应。

- 不提供缺省版本为 0 的兼容构造器，不用事后状态回填版本；所有生产调用点显式提供版本。
- 密码登录：从校验密码所使用的同一条用户记录取得 ID、角色与版本。BCrypt 返回成功后携带该记录的版本，不能重新查库取新版本代替。
- 邮箱验证码登录：沿用原有校验与消费顺序，从消费验证码前已读取的同一用户记录取得版本；消费成功后携带该快照版本，不在之后重新读取并升级。
- 用户名、邮箱可因资料修改而变化，但身份与角色判断不能由客户端字段重建；凭据快照不能覆盖随后数据库中的新资料。

未消费的邮箱验证码继续按既有用途、有效期和一次性规则使用，不因本任务绑定版本额外撤销。验证码发放时不新增账户版本字段；其独立邮箱凭据仍可在之后发起新的验证。

### 4.2 普通登录和管理员 Ticket 签发前复核

UserLoginService 在根据最高角色选择直接签发或生成 Ticket 之前，统一读取当前 UserAuthorizationSnapshot，要求：

1. 当前账户仍存在。
2. 当前 tokenVersion 等于 LoginPrincipal.tokenVersion。
3. 当前 userRole 等于验证结果的 maximumRole。

不满足任何一项时抛 InvalidCredentialsException，返回 401 INVALID_CREDENTIALS，不签发 JWT 或 Ticket。此处角色判断是在复核之后分支，不能先信任旧 ADMIN 状态绕过复核去签发 Ticket。

普通用户继续以 USER 登录；管理员继续返回角色选择结果。可使用复核快照中的当前用户名和邮箱展示资料，但认证版本和角色必须通过上述相等条件，不能将新角色、新版本合并进旧验证结果。

复核通过后，将这个不可变快照继续传给 JwtTokenService；后续不得为了签发 Token 再次查询、刷新或替换版本。原有登录活动记录流程保留，不把 last_login_at 更新视为跨阶段原子认证保证。

## 5. Ticket 保存、校验与一次性消费

### 5.1 格式与发布

继续使用 SecureRandom 生成 32 字节随机 Ticket，客户端值及 Redis key 的 SHA-256 派生方式不变，key 命名空间仍为 music:auth:login-ticket:。

Redis Hash 包含以下四个必要字段：

| 字段 | 来源 |
| --- | --- |
| userId | 已验证主体结果的用户 ID |
| maximumRole | 已验证结果的账户角色 |
| loginType | 原始凭据登录方式 |
| tokenVersion | LoginPrincipal 所绑定的版本，十进制字符串 |

Ticket 必须保存原验证版本，不在 Redis 写入前另查数据库“刷新”版本。使用一次 Lua 执行全部字段写入与 300 秒 TTL 设置，对其他 Redis 操作不暴露分步 HSET 的中间状态。

写入参数在修改前验证，TTL 是服务端固定合法值；仅确认脚本成功后返回 Ticket。Redis 故障、超时或无法确认写入结果时返回 503 AUTH_STATE_STORE_UNAVAILABLE，不返回可能不完整或无法确认的 Ticket，不转而发放 JWT。Lua 的隔离性不等于异常时自动回滚，不能声称服务故障下所有已执行写入必然撤销。

角色选择响应仍包含原有 loginTicket、expiresAt 字段；有效期以 Redis 的 300 秒 TTL 为准，不因为兑换、查询或失败而延期。

### 5.2 消费与格式校验

在 LoginTicketContext 增加 long tokenVersion，字段必须来自 Ticket，不能来自客户端请求或当前数据库版本。

- 保留 Redis Lua 原子读取并删除的一次性消费语义；同一 Ticket 并发兑换最多一个请求得到上下文。脚本内同时检查剩余有效期，无有效 TTL 的异常记录也删除并拒绝，不能成为不失效的 Ticket。
- userId 必须为有效的正 Integer，角色和登录方式必须是合法的既有枚举语义，所有必要字段必须存在且可解析。
- tokenVersion 必须是 long 范围内的规范非负十进制字符串：0 或不以 0 开头的正整数，仅含 ASCII 数字。不接受正负号、前导零、小数、科学记数、空白、溢出或缺失，不做数值截断或默认赋值。
- 版本 0 是合法初始版本；缺少字段不等于 0，不能用数据库当前版本替换。
- Ticket 缺失、过期、已消费、格式不合法或旧格式缺版本，返回 401 LOGIN_TICKET_INVALID。

消费得到的上下文在同一请求内只使用一次；后续校验失败、签发失败或客户端未收到响应，不将 Ticket 放回 Redis。Redis 消费超时可能已删除 Ticket，仍返回存储不可用，不承诺凭据一定未被消费。

### 5.3 兑换时复核

ROLE_SELECTION 消费 Ticket 后读取当前认证快照，要求账户存在，tokenVersion 与 Ticket 相同，userRole 与 maximumRole 相同。任一不满足返回 401 LOGIN_TICKET_INVALID，不发放 JWT。

复核通过后，按现有 loginRole.isAllowedBy 规则校验本次选择；不允许的角色沿用 403 ROLE_SELECTION_NOT_ALLOWED，不利用本任务放宽角色选择。

JWT 继续由本次已复核的不可变快照签发，禁止兑换后另取新版本升级失效 Ticket。Ticket 中的登录方式继续作为原始认证方式，不把 ROLE_SELECTION 当作新的密码或验证码验证。

## 6. 并发安全保证与边界

### 6.1 验证后、复核前撤销

如果密码验证或验证码消费完成后、最终状态复核前发生版本递增，比较发现失配，返回 401 INVALID_CREDENTIALS。若 Ticket 签发后、兑换复核前发生撤销，返回 401 LOGIN_TICKET_INVALID。

版本变化后即使账户角色又恢复成原值，也必须因版本不同拒绝，不能只比较角色。初始版本 0 和非零版本均应用同样规则。

### 6.2 最后复核之后撤销

最终复核与 Token 签发不是覆盖全系统的原子事务。若撤销在复核之后发生，登录可能仍返回 200 和一个旧版本 Token，但编码版本必须仍为复核且绑定原验证的版本；该 Token 随后访问受保护接口时会被数据库版本检查拒绝。

管理员也可能在复核之后取得一个已过时版本的 Ticket；该 Ticket 后续兑换必须因版本失配被拒绝，不能换取新版本有效 Token。

不得承诺所有竞态都使登录请求当场返回 401，也不得因为无法保证即时拒绝而在签发时改用数据库最新版本。用户名等资料更新不递增版本时，沿用现有资料读取语义，不额外撤销登录。

### 6.3 一次性凭据与跨存储失败

MySQL 状态查询和 Redis 验证码/Ticket 消费不构成分布式事务。已消费的验证码或 Ticket 不因版本校验、数据库或签发失败恢复；调用者重新发起登录，验证码流程必要时重新获取验证码。

不引入自动重试已消费凭据、跨请求缓存验证成功结果或跨 MySQL/Redis 的长事务。已通过认证进入业务层的请求、已提交操作和既有媒体传输不由本任务中断。

## 7. 错误、限流、日志与 API 文档

| 场景 | HTTP / code |
| --- | --- |
| 初始凭据验证失败，或验证结果复核时账户不存在、版本/角色变化 | 401 INVALID_CREDENTIALS |
| Ticket 缺失、过期、重放、无版本、版本非法，或兑换时账户/版本/角色失效 | 401 LOGIN_TICKET_INVALID |
| 合法 Ticket 选择了不允许的登录角色 | 403 ROLE_SELECTION_NOT_ALLOWED |
| 现有登录限流触发 | 429 LOGIN_RATE_LIMITED |
| 本次版本状态查询、Ticket Redis 或原有限流存储不可用 | 503 AUTH_STATE_STORE_UNAVAILABLE |
| 验证码专用存储不可用 | 沿用现有 503 VERIFICATION_CODE_STORE_UNAVAILABLE |
| 其他未预期错误 | 沿用现有 500 INTERNAL_ERROR |

复用现有异常与 ProblemDetail，不新增业务错误码，不对外说明失效是密码、角色、版本或账户变化造成，避免泄漏额外账户状态。type 使用现有 code 小写、下划线格式。

保持原有登录限流和失败计数规则，不新增独立限流、不重置计数。复核产生的 InvalidCredentialsException、LoginTicketInvalidException 仍进入现有失败计数路径，不为这些错误绕过或重复消费额度。

不记录密码、摘要、验证码、Ticket、Token、Redis 敏感键或完整请求体；故障日志沿用 traceId 与脱敏约定。内部验证对象不进入响应，不因新增版本字段而输出额外认证信息。

更新 docs/api/auth/login.md，说明版本绑定、旧 Ticket 失效、最后复核后撤销的窗口、一次性凭据失败不恢复及发布条件。OpenAPI 对既有 401/503 的描述与实际一致，不新增请求版本参数或改变成功 DTO。

## 8. 混合部署、旧 Ticket 与回滚

新代码一律拒绝没有版本的旧 Ticket，不做默认版本、当前版本回填或双格式容错。升级期间已有用户可能需要重新登录，Ticket 本来最长有效 300 秒。

旧实例既可能签发不带版本的 Ticket，也可能忽略新版 Ticket 的版本字段执行旧兑换逻辑，因此只升级部分实例不能宣告加固生效。必须停止或切走所有旧登录实例，确认登录和兑换都由新代码处理后，才能作为改密前置条件通过。

不清空整个 Redis，不影响验证码、限流及其他业务 key；不需要 MySQL 迁移。无需为了兼容旧节点而让新节点接受无版本 Ticket。

回滚到旧登录逻辑会重新引入旧验证升级与旧 Ticket 风险，不能同时继续宣称满足 U03 的实施前置条件；回滚部署时必须同步停止推进依赖该保证的改密上线。

## 9. 预计文件与职责范围

- user/domain/LoginPrincipal：增加显式 tokenVersion，绑定验证快照。
- user/domain/LoginTicketContext：增加 Ticket 绑定版本，不提供无版本默认值。
- user/service/PasswordLoginService：从校验原摘要的同一记录取得版本。
- user/service/EmailCodeLoginService：从验证码消费前读取的用户记录取得版本。
- user/service/UserLoginService：初始分支前复核、Ticket 兑换复核、错误按流程区分，并持续使用不可变快照。
- user/service/LoginTicketService：四字段与 TTL 的 Lua 发布、完整版本解析和一次性消费。
- 上述组件单元测试、现有用户登录与角色集成测试，以及必要的新认证版本并发集成测试。
- docs/api/auth/login.md 与必要 OpenAPI 描述；新增本任务设计、实现计划、审核材料及部署注意事项文档。

JwtTokenService、UserAuthorizationSnapshot 和认证过滤器已有表达能力，默认不改其公开方法或共享主体；如实施计划发现必要局部适配，必须说明原因和范围，不借机重构公共认证体系。

所有新 record 字段的生产及测试构造调用点一并适配，不能保留默认版本的重载以隐藏遗漏。无数据库迁移、无新增依赖、无改密或注册接口实现。

## 10. 测试与验收标准

### 单元与 HTTP 契约

- 密码校验成功返回记录当时版本，校验期间数据库版本变化不能导致结果自动刷新。
- 邮箱验证码消费前捕获账户版本，消费后的结果保持该版本；失败不生成 LoginPrincipal。
- 普通用户与管理员初始阶段均复核账户、版本和角色；失配或账户消失返回 INVALID_CREDENTIALS，管理员不得绕过检查生成 Ticket。
- Ticket 版本 0、非零和 long 上限正常往返；缺版本、空值、正负号、前导零、非数字、小数、溢出及不完整元数据均拒绝，无默认值。
- 兑换复核失配返回 LOGIN_TICKET_INVALID；原有角色选择拒绝行为保留。
- JWT 的 tokenVersion 只来自已复核的固定快照，不从晚到的另一次查询取版本；公开请求/成功响应字段和 JWT 声明格式不变。
- 验证码、Ticket、状态服务和限流故障沿用各自错误码，无凭据、摘要或 Token 泄漏。

### 真实 MySQL、Redis、BCrypt 与签名 JWT

- Testcontainers MySQL 8.4 与真实 Redis；正常登录、Ticket Lua 发布/消费、版本复核和 JWT 验证不以 mock 替代真实外部依赖。不用 H2。
- 用户名密码、邮箱密码、邮箱验证码、管理员 USER/ADMIN 角色选择成功回归，含合法版本 0 和非零版本。
- 通过闩锁或栅栏暂停在真实 BCrypt 校验完成之后，递增数据库版本，再继续初始复核：401 INVALID_CREDENTIALS，不得到新版本有效 JWT；管理员同样不能生成可兑换的新版本 Ticket。
- 邮箱验证码消费完成后递增版本，再复核拒绝；已消费验证码不能重新使用，未消费且仍有效的验证码仍可按正常流程发起新验证。
- Ticket 签发后递增版本，兑换返回 401 LOGIN_TICKET_INVALID；数据库角色相同但版本不同也拒绝。
- 构造真实旧格式 Redis Hash，不含 tokenVersion，即使当前账户版本为 0 也拒绝；不可回填为当前版本。
- Redis 中验证完整四字段与 TTL 一次发布，Ticket 最长 300 秒；无有效 TTL 的异常记录拒绝。同一 Ticket 并发兑换最多一次获得上下文，重复请求拒绝。
- 在最后状态复核之后、JWT 编码之前递增版本，验证仍只可能签出旧版本 JWT，随后通过真实受保护接口得到 TOKEN_REVOKED。不能通过断言“必定当场 401”掩盖真正的版本升级风险。
- 在初始复核之后、Ticket 发布之前递增版本，验证 Ticket 保留旧版本，兑换被拒绝。
- 校验角色变更撤销、Ticket 角色不匹配、账户消失、Redis 故障和版本状态查询失败；错误不返回假成功，不恢复已消费凭据。
- 并发测试使用明确超时及 finally 释放钩子/关闭线程，不依赖随机等待或执行顺序；测试用版本递增模拟 U03，不实现改密接口或额外 HTTP 入口。

### 构建与审核

运行 Windows PowerShell `.\gradlew.bat check`；为确保实际执行可用 cleanTest check。可启用 MINIO_INTEGRATION_TESTS=true 完整回归既有存储测试，但不扩大改动到 storage/track。必要 MySQL/Redis 测试被跳过不得声称完成，按 XML 汇总真实通过、失败、错误、跳过数量与原因。

送审明确为现有 POST /api/v1/auth/login 的认证版本加固单任务，提供需求、行为、全部变更含 untracked、迁移无、API 文档、基线、实际测试和部署边界。Claude 独立只读审核 accepted 后，才解除 U03 的认证加固前置门禁；届时记录加固实现提交，再回到改密设计核对实际代码基线。

## 11. 自查与设计门禁

已核对验证来源、初始复核与角色分支、Ticket 版本 0/缺失区别、Lua 发布/一次性消费、既有错误码、不可变快照、后复核竞态、混合部署和改密前置关系。无待定业务规则、旧 Ticket 默认升级、跨存储原子性承诺或其他接口实现。

当前只提交设计，未修改业务代码或执行实现测试。用户审阅并批准书面设计后，使用 writing-plans 为本独立加固任务制定实施计划；不直接开始改密实现。
