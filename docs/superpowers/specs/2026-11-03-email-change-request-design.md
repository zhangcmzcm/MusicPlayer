# 向新邮箱发送变更验证码接口设计

日期：2026-11-03。

状态：设计提交6f29752已获用户提供的Claude第1/3轮设计审核accepted，零阻断、一条非阻断NOTE-001；本文按该意见微调415措辞，业务契约不变。尚未开始实现，设计accepted不代表实现accepted。处置见[设计审核记录](../../reviews/2026-11-04-email-change-request-design-review.md)。

基线：main @ 36cf271；设计前工作区干净。用户提供的最近独立全量验证结果为1285通过、0失败、0错误、0跳过，本轮文档工作未重跑该验证。

## 1. 范围与前置条件

本次只设计 `POST /api/v1/users/me/email-change-requests`：当前登录用户向目标新邮箱申请变更专用验证码。允许尚未绑定邮箱的用户通过此流程首次绑定。

本接口不更新邮箱、不预占目标邮箱、不写用户表、不签发Token、不刷新auth_time、不递增token_version、不撤销登录。最终验证新邮箱并更新账户的接口另行设计，方法与路径不在本文确定，也不在本次实现。

已完成的真实认证时间记录、当前邮箱再次验证发码和本人再次验证接口保持原契约。300秒敏感操作门禁只随本接口落地，不扩展到其他既有接口。再次验证成功保留原登录角色和认证版本，不使已有邮箱变更申请失效。

## 2. 方案选择与代码边界

| 方案 | 收益 | 成本与结论 |
| --- | --- | --- |
| 独立Redis邮箱变更申请状态 | 表达本人、原邮箱、新邮箱、认证版本和预留/激活状态，隔离验证码用途 | 采用；无需数据库迁移或新依赖 |
| 扩展注册、登录通用验证码存储 | 表面上减少存储组件数量 | 扩大既有链路改动，不采用 |
| MySQL申请表加Redis限流 | 便于持久化申请历史 | 当前无历史查询需求，增加迁移、过期清理和跨存储协调，不采用 |

Controller负责HTTP契约；Service负责近期验证、账户与可用性核验、发送协调；专用Redis存储负责限流、租约及状态归属CAS；专用邮件封装只负责有界发送。输入与响应使用DTO，不返回Entity。

沿用既有随机码生成器、PasswordHasher/BCrypt、可信认证上下文和ClientIpResolver。邮件基础配置可复用，但现有再次验证邮件封装包含专用类型与异常，不能直接把新用途接入旧申请状态或改变旧接口行为。不为抽象复用进行无关重构。

实现预计涉及user模块的controller、dto、service、repository、domain、email和exception，GlobalExceptionHandler的必要映射，对应测试及 `docs/api/user/request-email-change.md`。近期验证门禁优先留在当前接口所需组件，不修改全局认证准入规则。准确文件清单在实施计划和审核材料中列出。

不修改任何已执行Flyway迁移；当前实际迁移已到V8，本接口无需V9。不涉及storage/track业务改动。

## 3. HTTP请求与校验

```http
POST /api/v1/users/me/email-change-requests
Authorization: Bearer <ACCESS_TOKEN>
Content-Type: application/json

{"newEmail":"new@example.com"}
```

- 只接受一个JSON对象及唯一字符串字段newEmail；缺正文、空对象、null、非字符串、重复字段、未知字段、嵌套结构或尾随JSON均返回400 VALIDATION_FAILED。解析不得依赖字符串类型自动强制转换。
- 使用UTF-8的application/json；非法JSON或编码返回400；缺失、不支持的Content-Type或显式非UTF-8字符集返回415 UNSUPPORTED_MEDIA_TYPE。当前没有可沿用的通用415契约，本接口新增专用异常及映射，不修改其他接口媒体类型行为。
- newEmail必填；按String.trim()去除首尾空白，使用Locale.ROOT转小写后，校验非空、合法邮箱和最长120字符。首尾普通空格允许被去除，内部空格不被删除。
- 原输入含CR/LF直接拒绝，不能通过trim消除换行后放行。只接受单个邮箱地址，拒绝显示名称、地址列表和邮件头注入形式；同时满足Bean Validation及邮件地址校验。
- 不做Gmail点号、+tag或其他邮箱服务商别名合并，不新增国际化地址转换规则，不放宽现有邮件基础设施的地址支持能力。
- 客户端不能指定userId、原邮箱、purpose、认证版本或认证时间；参数及请求头不能覆盖可信身份与申请绑定。

## 4. 身份与300秒门禁

USER、ADMIN均可操作本人，不以ADMIN身份操作其他用户。userId、登录角色及tokenVersion来自已有可信认证主体和内部上下文。

顺序为普通认证、当前接口近期验证门禁、严格请求解析及校验、数据库账户与目标邮箱核验，随后才进入发送准入。普通认证继续执行现有签名、issuer/audience、有效期、认证版本和角色检查。

业务准入时以应用Clock读取一次当前时间，使用可信authenticatedAt而非iat：`0 <= now - authenticatedAt <= 300秒`允许，包含300秒端点。缺少auth_time或超窗返回401 REAUTHENTICATION_REQUIRED。现有认证链对非法或未来auth_time的拒绝规则保持，不通过门禁补填或修正声明。

发送期间不再重复计算近期窗口；最终确认接口独立检查。Token过期或被撤销时使用既有普通认证失败契约，不能统一包装成REAUTHENTICATION_REQUIRED。前端仅对该专用错误保留输入并弹出再次验证，拿到新Token后重试；恢复登录与再次验证的交互不同。

门禁、非法请求、初次查库失败、邮箱未变化或已占用均不消耗发送额度。业务过程中仍核验数据库账户存在、认证版本一致且原登录角色仍被允许；撤销或权限变化沿用已有认证拒绝语义。

## 5. 邮箱可用性与绑定

- 当前邮箱使用既有trim加小写规则参与绑定；null或全空白统一表达未绑定。非空存量邮箱若不能合法解释，返回503，不作为客户端输入错误。
- 与当前邮箱相同返回409 EMAIL_UNCHANGED；被其他账户占用返回409 EMAIL_ALREADY_EXISTS。占用查询遵循现有MySQL邮箱唯一约束及排序规则，不另建与数据库冲突的唯一性定义。
- 同一邮箱因数据库唯一规则被判定等同，也不能通过大小写或其他表示差异绕过占用保护；若命中当前本人，归为EMAIL_UNCHANGED，命中其他账户才归为EMAIL_ALREADY_EXISTS。
- 申请绑定Integer userId、规范化原邮箱或未绑定标记、规范化目标邮箱、可信tokenVersion。用途固定为邮箱变更，独立于REGISTER、LOGIN、旧邮箱再次验证。
- 发信前读取绑定与目标可用性，发信后激活前再次复核。账户绑定变化返回409 EMAIL_CHANGE_REQUEST_CONFLICT；账户已撤销按认证错误处理；目标被其他账户占用返回409 EMAIL_ALREADY_EXISTS。
- 发码不预占邮箱。MySQL复核与Redis激活之间仍可能发生变化，最终确认必须再次核验绑定、可用性并使用数据库唯一约束保护；不能承诺邮件发送后目标始终可用。

## 6. 成功响应及凭据

确认激活成功才返回201 Created，携带Cache-Control: no-store：

```json
{
  "challengeId": "<32字节安全随机数的Base64URL无填充编码>",
  "expiresAt": "2026-11-03T08:05:00Z"
}
```

expiresAt使用UTC ISO-8601 Instant。不新增申请查询接口，不构造无法访问的Location地址。

验证码为6位安全随机ASCII数字，允许前导零，Redis仅保存BCrypt摘要。challengeId不可由账户或邮箱推导，存储仅保存其SHA-256摘要；不把验证码返回客户端。响应丢失后不重复返回相同凭据。

验证码、challengeId、邮箱、Token和内部状态对象不进入日志。内部对象toString脱敏，异常诊断沿用既有脱敏方式，日志保留traceId。错误响应不泄漏数据库、Redis、SMTP信息或底层异常。

## 7. 限流、时间与状态

专用Redis命名空间与所有既有用途隔离，例如music:auth:email-change:。每人最多一个ACTIVE和一个发送中的pending，重发同一邮箱与更换目标邮箱使用同一用户约束。

| 维度 | 规则 |
| --- | --- |
| userId | 至少间隔60秒；每UTC自然日最多10次获准发送尝试，跨设备、角色、Token共享 |
| 规范化目标邮箱 | 至少间隔60秒；每UTC自然日最多10次获准发送尝试，跨申请账户共享 |
| 连接IP | 固定60秒窗口最多5次，从该IP首个获准尝试起算 |

ClientIpResolver继续采用连接remoteAddr，忽略不可信转发头；IP和目标邮箱限流键使用摘要，不新增代理信任规则。以上是本用途的额度，不是全项目邮件总额度。

Redis Lua原子检查所有额度、现有pending及状态完整性，通过后一次扣额并预留；拒绝时不扣任何额度、不延长窗口、不抢占旧申请。任一限流触发返回429，Retry-After为所有阻塞额度所需等待时间的最大值，毫秒向上取整为秒，至少1秒；限流检查通过但已有有效pending时返回409 EMAIL_CHANGE_REQUEST_CONFLICT，不扣额度。

UTC午夜只重置日额度，不清除60秒冷却。成功、重发、换Token、重新登录不清除额度。准入后的发送失败、容量不足、哈希或存储故障、复核失败、激活冲突均不退款、不自动重试。

Redis为限流、租约及申请到期的时间权威。预留准入时取得Redis时间：expiresAt固定为该时刻加300秒，pending租约最长60秒。ACTIVE沿用原绝对到期时间，到期时刻起失效；准备、发送、激活、再次验证均不延长期限。应用Clock只负责JWT近期门禁，不能通过移动应用时钟模拟Redis过期。

状态转换为：预留PREPARING → 保存码摘要PREPARED → 邮件接受及绑定复核 → 原子激活ACTIVE。pending不是可兑换凭据。激活必须同时校验本次challenge摘要归属、PREPARED状态、绑定、租约及原到期时间，再替换旧ACTIVE并移除本次pending。

Redis错误类型、缺字段、非法计数、TTL异常、未知回包或损坏状态返回503，不能清空后放行。Lua原子可见性不等于运行错误回滚；命令结果未知时不自动重试、不假定未扣额或未激活。沿用当前单Redis部署前提，不进行Cluster改造。

## 8. 有界邮件与故障处理

专用邮件发送沿用已有模式及SMTP连接、发件、TLS/认证配置，连接、读取、写入超时各5000ms；最多4个并发任务、零排队，请求等待最多20秒。仅当前用途使用该有界执行器，不改动旧邮件用途的容量或超时。饱和返回503。

发送任务仅取得收件人、验证码和expiresAt，没有Redis、数据库或激活能力。邮件明确说明用于确认新邮箱、完成后才会修改绑定，不混用旧邮箱再次验证文案；SMTP接受不等于保证进入收件箱。

SMTP明确失败或等待超时返回502 EMAIL_SEND_FAILED，不激活本次申请。请求线程中断保留中断标记并取消等待。Future取消不保证邮件撤回；任务仍占用槽位直至实际退出，迟到任务不得激活，不额外创建无界线程或队列。执行器由应用管理生命周期。

激活前明确失败，旧ACTIVE保留原到期时间，除非已被其他独立成功请求替换。清理只按本次归属取消pending，不删除或恢复ACTIVE；清理失败记录脱敏日志，pending仍由租约自然失效。

激活命令已发出但结果未知时返回503：可能新申请已生效且旧申请已失效，不能承诺旧申请保留，也不执行恢复旧申请的补偿。租约失效等确定冲突返回409。

没有幂等键或申请查询恢复接口。201响应丢失、激活结果未知后，客户端等待冷却再重新申请；成功的新申请替换旧申请，不恢复旧码或重发同一码。

不在长数据库事务内等待SMTP，不使用跨MySQL/Redis/SMTP的伪原子事务。

## 9. 错误契约

统一application/problem+json，稳定code，type遵循现有urn:problem:music:*格式；按现有GlobalExceptionHandler规则将code转小写，保留下划线。例如UNSUPPORTED_MEDIA_TYPE对应urn:problem:music:unsupported_media_type；不改现有problem构造规则或另立同码异义映射。

| 状态 | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 请求结构、字段、编码或邮箱校验失败 |
| 401 | REAUTHENTICATION_REQUIRED | 有效登录但缺少auth_time或超出300秒 |
| 401/403 | 沿用现有认证授权错误码 | Token无效、过期、撤销、主体不合法或权限不再被允许 |
| 409 | EMAIL_UNCHANGED | 与当前邮箱相同 |
| 409 | EMAIL_ALREADY_EXISTS | 目标被其他账户占用，包括发信后复核发现 |
| 409 | EMAIL_CHANGE_REQUEST_CONFLICT | 已有pending、绑定变化、租约失效或确定激活冲突 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 缺失、不支持或格式非法的Content-Type，以及显式非UTF-8字符集；本接口专用映射 |
| 429 | EMAIL_CHANGE_RATE_LIMITED | 任一发送额度超限，附Retry-After |
| 502 | EMAIL_SEND_FAILED | SMTP明确失败或等待发送超时 |
| 503 | EMAIL_CHANGE_REQUEST_UNAVAILABLE | 本接口查库/Redis故障、状态损坏、执行器饱和或激活结果未知 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE等现有认证错误码 | 请求进入业务前的认证状态基础设施故障 |

意外程序错误遵循现有脱敏500处理，不把所有RuntimeException伪装成已知业务失败。

## 10. 后续确认接口必须遵守的约束

以下仅固定与申请兼容的业务规则，不实现最终确认接口，不在本文指定其Method或Path：

- 确认时再次检查有效登录及包含300秒端点的近期门禁；超窗先再次验证，已有申请在原期限内继续可用，新Token不延长申请期限。
- 必须核验ACTIVE、challengeId归属、本人、原邮箱、目标邮箱、认证版本及到期时间，不能仅凭目标邮箱和验证码更新账户。
- 同一申请累计错码5次失效，正确码一次性消费；成功重发的新申请从0次开始。旧申请的并发确认或计错不得误操作替换后的新申请，即使两次随机码碰巧相同。
- 最终邮箱更新使用数据库事务、并发条件与唯一约束保护，不能以发码时检查替代最终检查。Redis消费与MySQL提交的故障恢复、并发确认以及邮箱更新和认证状态协调机制，由该接口单独设计，未授权通过先消费再无条件更新等方式简化。
- 最终邮箱更新成功时递增token_version，撤销当前及其他全部已有登录，不给当前操作签发续登录Token，用户重新登录。首次绑定与更换邮箱遵循同一策略。
- 后续refresh可以更新iat/exp，但必须继承原auth_time，不能将刷新视为再次验证；本次不实现refresh。

## 11. 验证与交付

- MVC：USER/ADMIN本人、无认证、撤销、旧Token缺auth_time；299秒、恰好300秒及超窗边界；发信耗时跨过门禁窗口仍按准入结论执行；严格JSON、重复/未知字段、空值及类型强转拒绝。
- 输入：首尾空格、小写规范化、规范化后120/121字符、空白、非法地址、CR/LF、多收件人、显示名称；占用查询与MySQL唯一约束一致，不做服务商别名合并。
- MySQL：未绑定邮箱可申请、相同邮箱和其他账户占用；发信前后账户删除、邮箱/角色/版本变化和目标并发占用。比较用户行，确认邮箱、updated_at、版本等均未被本接口写入。
- 真实Redis：三层额度及交集、UTC日界与冷却、IP固定窗口、跨账户目标邮箱额度、并发准入、429及409不扣额、失败不退款、TTL和损坏状态关闭放行。
- 状态：pending不可消费；发送失败保留旧ACTIVE，成功才替换；同邮箱重发和换目标均每人唯一；旧清理/迟到任务不影响新状态；原绝对期限、租约失效、激活前明确失败与激活结果未知分别验证。
- 邮件：专用用途文案、目标为规范化新邮箱、网络超时隔离、4任务零排队、20秒等待上限、取消及迟到任务边界、敏感对象与嵌套异常脱敏。使用可控发送器制造故障不等于验证真实收件箱送达。
- 通过真实发送入口及Testcontainers MySQL/Redis覆盖成功和状态交错，不只mock存储结果。测试使用受控时间或同步屏障处理边界，Redis过期验证不能仅推进Java Clock。
- 回归登录、原auth_time、再次验证、原角色保留、认证版本、旧用途验证码隔离；确认本接口不签发Token或撤销登录。

实现后更新OpenAPI的Tag、Operation、ApiResponse、Schema及参数约束，并提供中文API文档，包含认证、归属、规范化、限流、错误码、失败边界及可执行curl。

实现验证基准为PowerShell：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat check
```

按实际执行结果报告通过、失败、错误、跳过数量，列明跳过原因；必要容器跳过不算完成。当前仅设计文档，不执行或声称本接口测试通过。

后续实现的curl契约示例，ACCESS_TOKEN须替换为本人有效且满足近期验证条件的凭据：

```bash
curl -i -X POST 'http://localhost:8080/api/v1/users/me/email-change-requests' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"newEmail":"new@example.com"}'
```

## 12. 设计自查与后续步骤

已核对单接口范围、未绑定邮箱准入、两道独立300秒门禁、旧登录不变与最终全部撤销的阶段差异、原邮箱/目标/版本绑定、三层额度、原绝对期限、成功后替换、明确失败与未知结果边界、日志脱敏、迁移不变及真实集成验证要求。无占位业务规则。

用户已批准本文并提供设计accepted结论，当前使用writing-plans编写中文实施计划，待用户审阅计划；设计通过不授权跳过计划审阅或直接开始业务代码实现。最终确认接口仍是后续独立任务，不以本文的兼容约束宣称其设计或实现已完成。
