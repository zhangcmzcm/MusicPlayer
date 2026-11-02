# 登录认证时间绑定加固设计

日期：2026-11-02。

状态：书面设计8e652e4及后续实现均已通过独立第1轮审核；实现零阻断、零非阻断，设计两条建议由审核者核实关闭（代码侧）。[处置记录](../../reviews/2026-11-02-auth-time-binding-design-review.md)、[实施计划](../plans/2026-11-02-auth-time-binding-plan.md)及[实现审核材料](../../reviews/2026-11-02-auth-time-binding-review.md)已闭环；部署验收仍独立跟踪。本文保留设计阶段的范围与前置约束，不代表后续接口已经实施。

基线：main @ 3a27229，工作区干净并与 origin/main 同步。登录认证版本绑定加固5e8ceba、U03改密及注册密码规则修复均已独立审核accepted；最新已确认完整回归925通过、0失败、0跳过。

## 1. 目标、范围与后续顺序

本次仅加固现有 `POST /api/v1/auth/login`，将真实凭据验证时间作为 `auth_time` 贯穿初始登录、管理员角色选择Ticket和JWT。为后续敏感操作提供可信的近期验证依据，不新增HTTP接口、数据库迁移、刷新凭据或服务端会话表。

用户已确认后续采用独立再次验证接口，支持密码或当前绑定邮箱专用验证码，在当前页面弹窗完成；邮箱变更请求不提前修改邮箱。为遵守单接口工作流，依次推进：

1. 本设计：现有登录的认证时间绑定，独立计划、实现和审核。
2. 受保护的再次验证验证码发送接口：收件人来自本人当前绑定邮箱，不由前端指定，专用凭据绑定本人、邮箱和认证版本。
3. `POST /api/v1/users/me/reauthentications`：PASSWORD或EMAIL_CODE二选一，验证后签发带新auth_time的访问Token，保留当前登录角色，不递增token_version、不撤销其他登录。
4. `POST /api/v1/users/me/email-change-requests`：要求近期验证，向新邮箱发送绑定本人及目标邮箱的专用验证码，不提前修改邮箱。
5. 后续独立邮箱变更确认接口：验证新邮箱归属后才更新。

步骤2的路径、限流和发送契约，步骤3至5的完整状态与错误处理，分别在对应设计中确认，不由本文件授权批量实现。发送接口不要求近期验证，否则会形成无法再次验证的循环；需要普通有效登录。未绑定邮箱者只能以密码再次验证，若同时忘记密码则走独立账户恢复流程，不能以新邮箱替代当前账户身份验证。

本次不实现refresh，不改变U03改密、资料查询或其他既有业务接口的近期验证要求，不修改密码策略、登录方式、角色选择权限、认证版本撤销、限流或验证码用途。

## 2. 已核实的现有代码

- LoginPrincipal目前携带用户ID、用户名、邮箱、最大角色与tokenVersion；LoginTicketContext携带用户ID、最大角色、原登录方式与tokenVersion，均无认证时间。
- PasswordLoginService在matches之前复制账户身份与版本，matches通过后返回该快照；EmailCodeLoginService先验证LOGIN验证码，再读取用户并复制快照，消费成功后返回。
- UserLoginService初始登录与Ticket兑换均复核账户存在、角色、版本，JWT签发使用固定授权快照，不允许旧验证回填新版本。
- LoginTicketService使用四字段Redis hash、原子发布和带TTL检查的一次性消费；精确字段集校验拒绝旧格式、额外字段和缺失字段。
- JwtTokenService当前仅在签发时生成iat/exp，JWT没有auth_time；不能把iat推断为原始凭据验证时间。
- TokenVersionAuthenticationFilter完成JWT身份、角色、版本校验后，将认证替换为包含AuthenticatedUserPrincipal的UsernamePasswordAuthenticationToken。主体只有Integer userId、loginRole，原JWT信息不继续携带；当前没有getDetails/setDetails的生产使用。
- JWT现有HS256、issuer、audience、有效期和逐请求查库策略保持现状。无需修改users表或V1—V8迁移。

## 3. 方案比较

| 方案 | 收益 | 代价与结论 |
| --- | --- | --- |
| JWT携带可信auth_time（选定） | 与现有无状态访问Token一致，不增加会话存储；角色选择和未来refresh可显式继承 | 必须贯穿验证结果/Ticket并严格校验，不能在签发时重置 |
| 服务端按会话保存近期验证状态 | 可集中维护单会话状态 | 需要会话ID、存储与生命周期管理；不能只按用户保存，否则其他旧Token也可能被间接提升。本次不引入 |

采用JWT声明加内部认证上下文。前端只负责携带Token，不能指定或覆盖认证时间。

## 4. 认证时间的产生与传递

auth_time表示服务器完成凭据验证的时间，不表示Token创建、角色选择、Ticket兑换或未来refresh时间。

- 密码路径：在matches返回true之后立即记录服务端UTC时间，使用已经复制的账户身份/角色/版本构造带认证时间的不可变验证结果，不重新读取可变Entity回填版本。
- 邮箱验证码路径：validate成功返回时立即记录时间；继续既有账户查询和快照复制，只有后续一次性consume成功才返回验证结果。consume失败不得签发Token，不恢复验证码。认证时间不改成查询、消费或签发完成时间。
- 后续查询、活动记录、限流协调、管理员等待选择角色及签发耗时都不刷新认证时间。即使签发时验证已经超过300秒，普通登录仍可成功，但不应获得近期验证资格。
- LoginPrincipal新增非空认证时间；LoginTicketContext新增同一时间。字段必须显式传递，不提供以当前时间或0代替缺失时间的构造器。
- 初始登录复核后的角色选择结果必须保留原验证时间；数据库授权快照不成为认证时间来源，不修改UserAuthorizationSnapshot来伪造验证时间。
- 使用可控UTC时钟验证时序；按秒向下取整，避免序列化往返改变时间或因舍入生成未来时间。精确Clock接入方式由实现计划确定。

## 5. 管理员Ticket

Ticket继续使用现有32随机字节、SHA-256派生key、300秒TTL与一次性消费，不增加HTTP字段或延长原有效期。

Redis hash从四字段变成精确五字段：userId、maximumRole、loginType、tokenVersion、authTime。authTime为规范非负ASCII十进制Unix秒文本：允许0，拒绝缺失、空白、符号、前导零、小数、科学计数、Unicode数字和溢出；0是真实旧时间，不是缺失默认值。

一次Lua发布完整五字段及TTL；读取仍通过带类型/PTTL检查的原子取出并删除。严格解析完整字段集，拒绝缺失、多余、重复或非字符串数据。验证时间必须能表示为合法Instant，且不晚于兑换时的服务器当前时间；非法票据401 LOGIN_TICKET_INVALID，存储故障或消费结果未知503 AUTH_STATE_STORE_UNAVAILABLE。

旧四字段Ticket不补齐authTime，拒绝并要求重新登录；消费失败后的凭据不恢复，未知结果不自动重试。已有userId/角色/版本/登录方式严格解析和数据库版本复核全部保留。

Ticket自身TTL从发布时计时；auth_time从凭据验证完成时计时，两者含义不同。不能用Ticket剩余TTL倒推auth_time，也不因为auth_time超过300秒就将仍有效的Ticket当作认证失败；只是不授予近期资格。

## 6. JWT签发与请求校验

### 6.1 签发

真实登录JWT必须显式接收验证时间，将其写为JSON整数Unix秒的auth_time。iat/exp仍按当前签发时间和既有有效期生成，其他声明及登录成功响应JSON保持原状。

内部验证时间缺失、非法、未来或晚于本次iat时，不签发凭据；属于内部调用或时钟异常，不通过now回填掩盖故障，沿用未预期错误处理。数据库版本仍来自已经复核匹配的固定快照，不能为补时间重查并升级版本。

现有三参JwtTokenService.issue若为兼容既有调用保留，其语义固定为签发不带auth_time的旧式Token，不得默认写入当前时间；实际交互登录必须使用显式时间的签发路径。显式时间接口传null必须拒绝，不能降级为旧式签发。具体签名与调用清单在实现计划中固定，测试证明真实登录路径不调用旧式签发。

### 6.2 受保护请求

签名、issuer、audience和原有时间声明先沿用现有校验。对auth_time按以下规则处理：

| 情况 | 行为 |
| --- | --- |
| 声明缺失 | 接受为没有近期验证证据的旧Token，继续既有身份/角色/版本校验 |
| 声明存在 | 必须为JSON整数、非负、可表示为Instant，并具备可比较的iat；auth_time不得晚于iat或当前服务器时间 |
| null、字符串、布尔、小数、数组、对象、负数、溢出、未来或晚于iat | 401 INVALID_TOKEN，不能当成缺失或普通旧Token |

不从客户端参数/头部读取时间，不用iat、Token过期时间、数据库updated_at或当前时间补齐auth_time。验证实际JWT解码后的类型行为，不能用转换器的隐式字符串/小数强制转换绕过JSON整数要求。

不额外增加未来时钟宽限。现有JWT解码器对其他时间声明的既有容差保持原状，但不因此为auth_time或后续300秒近期窗口增加宽限。跨实例必须同步时钟，时钟回退造成的未来时间按上述规则拒绝，不用补时规避。

主体保持AuthenticatedUserPrincipal(Integer userId, UserRole loginRole)。新增不可变内部认证上下文，包含已校验Token的tokenVersion和可缺失的authenticatedAt，通过服务端构造的Authentication.details传递；缺失用明确的空值表达，不用0假装存在。仅在JWT身份、角色、版本全部通过后发布上下文，不保留原始Token或密码，不序列化到HTTP响应。未来敏感接口缺少可信上下文时也必须拒绝近期资格。

## 7. 后续近期验证与refresh兼容约束

以下作为后续任务的共同契约，不在本次增加业务访问门禁：

- 近期资格条件为authenticatedAt存在，且 `0 <= now - authenticatedAt <= 300秒`，300秒端点包含；比较实际Instant，不用向下取整的分钟数延长窗口。
- 缺失或超过窗口时，敏感操作返回401 REAUTHENTICATION_REQUIRED；当前普通登录仍可使用。前端保留用户输入，弹窗进行再次验证，不能把所有401统一跳登录页。
- PASSWORD与EMAIL_CODE两种真正的再次验证都可产生新auth_time。EMAIL_CODE发到当前绑定邮箱且为专用用途；新邮箱验证不能冒充现有账户身份验证。
- 再次验证保留当前登录角色，不给普通USER提升到ADMIN，不递增token_version、不撤销其他登录；这是后续接口契约，不是本次新增行为。
- 未来refresh可更新iat/exp，但必须从可信会话记录或刷新凭据原样继承auth_time，不以刷新时间重置它；没有auth_time就继续缺失，需再次验证后才能获得近期资格。
- refresh必须拒绝已撤销版本，不能把旧会话的tokenVersion升级为最新数据库值。刷新凭据轮换、重放检测和会话模型独立设计，不将持有访问Token等同于可无限续签。

## 8. HTTP兼容、错误与日志

现有POST /api/v1/auth/login仍公开，PASSWORD/EMAIL_CODE及角色选择请求字段、成功DTO和状态码不变；保留登录限流和失败计数。JWT新增声明，Token本身属于不透明凭据，客户端不应自行构造auth_time。

现有初始登录版本失配401 INVALID_CREDENTIALS、Ticket失效401 LOGIN_TICKET_INVALID、角色选择不允许403及存储故障503均保留。受保护链遇到非法auth_time复用401 INVALID_TOKEN；缺失时间且版本已撤销仍401 TOKEN_REVOKED，不因兼容旧Token跳过版本检查。

本次不新增REAUTHENTICATION_REQUIRED错误映射或300秒业务限制。ProblemDetail、下划线urn、缓存与WWW-Authenticate沿用现有安全链现状，不顺手统一JWT解析错误或trace头。

日志不记录Token、密码、验证码、Ticket明文或原始JWT载荷，错误响应不返回底层解析信息。内部上下文不是公开DTO，不扩大资料接口响应字段。

## 9. 测试设计

- 可控时钟证明密码验证成功时记时、验证码验证时记时且消费成功才签发；失败不发凭据。
- 验证完成后延迟数据库查询、活动记录或JWT签发，auth_time不变；管理员延迟选择角色后iat更新而auth_time不变。
- 两种密码登录、邮箱验证码登录，USER及ADMIN的USER/ADMIN角色选择均保持权限与版本语义，真实解码JWT确认整数声明。
- 新五字段Ticket原子发布、规范文本解析、缺字段/旧四字段/重复多余字段/无TTL/错误类型拒绝、并发消费恰一成功，消费失败不恢复。
- 真实签名JWT经过实际过滤链：缺auth_time的旧Token访问普通接口成功、内部时间为空；合法字段进入上下文并保持已校验版本，不能从伪造头/参数覆盖。
- 覆盖auth_time为0、过去很久、等于iat/now、缺失、null、数字字符串、1.0等小数、负数、未来、晚于iat、超Long/Instant范围；必须分别证明缺失兼容与非法拒绝。
- 时间较旧仍可普通访问；不在本次给旧业务接口强加300秒门禁。后续敏感接口分别覆盖300秒端点与窗口外，不在本轮用假接口替代它们。
- 保留真实MySQL/Redis/BCrypt/JWT版本绑定和改密撤销回归，验证记录时间不会导致旧验证/旧Ticket升级版本；保留最终复核后竞态的诚实边界。
- 采用MockMvc契约、单元测试及Testcontainers真实依赖，不用注入主体代替真实JWT链；并发有闩锁、超时和finally清理，不sleep。

基准 `.\gradlew.bat check`，未实际执行时cleanTest check；可启用MINIO_INTEGRATION_TESTS=true全量回归，但不修改storage/track。按新生成XML报告通过、失败、错误、跳过，必要MySQL/Redis未运行不能声称完成。

## 10. 发布与回滚

- 不递增全站token_version、不撤销旧JWT、不扫描重写JWT，不清空Redis或修改已有迁移。
- 旧JWT缺auth_time只失去未来敏感操作的近期资格，普通访问按原到期/撤销规则继续；可通过后续再次验证获得新凭据。
- 四字段与五字段Ticket互不兼容。旧实例拒绝新Ticket、新实例拒绝旧Ticket；安排全实例升级和受控切流，旧Ticket用户重新登录，不用默认当前时间兼容。随机Ticket格式、key命名空间和TTL维持现状，不自动改写旧记录。
- 不在混合部署期间宣告认证时间能力全站生效；敏感接口上线前确认登录实例均携带并验证时间，旧在途登录处理已排空，实例UTC时钟同步。
- 回滚可能重新签发缺auth_time的Token，必须停止依赖本能力的新敏感接口上线/放行，不允许以iat兜底。已有版本绑定加固的部署条件仍需保留，不能退回已知可升级旧验证版本的登录实现。

## 11. 预计范围与门禁

预计涉及LoginPrincipal、LoginTicketContext、PasswordLoginService、EmailCodeLoginService、UserLoginService、LoginTicketService、JwtTokenService、TokenVersionAuthenticationFilter及新的内部认证上下文；相关单元/真实集成测试、登录API和部署文档。必要时增加可控时钟接入，不扩大共享认证主体、业务DTO、数据库结构或其他接口行为。

具体文件、签名、旧调用兼容方式与测试分组由writing-plans确定，但不得引入时间默认补齐。API文档、计划、提交与审核材料使用中文。本设计不允许开始再次验证发送/完成或邮箱变更实现。

自审已核对：单任务边界、验证与签发时间区别、不可变版本绑定、五字段Ticket、缺失与非法区分、旧JWT兼容、内部上下文、300秒未来门禁、refresh继承、时钟和混合部署风险。无待定业务规则或占位实现。

用户审阅书面设计后进入本前置任务writing-plans；实现完成按单任务审核协议交Claude独立只读审核，accepted后再开始下一接口。
