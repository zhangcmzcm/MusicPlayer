# 修改密码接口设计

日期：2026-11-01。

状态：用户已确认接口契约、全部旧 Token 撤销、新密码边界、限流、CAS 更新及认证加固实施顺序；书面设计待审阅。

基线：main @ 0cc33c3。开始设计时工作区干净，已与 origin/main 同步。

## 1. 目标与单接口范围

本次设计 U03：`PUT /api/v1/users/me/password`。已登录本人通过原密码校验后设置新密码，提交成功时递增 token_version，撤销包括当前登录在内的全部旧 Token。客户端清除旧 Token，以新密码重新登录；接口不签发新 Token。

需要有效 Bearer JWT，USER 与 ADMIN 登录身份均可操作本人；身份只取自认证主体 userId，保持 Integer 类型。不提供管理员修改他人密码、密码找回、邮箱修改、刷新 Token 或单设备退出能力。

本接口不新增数据库字段、迁移或依赖，不修改已执行的 V1—V8，不更换现有 PasswordHasher/BCrypt 算法。API、设计、计划和提交说明使用中文。

## 2. 已核实的现状与实施前置条件

### 2.1 现有能力

- AuthenticatedUserPrincipal 包含 userId、loginRole，JWT subject 为用户 ID，不依赖用户名确定本人。
- PasswordHasher 由 BCryptPasswordHasher 实现；数据库密码摘要列为 users.password_token。
- token_version 为非负 BIGINT，认证过滤器在每次受保护请求中读取数据库版本并与 JWT 比较，不匹配时返回 401 TOKEN_REVOKED。
- BCrypt 当前依赖对新密码 UTF-8 编码超过 72 字节抛出异常；matches 校验路径不增加该新密码上限。现有注册只校验 8—128 字符，与编码上限不一致，可能返回 500。该注册缺陷已告知用户，应独立修复，不混入本接口或认证加固任务。
- ProblemDetail 的 type 使用 urn:problem:music: 加 code.toLowerCase()，保留下划线。

### 2.2 已确认的认证加固前置任务

当前密码登录校验后会另外读取最新认证快照再签发 Token，LoginPrincipal 未携带验证时版本；管理员 LoginTicketContext 和 Redis Ticket 也未保存认证版本。因此单纯改密递增版本，不能阻止已验证旧密码的登录请求或已有角色选择 Ticket 随后换取新版本有效 Token。

用户已明确同意以下顺序，不能将认证加固与改密实现混为一次变更：

1. 完成并审阅本改密设计。
2. 单独设计、计划并实现认证版本绑定加固，不新增 HTTP 接口，独立测试并经 Claude 审核 accepted。
3. 回到本设计，核对加固后的实际代码基线，再制定改密实现计划、实现、测试和送审。

前置任务必须达到的安全结果：密码验证结果绑定该次验证所依据的账户版本；管理员角色选择 Ticket 绑定相同认证版本；登录后续阶段不得用新读取的版本升级旧验证结果。已知验证版本或 Ticket 版本失效时拒绝继续登录/兑换。若版本检查后又发生并发撤销，后续签发也不能产生通过新版本检查的有效 Token。

前置任务必须用可控并发验证：密码验证完成后递增版本、Ticket 签发后递增版本，两者都不能再换取新版本有效 Token。旧格式且不带版本的 Ticket 应拒绝，不通过默认新版本延续其有效性。具体组件调整与测试在独立加固设计中确定，本文件不授权实施该任务。

改密接口的“全部旧 Token 撤销”保证建立在此前置结果上。仍不承诺中止已通过认证进入业务层的请求、已提交的操作或已开始的媒体传输；不能把版本检查描述为全系统原子停机。

## 3. 方案比较与选择

| 方案 | 优点 | 代价 |
| --- | --- | --- |
| CAS 条件更新（选定） | BCrypt 校验和哈希均在写事务外，减少锁占用；并发由条件更新拒绝旧快照覆盖 | 需读取密码快照，并明确 0 行更新的冲突语义 |
| 悲观锁后校验与哈希 | 流程直观，用户行上串行处理 | BCrypt 运算期间持续占用数据库锁，增加锁等待 |

选用 CAS：条件同时匹配本人 ID、读取的原摘要和 token version；成功时原子写入新摘要、版本加一及数据库 UTC 更新时间。不重新尝试 CAS、不自动重新读取后覆盖并发结果。

## 4. HTTP 请求与密码规则

- Method：PUT；完整 Path：`/api/v1/users/me/password`。
- Content-Type：application/json，普通 JSON 对象。
- 无路径用户 ID、业务查询参数、幂等键或客户端版本参数。查询参数和自定义身份头不参与身份、密码或更新目标判断。
- 仅允许 oldPassword、newPassword 两个字段，各出现一次，字段顺序不限。

```json
{
  "oldPassword": "old-example-password",
  "newPassword": "new-example-password"
}
```

| 字段 | 类型 | 校验与语义 |
| --- | --- | --- |
| oldPassword | string | 必须存在、非 null、非全空白，8—128 字符；与现有密码输入边界一致，不额外限制到 72 字节 |
| newPassword | string | 必须存在、非 null、非全空白，至少 8 字符，UTF-8 编码最多 72 字节；不能与当前密码等价 |

字符长度沿用项目 Bean Validation 对 Java String 长度的计数口径；新密码额外按实际 UTF-8 字节数校验，不能只用字符数代替 BCrypt 上限。原密码不增加 72 字节限制，以保持既有校验行为的兼容性。

两个密码均按原值处理，不 trim、不折叠大小写、不做 Unicode 规范化、不截断、不预哈希。含首尾空格的非全空白密码保留空格，不因 trim 而改变凭据。

缺体、空对象、JSON null、非对象根值、多个根值、缺任一字段、额外字段、重复键（含转义后的重复名称）、非字符串、长度/字节数非法，统一返回 400 VALIDATION_FAILED。数字或布尔值不得隐式转为字符串；不接收确认密码、用户 ID、邮箱、角色或 tokenVersion 字段，额外字段为 null 也拒绝。

采用请求 DTO 局部的严格绑定与 Bean Validation，不改变全局 ObjectMapper 或已有注册、登录、资料修改请求的行为。认证链先执行；非法格式请求不进入本接口限流和 BCrypt，但认证过滤器本身仍可能查询数据库。

## 5. 本人限流规则

使用独立 Redis key 命名空间，按已验证主体的 userId 计数，例如 `music:auth:password-change:user:<userId>`。不按用户名、Token 或客户端传入 ID 分桶，不复用登录失败、IP 限流或验证码计数。不保存任何密码、摘要或 Token 到限流数据中。

- 固定窗口：从该用户第一次有效格式改密请求起 15 分钟，即 900 秒。
- 窗口内最多允许 5 次尝试。通过请求格式与字段校验后，在任何 BCrypt 运算之前消费一次额度。
- 使用单次 Lua 原子操作建立窗口、设置 TTL、判断并计数，避免并发超过配额。
- 第 6 次及之后返回 429 PASSWORD_CHANGE_RATE_LIMITED，并设置 Retry-After 为窗口剩余等待秒数，向上取整且最少为 1。
- 原密码错误、新密码等价、CAS 冲突或数据库故障均消耗已获准的本次额度，不补偿 Redis 计数。
- 超限不延长窗口；成功、重新登录、换用其他设备或换发 Token 都不重置该用户窗口。
- Redis 故障、超时或无法可靠判断配额时返回 503 PASSWORD_CHANGE_RATE_LIMIT_UNAVAILABLE；不绕过限流，不进行 BCrypt 或密码状态写入。
- 限流 key 类型、计数或 TTL 损坏时同样拒绝继续，不以删除限流记录的方式放行。

Redis 计数和 MySQL 更新不构成分布式事务。Redis 请求结果不确定时可能已经消费一次额度，仍按限流存储不可用处理；不伪造“保证未计数”。Redis 不在数据库写事务内调用。

## 6. 处理顺序、密码校验与组件边界

1. 现有安全链完成 JWT、账户角色和版本校验，生成认证主体。
2. Controller 通过局部绑定、@Valid 或等价入口校验取得两个密码字段，仅把认证主体与请求交给 Service。
3. Service 按主体 userId 请求独立 Redis 限流器，先消费配额。
4. Repository 按本人主键查询专用内部密码快照，仅包含必要的用户 ID、password_token 和 token_version，不加载整份 Entity，也不复用公开资料 DTO。
5. 在数据库写事务外，以 PasswordHasher.matches(oldPassword, 当前摘要) 校验原密码；失败返回 400 OLD_PASSWORD_INCORRECT，不继续检查新密码或生成摘要。
6. 原密码通过后，用 matches(newPassword, 当前摘要) 判断新密码是否与当前密码等价；相同返回 400 NEW_PASSWORD_SAME_AS_OLD。不能重新 hash 后比较两个随机盐摘要字符串，也不能仅比较请求中的两个明文字符串。
7. 通过校验后，在写事务外调用现有 PasswordHasher.hash(newPassword)，生成新 BCrypt 摘要。
8. 开启短写事务，执行 CAS 更新，提交确认成功后向 Controller 返回；Controller 构造 204 与 no-store，无响应体。

Controller 不处理业务错误或密码哈希，异常通过统一处理器转换。Repository 仅负责快照、条件更新和必要的存在性查询；Service 负责校验顺序、限流协调、事务与故障翻译。BCrypt 和 Redis 均不占用数据库写事务。

请求 DTO 与内部密码快照的字符串表示必须脱敏，不让 record 默认 toString 暴露明文或摘要。快照不进入 Controller，不序列化到 HTTP 响应，也不写日志。OpenAPI 将密码字段标记为只写、password 格式；示例仅使用非真实示例值。

## 7. CAS、并发与事务边界

CAS 只允许赋值 password_token、新 token_version 和 updated_at。版本在数据库原值基础上加一，updated_at 使用数据库 UTC 当前时间并保留微秒；用户名、邮箱、角色、创建时间、最后登录时间和其他账户不变。

条件同时检查 userId、原摘要和预期版本。原摘要使用二进制精确比较，避免 users 默认大小写不敏感排序规则使不同 BCrypt 字符串被当作相等；不能采用只检查 ID、只检查旧摘要或只检查版本的弱条件。

- 更新 1 行：事务提交确认成功后才返回 204。
- 更新 0 行：在同一短事务中用存在性查询区分。账户不存在返回 401 TOKEN_REVOKED；账户仍存在返回 409 PASSWORD_CHANGE_CONFLICT，不自动重试、重新验证后覆盖或返回假成功。
- 密码或角色变更导致 token version 已改变，均应使旧快照的 CAS 失败。
- 两个已通过认证的并发改密请求即使都验证了相同旧摘要，也最多一个成功更新；另一个若已进入处理阶段返回冲突，尚未通过认证的后续请求按版本失效返回 401。
- 版本不得溢出、回绕或复位；已达 BIGINT 有符号上限时拒绝改密并按密码存储不可用处理，记录脱敏诊断，不执行加一更新。

密码快照读取、BCrypt 校验和写事务不是单个长事务；CAS 明确保护这段期间的状态变化。不新增密码历史或独立版本列，不需要数据库迁移。

写事务中的数据库错误回滚未提交更新；数据库读写、事务启动、锁超时、死锁和提交确认故障统一按密码存储不可用返回 503，不自动重试。提交确认阶段连接中断时，数据库可能已经提交，因此 503 不保证密码或撤销状态一定未变化；客户端应通过登录确认，不能通过 GET 读取密码摘要或版本确认。

## 8. 成功响应与撤销边界

成功返回 204 No Content、Cache-Control: no-store，无 JSON、无密码字段、无新 Token，也不返回摘要或 token version。

客户端收到成功后清除当前 Token，要求重新登录。密码摘要和 token_version 同次提交后，包括当前调用使用的 Token、其他设备的旧 Token，都会在之后经过数据库版本校验时返回 401 TOKEN_REVOKED。

新密码可用于用户名密码和邮箱密码登录，旧密码不能再完成新的密码验证。邮箱验证码登录仍按既有规则进行，不因设置新密码而禁止其他已支持的认证方式。

由于成功后调用凭据已被撤销，使用原 Token 重复请求通常返回 401，不保证重复返回 204；不引入保存明文、复用旧摘要或延长旧 Token 有效期的幂等方案。

旧登录验证结果、旧管理员 Ticket 的阻断依赖第 2 节认证加固。已经通过认证进入业务层的请求或已经开始的传输不保证被中途取消，文档与测试不得将撤销范围夸大为全系统瞬时中断。

## 9. 错误契约与日志

| 场景 | HTTP / code |
| --- | --- |
| JSON、字段、类型、重复键、字符/字节长度非法 | 400 VALIDATION_FAILED |
| 原密码错误 | 400 OLD_PASSWORD_INCORRECT |
| 新密码与当前密码等价 | 400 NEW_PASSWORD_SAME_AS_OLD |
| 缺少认证 | 401 UNAUTHORIZED |
| JWT 必需声明非法 | 401 INVALID_TOKEN |
| 已撤销版本、登录角色不再允许、账户不存在 | 401 TOKEN_REVOKED |
| CAS 的密码或版本快照冲突 | 409 PASSWORD_CHANGE_CONFLICT |
| 固定窗口配额耗尽 | 429 PASSWORD_CHANGE_RATE_LIMITED，带 Retry-After |
| 认证状态查询失败 | 503 AUTH_STATE_STORE_UNAVAILABLE |
| Redis 限流不可用或配额判断不可靠 | 503 PASSWORD_CHANGE_RATE_LIMIT_UNAVAILABLE |
| 密码状态读写、版本无法递增或写事务故障 | 503 PASSWORD_CHANGE_STORE_UNAVAILABLE |
| 未预期错误 | 500 INTERNAL_ERROR |

业务错误使用 application/problem+json，遵循现有 ProblemDetail 与缓存、安全头约定，携带稳定 code。type 为下划线格式，如 urn:problem:music:old_password_incorrect、urn:problem:music:password_change_conflict。所有成功和业务错误响应禁止缓存。

JWT 签名错误、过期及资源服务器解析失败沿用既有安全链响应与 WWW-Authenticate，可能没有 ProblemDetail 响应体，本次不统一改造其他接口。没有正常已认证角色的额外 403 或本人资源 404 分支。

业务错误 detail 使用通用文案，不包含两个密码、摘要、Redis key、SQL、Token、内部路径或底层异常信息。Bean Validation 不得在默认消息中拼接输入密码。SLF4J 故障日志保留 traceId、exceptionType、必要 throwable，但不记录请求/快照字符串、密码字段或底层包含敏感参数的诊断值；必要时先脱敏再记录。

## 10. 预计实现与文档范围

- user/controller：单独的 PUT 修改密码入口，HTTP、入口校验、主体与 OpenAPI。
- user/dto：仅两个密码字段的请求与本接口局部严格绑定、UTF-8 字节边界验证；敏感字符串表示脱敏。
- user/domain：内部密码状态快照，摘要不进入公开 DTO 或日志。
- user/service：密码修改协调、BCrypt 校验、限流调用、CAS 与短写事务；独立密码修改限流器。
- user/repository/UserRepository：新增必要的密码快照和 CAS 更新，复用合适的存在性查询。
- user/exception、GlobalExceptionHandler：仅新增本接口错误处理，不改变资料 GET/PATCH 和登录错误契约。
- src/test/java/org/example/music/user：请求、校验器、Service、HTTP、真实 MySQL/Redis/JWT 与并发集成测试。
- docs/api/user/change-password.md：中文 API 文档，含请求、长度/字节边界、限流、撤销、错误、并发、提交不确定性和 curl。
- docs/superpowers/plans、docs/reviews：前置加固完成后的本接口实现计划和单接口审核材料。

具体类名、文件和基线必须在前置加固 accepted 后按实际代码确定。本设计只说明改密接口所需职责，不预先修改共享认证主体或代替认证加固任务定义实现清单。

## 11. 测试与完成标准

### 请求、密码与保密

- 仅 oldPassword/newPassword，任意顺序可用；严格拒绝缺体、空对象、null、未知字段、重复/转义重复键、错误类型、非对象和多根值。
- 原密码 8/128 字符边界及超界，新密码最小长度，以及 UTF-8 71/72/73 字节边界；多字节示例覆盖 24 个普通汉字为 72 字节可编码、25 个为 75 字节拒绝。字节失败在哈希前返回 400，不让 BCrypt 长度异常变成 500。
- 首尾空白原样处理；全空白拒绝，合法密码不被 trim 或规范化。
- 原密码错误优先返回 OLD_PASSWORD_INCORRECT，不执行新密码等价检查或哈希；新密码等价时不写库、不递增版本。
- 验证不是用随机盐摘要字符串判断相同密码；兼容既有长原密码校验路径。
- 请求、快照 toString 脱敏，错误和日志不包含明文或摘要；没有敏感字段的响应序列化。

### 限流与故障

- 真实 Redis 验证 5 次放行、第 6 次 429，Retry-After 与实际 TTL 对齐；窗口不因超限或重新登录延长/重置。
- 同一用户不同 Token 共用配额，不同用户隔离；无效格式不消耗改密额度，不使用客户端伪造 ID 分桶。
- 并发请求原子计数，最多 5 次进入 BCrypt；Redis 故障/超时/损坏状态时 503，不调用 BCrypt 或更新密码。
- 原密码错误、新密码等价、CAS 冲突、数据库失败后额度不补偿；数据库回滚不假装 Redis 也回滚。

### 事务、并发与真实认证

- Testcontainers MySQL 8.4、真实 Redis、真实 BCrypt 与签名 JWT 经过实际安全过滤链；禁止 H2，禁止仅靠注入主体的 WebMvcTest 证明认证正确。
- 成功严格 204、无响应体、no-store；比对完整数据库行，只有 password_token、token_version、updated_at 改变，其他账户和列保持不变。
- 版本只递增一次，摘要匹配新密码、不匹配原密码；读取 UTC 文本验证更新时间，避免 ORM 时区写读相互抵消。
- 原 Token、另一设备旧 Token、不同登录角色旧 Token 均在后续认证中被拒绝；新密码可登录，旧密码新的验证失败；邮箱验证码方式不被本接口意外禁用。
- USER、ADMIN 登录身份均只能改本人，JSON 伪造 ID 400，查询/自定义身份头不改变操作对象。
- 原密码、新密码等价、账户消失、版本撤销、CAS 冲突、MySQL/Redis 故障、事务启动/提交确认故障均有对应 HTTP 契约及无假成功验证。
- 受控并发让两个请求读取同一快照并完成校验，验证最多一次 CAS 成功；与角色版本更新并发时不覆盖版本或角色。使用栅栏/闩锁及超时清理，不靠随机等待。
- 版本上限不能溢出；0 行更新的存在/不存在分支正确。写故障时摘要与版本一起回滚；提交确认异常不承诺数据库一定未提交。
- 前置认证加固的独立测试必须已经证明旧验证结果与旧 Ticket 不会换取新版本有效 Token，改密集成再验证真实改密后的同一边界。

基准为 Windows PowerShell `.\gradlew.bat check`；测试未实际执行时使用 cleanTest check。可启用 MINIO_INTEGRATION_TESTS=true 完整回归既有存储测试，但不扩大到 storage/track 修改。必要 MySQL/Redis 集成测试被跳过不得声称完成；按 XML 汇总实际通过、失败、错误、跳过及原因。

实现完成后按 AGENTS.md 提供唯一 Method/Path、权限、声称行为、全部变更含 untracked、迁移无、API 路径、实际命令与数量、基线、curl 和未决项，交由 Claude 独立只读审核。

## 12. curl 示例

仅使用环境变量示意凭据，不包含真实 Token、密码或验证码；实际调用需使用 HTTPS。PASSWORD_CHANGE_BODY_FILE 指向调用者自行安全准备的请求 JSON 文件，包含上述两个字段；文件不进入 Git，调用后按调用者策略清理。

```sh
curl --request PUT 'https://music.example.com/api/v1/users/me/password' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_CHANGE_BODY_FILE}"
```

客户端收到 204 后清除旧 Token，使用新密码重新登录。不在 URL 查询参数中发送密码，也不将真实密码硬编码到示例命令或文档。

## 13. 自查与后续门禁

已核对单接口、前置加固、原密码验证、BCrypt 字节边界、密码等价、限流窗口、CAS 条件、版本递增、撤销边界、提交不确定性、错误码与真实测试范围。无待定业务规则、占位实现、算法迁移或额外接口。

本次仅提交书面设计，不编写改密代码或实施认证加固。用户审阅本文件后，按已确认顺序先进入独立认证加固任务的设计、计划、实现与审核；前置 accepted 后再回到改密接口的 writing-plans。不得因本设计确认而越过此前置门禁。
