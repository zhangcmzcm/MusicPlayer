# 再次验证身份邮件请求接口设计

日期：2026-11-03。

状态：设计bad68f3及实现6fc1ddf均已获独立第1轮accepted；实现审核零阻断、零非阻断，独立带MinIO的cleanTest check为1105通过/0失败/0错误/0跳过、96类，本接口8类106项。用户已授权推送；[实现审核记录](../../reviews/2026-11-03-reauthentication-email-request-review.md)及[设计意见处置](../../reviews/2026-11-03-reauthentication-email-request-design-review.md)已闭环，生产部署验收另行执行。

基线：main @ 22af54e，工作区干净、与origin/main同步。认证版本与认证时间绑定均已独立审核通过；最近独立全量999通过、0失败、0错误、0跳过，88类。

## 1. 目标与单接口范围

本次仅设计 `POST /api/v1/users/me/reauthentication-email-requests`：有效登录本人向当前绑定邮箱申请“再次验证身份”专用验证码，返回challengeId和到期时间。

它是修改邮箱流程的前置发送接口，不直接修改邮箱，也不是向新邮箱发送变更确认码。继续沿用已确认的后续统一接口：

- `POST /api/v1/users/me/reauthentications` 接受method=PASSWORD＋password，或method=EMAIL_CODE＋challengeId＋code；两者验证成功都签发带新auth_time的访问Token，保留当前登录角色，不修改token_version、不撤销其他登录。
- 然后独立设计 `POST /api/v1/users/me/email-change-requests`，要求近期验证后向新邮箱发送专用变更凭据，不提前改邮箱；最终更新属于另一个确认接口。

本文件不授权实现这些后续接口、refresh、密码找回或修改任何已有业务接口。未绑定邮箱者选择密码再次验证；同时忘记密码时走独立恢复流程，不能用新邮箱替代本人当前身份凭据。

## 2. 现有能力与复用边界

- AuthenticatedUserPrincipal提供Integer userId和loginRole；认证过滤器已检查数据库角色/版本，并发布AuthenticatedSessionContext，包含已校验tokenVersion及可缺失authenticatedAt。
- 本接口只要求普通有效登录，不要求300秒内验证，否则会形成再次验证循环。旧JWT缺auth_time也可使用，但版本、角色、签名、issuer/audience和到期检查不能跳过。
- UserRepository.findAuthorizationSnapshot可读取本人邮箱、角色和版本；users.email可为null。
- 当前VerificationCodePurpose只有REGISTER/LOGIN，公开POST /api/v1/email/send-code直接接受该枚举；不得为复用而开放新的再次验证用途。
- 现有EmailVerificationCodeStore按用途/邮箱定位，不能表达本人、版本、challengeId、预备/激活和重发替换，不直接复用。
- 复用SecureVerificationCodeGenerator的安全随机能力、PasswordHasher/BCrypt及既有SMTP连接配置；不调用现有EmailSendCodeService或其限流器。
- 专用邮件发送封装和私有存储独立；注册/登录的发送、消费、限流和邮件流程保持原状，不更改公开用途枚举。
- ClientIpResolver使用连接remoteAddr，忽略任意自定义转发头；继续沿用，IP限流键使用地址摘要。部署方需确认反向代理下实际连接地址含义，不在本任务新增代理信任规则。

## 3. 方案选择

| 方案 | 收益 | 代价/结论 |
| --- | --- | --- |
| 复用现有按用途/邮箱的完整发送流程 | 接入少 | 缺少主体/版本绑定和预备激活，会扩展公开用途或影响注册语义，不采用 |
| 独立请求状态与有界发送，仅复用基础能力（选定） | 与登录/注册隔离，能表达发送后激活及并发归属 | 增加专用Redis状态和邮件封装，不需要新依赖或数据库结构 |

重发采用用户选定的A：每人最多一个有效请求。不是多个验证码并行有效，也不是请求一到就删除旧验证码；新请求激活时才原子替换旧请求。

## 4. HTTP与权限契约

- Method：POST；Path：`/api/v1/users/me/reauthentication-email-requests`。
- 有效Bearer JWT，USER/ADMIN均可操作本人；身份与版本只来自认证主体及可信内部上下文。
- **没有请求体**。零字节请求合法；空白、JSON空对象/null、任意字段或其他非空正文均400 VALIDATION_FAILED。不因为是发送接口而默默忽略请求体中的邮箱/ID。
- 无业务查询参数；查询参数、X-User-Id/X-Email等自定义头不影响身份或收件人，不将其转为业务输入。
- 可信上下文缺失或类型错误401 INVALID_TOKEN，不以当前数据库版本补齐上下文；authenticatedAt为空本身不拒绝。
- 从数据库按本人ID读取当前绑定信息，与Token上下文版本及登录角色允许范围核对；不存在或版本/角色已失效401 TOKEN_REVOKED。
- 当前邮箱null或全空白返回409 CURRENT_EMAIL_NOT_BOUND，不发信、不创建请求。收件地址按既有trim＋Locale.ROOT小写规则规范化；存储中的非空地址若不合法视为上下文不可用503，不把坏地址作为客户端输入错误返回。

成功返回201 Created、Cache-Control:no-store，无验证码/摘要/邮箱/版本或认证时间，只含两个字段：

```json
{
  "challengeId": "opaque-random-request-id",
  "expiresAt": "2026-11-03T08:05:00Z"
}
```

expiresAt为UTC ISO-8601 Instant。没有新增查询该资源的GET接口，不编造Location查询地址。本次不写用户表，不改变邮箱、密码、角色、token_version、updated_at或任何登录的auth_time，不签发Token。

## 5. 凭据与存储隔离

- challengeId为32个安全随机字节的Base64URL无填充编码，无法从用户ID/邮箱推导。不在日志中打印。
- 验证码为6位ASCII数字，含前导零；Redis只保存BCrypt摘要，明文只在生成与发送任务的必要内存中存在，不返回客户端、不写日志。
- 固定用途为再次验证身份，独立于REGISTER、LOGIN和未来新邮箱变更用途；不接受客户端传purpose。
- 绑定Integer userId、规范化当前邮箱、可信tokenVersion。不是仅绑定邮箱，也不按客户端提供的目标查找。
- 私有Redis命名空间例如music:auth:reauth-email:，按本人保存最多一个active和一个pending；后者不是有效验证码。以challengeId摘要标识请求归属，避免存储原始challengeId。
- 活跃记录具备状态、challenge摘要、本人/邮箱/版本、codeHash、expiresAt及初始错误次数0。后续消费接口必须严格检查ACTIVE状态和所有绑定，并做一次性消费；错误次数限制和消费事务由后续再次验证接口单独设计，本次不实现验证入口。
- 含邮箱、验证码或摘要的内部对象覆盖toString脱敏，不能把邮件信封或状态记录直接交给日志/HTTP序列化。

## 6. 限流与时间来源

在普通认证、零正文校验和当前账户绑定检查通过后，才消耗本接口独立额度；未绑定邮箱、非法请求或这之前的数据库故障不消耗额度。

- 每个userId发送尝试间隔至少60秒，跨设备、Token、登录角色共享。
- 每个userId每个UTC自然日最多10次获准发送尝试，UTC午夜重置日计数，但不重置尚在生效的60秒冷却。
- 每个连接IP最多5次/固定60秒窗口，从该IP本接口首个获准尝试起算，不是无限滚动窗口或自然分钟桶。与现有公开发送接口限流命名空间隔离，不声称是全项目邮件总额度。
- 任一限制触发429 REAUTH_EMAIL_RATE_LIMITED；Retry-After取当前所有阻塞条件所需等待时间的最大值，毫秒向上取整为秒，至少1。
- 同一Lua在写入前校验所有涉及状态，完成多额度检查、获准计数和本次pending归属预留；任一限流拒绝不新增计数、不延长窗口、不抢占旧请求。
- 邮件失败、发送容量不足、预留后哈希/数据库/Redis故障、激活冲突均不退已消费额度，不自动重发；成功、重新登录、换设备/Token不重置。
- 错误类型、非规范计数、缺失或异常TTL、未知脚本回包等503并停止，不能视作允许或删除状态后放行。Lua的原子可见性不等于脚本运行错误自动回滚已执行命令；结果未知可能已计数或预留，不自动重试。

Redis负责限流、租约和验证码期限的时间权威：预留/准入Lua读取Redis时间，以该“预留/准入时间”确定不可延长的expiresAt=预留/准入时间＋300秒及最长60秒pending租约；不是PREPARED或哈希完成时刻。UTC日界也按同一时间源计算。active激活使用原expiresAt和剩余期限，不从邮件确认时重新计5分钟。Java只转换UTC时间用于响应/邮件，不靠推进Java Clock模拟Redis过期。沿用现有单Redis服务前提，不在本任务做Redis Cluster改造。

## 7. 准备、发送与激活流程

1. 认证链完成已有校验；Controller执行零正文等入口校验，将actor、可信上下文和连接IP交给Service。
2. Service读取本人绑定快照，检查账户、版本、角色、邮箱；只做主键只读查询，不锁用户行。
3. 生成随机challengeId；Lua原子准入、计数并为其摘要预留唯一pending归属、邮箱/版本、租约和原始到期时间。旧active完全不动。未确认准入/预留则不哈希、不发信。
4. 在数据库事务外生成验证码摘要。pending初始为PREPARING、不可验证；摘要准备完成后，以本次摘要和未过期租约为条件原子置为PREPARED，保持原租约/期限。PREPARING/PREPARED都不能被消费。
5. 将收件人、验证码、到期时间交给独立有界邮件任务；发送任务只有邮件能力，无Redis激活、数据库或账户更新能力。
6. 请求线程取得SMTP成功确认后，再读取并核对账户邮箱、角色和版本。不匹配则不激活：账户/角色/版本失效401 TOKEN_REVOKED，邮箱变化但版本未变409 REAUTH_EMAIL_REQUEST_CONFLICT。
7. 激活Lua要求pending仍属于本次challenge摘要、为PREPARED、租约和原300秒期限均有效；把它原子置为当前唯一ACTIVE并替换旧active，再移除本次pending。active使用原绝对到期时间；旧请求一旦被替换不恢复。
8. 只有确认激活成功才返回201和challengeId/expiresAt；租约过期/被更新请求替换等确定冲突409，不返回假成功。

预备资源最久60秒；较晚获准的新请求可以在旧预备过期后建立新归属。旧请求迟到只能得到冲突，不能激活、取消或覆盖更新请求。所有取消/清理均带本次challenge摘要条件，不执行无归属DELETE。

故障前没有调用激活时，旧active按原期限保留（除非独立的其他成功请求已经替换它）。激活命令已发出却结果未知时返回503：可能新active已生效、旧请求已失效，不能承诺旧请求必然保留，也不回滚恢复旧active。

MySQL复核与Redis激活不是跨存储原子快照。两次查询之间/最后查询之后仍可能发生账户变化，邮件也无法撤回；旧绑定请求即使存在，后续再次验证兑换时仍须对照当前数据库邮箱/版本及认证主体核验，不能因发送成功跳过。复用同一凭据验证角色时不得提升输入Token的登录角色。

## 8. 有界邮件发送与失败处理

使用专用邮件封装，复用现有SMTP连接参数、发件地址和TLS/认证配置；为本封装的连接、读取、写入各配置5000ms超时，不修改既有公开发送客户端的行为。具体隔离构建方式在实施计划中按当前邮件组件确定，不直接改变共享JavaMailSender配置。

发送执行器最多4个任务、零排队，满载503 REAUTH_EMAIL_REQUEST_UNAVAILABLE。请求线程等待已提交发送任务最多20秒；此上限约束等待SMTP任务，不把它说成整个HTTP流程包含数据库/Redis在内的总耗时上限。

SMTP明确失败或等待超时返回502 EMAIL_SEND_FAILED，不激活本次请求；取消/清理只针对本pending，不能操作旧active。不退额度、不自动重发。即使清理失败，PREPARING/PREPARED也不是有效凭据，按租约失效，不因清理异常将其当作ACTIVE。

Future取消/线程中断不等于邮件未发出或已经撤回；超时后仍可能收到无效邮件。底层任务可能稍晚结束，实际占用槽位直到任务退出，不因逻辑超时另开无界线程或队列补偿；迟到结果没有激活权限。执行器生命周期受应用管理，关闭时停止接单并请求取消，保留底层网络超时。

SMTP成功只表示邮件服务器接受发送，不保证进入收件箱。发送成功后若状态变化、租约失效或激活失败，邮件可能已送出但验证码不可用；明确返回401/409/503，不误报201。邮件内容说明“用于修改邮箱等敏感操作前再次验证身份，不会直接修改邮箱”，含验证码和原expiresAt，不包含Token或密码。

响应丢失或激活结果未知时，本次没有幂等键或查询恢复接口；客户端等待冷却后重新申请，成功的新请求替换旧请求。不通过重新发送相同明文或恢复旧请求掩盖不确定性。

## 9. 错误、缓存与日志

| 场景 | HTTP / code |
| --- | --- |
| 任意非空正文等入口非法 | 400 VALIDATION_FAILED |
| 未认证 | 401 UNAUTHORIZED |
| JWT声明或可信上下文非法 | 401 INVALID_TOKEN |
| 账户消失、版本/角色失效 | 401 TOKEN_REVOKED |
| 没有当前绑定邮箱 | 409 CURRENT_EMAIL_NOT_BOUND |
| 预备归属/租约冲突、已超原期限、邮箱绑定变化 | 409 REAUTH_EMAIL_REQUEST_CONFLICT |
| 冷却/日额度/IP额度超限 | 429 REAUTH_EMAIL_RATE_LIMITED，Retry-After |
| SMTP失败或等待发送结果超时 | 502 EMAIL_SEND_FAILED |
| 认证链查库失败 | 503 AUTH_STATE_STORE_UNAVAILABLE |
| 本服务数据库/Redis故障、状态/回包异常、激活结果未知、发送满载 | 503 REAUTH_EMAIL_REQUEST_UNAVAILABLE |
| 其他未预期故障，如意外哈希故障 | 500 INTERNAL_ERROR |

业务错误走GlobalExceptionHandler/现有安全链，application/problem+json、稳定code与下划线urn；例如urn:problem:music:reauth_email_rate_limited。成功显式no-store，业务错误沿用组合Cache-Control中的no-store和现有安全头。JWT签名错误/过期保留现有资源服务器响应，可能无ProblemDetail；不为本接口横向统一错误格式。

所有新增诊断只允许脱敏exceptionType、traceId及必要安全堆栈。SMTP/SQL/Redis原异常消息可能包含完整邮箱、验证码、摘要或请求标识，不能直接记录原异常链；新封装/新异常须提供安全诊断，不因复用现有EMAIL_SEND_FAILED稳定code而把敏感原因传给旧日志路径。错误响应同样不回显收件地址、验证码、摘要、底层SMTP/SQL/Redis细节。无新增Actuator暴露。

## 10. 测试与验证

- MVC契约：USER/ADMIN本人；无认证、旧Token缺auth_time但仍有效、版本失效、上下文缺失；空正文201，JSON空对象/null、空白及伪造字段400，参数/头部不改变收件人。
- 当前邮箱未绑定409；规范化与当前邮箱/版本绑定；发信前后删除账户、改版本/角色/邮箱，分别验证不激活、无用户表写入。整体比较用户行不变，不更新auth_time或签发Token。
- 真实Redis：60秒冷却、10次UTC日额度、IP固定窗口5次，所有桶独立且跨Token/设备共享；限流拒绝不延长/新增计数，失败不退额度。UTC日界、损坏类型/计数/TTL、空/未知回包、Lua故障均覆盖，真实TTL过期不靠sleep或只改Java Clock。
- 真实Redis状态：PREPARING/PREPARED不可消费为active，旧active在发送失败时保留，成功才替换；归属CAS、最长60秒租约、原300秒到期和期限不续命；旧取消/迟到任务不动新请求，激活结果未知不伪造保留旧状态。
- 受控发送器用闩锁制造成功、明确失败、结果超时、晚结束、饱和4任务/零排队，不等待真实20秒来制造单测；可注入更短的测试等待时间，但生产固定20秒。确认迟到任务无法访问激活操作，槽位仍被占用时拒绝新任务，不无界创建线程。
- SMTP封装测试验证收件人仅为数据库地址、用途与到期时间文案、5000ms网络超时隔离、发件/TLS配置保留，以及异常/内部对象脱敏。受控邮件发送器不冒充真实外部邮件送达；数据库/Redis/BCrypt/JWT正常链仍用真实依赖。
- Testcontainers MySQL8.4和Redis、真实BCrypt及签名JWT走真实过滤链；不能只靠WebMvcTest注入主体证明绑定。并发有闩锁/栅栏、超时及finally释放，不sleep，不依赖测试顺序。
- 回归注册/登录发送流程与公开用途只允许REGISTER/LOGIN，现有认证时间、版本绑定和改密撤销不被改变；不为验证本发送接口新增假的生产兑换HTTP入口。

基准 `.\gradlew.bat check`，未实际运行时cleanTest check。若触及track/storage测试夹具则必须启用MINIO_INTEGRATION_TESTS=true；可主动完整回归既有MinIO，但不改歌曲生产逻辑。按本次XML报告通过/失败/错误/跳过及类数，必要MySQL/Redis未执行不能当作通过。

## 11. 预计实现范围与交付

新增专用Controller、响应DTO、Service、Redis请求状态/限流仓储、私有状态对象、邮件封装和有界发送执行器；仅新增本接口异常与GlobalExceptionHandler映射，复用已有主体/可信上下文、生成器、PasswordHasher、SMTP配置及适合的用户查询。

不新增依赖、配置项、迁移或用户字段，不改VerificationCodePurpose公开枚举、注册/登录完整发送Service、认证过滤器、JWT签发或既有业务接口。具体类、隔离邮件客户端构建和Lua/线程边界由实施计划列明，不能为了复用做无关公共组件重构。

新增中文API文档docs/api/user/request-reauthentication-email.md和必要部署说明，完整OpenAPI说明201/400/401/409/429/502/503/500、无正文、响应字段、限流与失败不确定性。实现后按单接口协议提交文件清单、基线、命令与数量、无迁移、curl及反馈处置材料，独立审核通过后再推进统一再次验证接口。

## 12. curl示例

不传请求体、收件邮箱或身份参数；ACCESS_TOKEN由调用者安全管理，以下不含真实凭据。

```sh
curl --request POST 'https://music.example.com/api/v1/users/me/reauthentication-email-requests' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```

```powershell
curl.exe --request POST 'https://music.example.com/api/v1/users/me/reauthentication-email-requests' `
  --header "Authorization: Bearer $env:ACCESS_TOKEN"
```

## 13. 自审与门禁

已核对单接口、无正文、普通登录与近期验证区别、双方式后续契约、当前/新邮箱用途隔离、单active加预备状态、发送后原子激活、归属清理、原期限不续命、限流与不退额度、邮件任务有界、迟到结果无权限、跨存储竞态诚实说明、隐私与真实测试边界。无本接口待定业务规则、占位实现或额外接口授权。

用户审阅本书面设计后，进入writing-plans创建本接口实施计划；计划确认后再编码。当前文档不表示部署、邮件送达或后续再次验证/邮箱变更能力已完成。
