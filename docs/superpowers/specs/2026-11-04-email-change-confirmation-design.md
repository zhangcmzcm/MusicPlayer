# 校验新邮箱验证码并更新本人邮箱接口设计

日期：2026-11-04。

状态：用户已逐段确认方法与路径、请求、失败策略、提交限流、方案、事务与并发边界、错误表和验证范围；本文完成自查，待用户审阅书面设计。本轮仅设计，不代表接口已实现或独立审核通过。

基线：main @ 97215a0，开始设计时工作区干净；上一接口已获独立实现审核accepted并推送。已有全量证据为1445通过/0失败/0错误/0跳过、114类；97215a0对两条非阻断建议的收尾验证为53通过/0失败/0错误/0跳过、2类，不冒充再次全量。本轮文档不重跑或声称新接口测试通过。

## 1. 单接口范围及已确认前提

本次只设计 `PUT /api/v1/users/me/email`：当前登录用户提交已收到的新邮箱验证码，完成首次邮箱绑定或更换邮箱。仅操作本人，USER和ADMIN适用相同规则。

沿用[新邮箱发码设计](2026-11-03-email-change-request-design.md)及已实现的POST /api/v1/users/me/email-change-requests：

- 发码不修改账户、不预占目标邮箱；ACTIVE绑定本人、原邮箱或未绑定状态、目标新邮箱、认证版本及随机challengeId摘要。
- 验证码为6位ASCII数字，Redis仅存BCrypt摘要；申请从Redis准入起300秒到期，准备、激活、再次验证不续命。每人最多一个ACTIVE和一个pending，新申请激活才替换旧ACTIVE。
- 本接口要求300秒内的真实身份验证，含300秒端点。再次验证不改变认证版本，旧申请在原有效期内可以配合新Token继续使用。
- 本接口成功时递增token_version，撤销当前及其他全部旧登录，不签发继续登录Token；首次绑定也适用。

不增加第二个确认、申请查询、状态恢复或refresh端点，不扩展到其他未规划模块。不改变既有发码或再次验证接口的业务契约，不新增表、迁移、依赖或后台协调任务。

## 2. 方案比较与选择

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| Redis消费后，MySQL条件更新（选定） | 校验成功后原子消费申请，再在短事务内更新邮箱并递增版本 | 无需新表，不跨存储持锁；数据库失败后不恢复已消费码，符合用户选择 |
| MySQL先更新但不提交，再消费Redis | 数据库锁持有期间等待消费结果，成功才提交 | 可提前发现部分DB冲突，但延长锁持有，仍无法消除提交结果未知，不采用 |
| 持久化确认操作记录 | 新增数据库处理记录，协调重试与恢复 | 需迁移及恢复状态管理，超出当前所需能力，不采用 |

选择第一种意味着明确接受“凭据已消费但邮箱未更新”的失败结果，不将Redis消费和MySQL提交包装成原子事务。不通过恢复验证码、自动重试更新、增加隐式补偿或伪造成功掩盖该边界。

## 3. HTTP请求与严格校验

```http
PUT /api/v1/users/me/email
Authorization: Bearer <ACCESS_TOKEN>
Content-Type: application/json

{
  "challengeId": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "code": "012345"
}
```

| 字段 | 规则 |
| --- | --- |
| challengeId | 必填字符串，32字节标识的规范Base64URL无填充编码，恰好43字符；解码长度为32并重新编码相同，不能只检查字符集和长度 |
| code | 必填字符串，恰好6位ASCII数字，允许前导零；不trim、不接受数字类型、Unicode数字或首尾空格 |

仅允许这两个字段，必须同时存在。缺正文、null、数组、嵌套结构、非字符串、重复字段（含转义后同名）、未知字段、多个JSON根或尾随非空白内容均400 VALIDATION_FAILED。

正文总量含尾随空白最多16384字节，未知Content-Length及分块输入同样有界读取，不整读无限流；不修改全局Jackson限制。严格UTF-8解码及JSON解析，非法编码返回400。

缺失、非法或不支持的Content-Type、非application/json、显式非UTF-8字符集返回415 UNSUPPORTED_MEDIA_TYPE。复用本邮箱流程已确认的专用415规则，不新增改变其他端点行为的全局媒体类型映射。

客户端不再传newEmail、用户ID、原邮箱、认证版本、角色或认证时间；目标新邮箱只能来自ACTIVE。参数或头不能覆盖上述绑定；未知JSON业务字段直接拒绝。

## 4. 认证、近期资格与准入顺序

顺序固定为：普通认证 → 本接口一次近期验证门禁 → 严格请求解析/Bean Validation → 提交额度准入 → 申请与账户校验 → 验证码匹配 → 可用性检查 → 原子消费 → 数据库条件更新并提交 → 204。

普通认证沿用现有签名、issuer/audience、到期、认证版本及角色检查。可信userId/loginRole只取AuthenticatedUserPrincipal，tokenVersion/authenticatedAt只取AuthenticatedSessionContext；不信任请求声明。

复用EmailChangeRecentAuthenticationGuard及已有Clock，业务准入读取一次当前时间：`0 <= now - authenticatedAt <= 300秒`通过；缺少auth_time或超窗返回401 REAUTHENTICATION_REQUIRED。精确比较Duration，不取整放宽端点，不使用iat、不补填时间。非法或未来认证时间沿用认证链的INVALID_TOKEN规则。

解析、BCrypt、Redis或数据库耗时跨过300秒，不重复计算本次近期资格。验证码期限独立，原子消费时必须按Redis时间仍有效。

无认证、Token过期/撤销等按既有普通认证错误处理，不能改成REAUTHENTICATION_REQUIRED。原登录角色不得提升；服务再次核验账户存在、数据库版本与输入Token版本一致、输入登录角色仍被允许。重新登录或再次验证不返还本接口额度。

## 5. 提交限流与时间来源

每用户从首次获准提交起900秒固定窗口最多10次，跨Token、设备及登录角色共享。独立命名空间，例如music:auth:email-change-confirmation:user:<userId>，与新邮箱发码三层额度、再次验证提交额度隔离。本接口不增加IP额度。

只有普通认证、近期门禁及请求格式通过后才调用准入。后续正确码、错码、无效/不存在申请、业务冲突和存储故障均计入已获准次数，不退款。限流拒绝不新增计数、不延长窗口；成功、重发、再次验证和换Token不重置。

准入Lua以Redis TIME为时间源，计数与resetAtMs及真实绝对TTL一致；类型、字段、非规范计数或TTL损坏一律503，不删状态后放行。第11次返回429 EMAIL_CHANGE_CONFIRMATION_RATE_LIMITED，Retry-After为本窗口剩余毫秒向上取整为秒，至少1。

准入命令结果未知可能已计数，返回503并停止，不自动重试或补扣/退款。Java Clock只控制近期验证，不能靠推进它模拟Redis窗口或申请过期。

## 6. 申请读取、归属与验证码处理

以可信userId定位既有music:auth:email-change:active:<userId>，以客户端challengeId的SHA-256摘要核对归属；不按客户端指定的用户或目标邮箱定位申请，不读取其他用户的申请键。

读取/计错/消费脚本复用既有邮箱变更状态格式与校验基础，必要变动仅限当前确认所需，不改变生产者的预留/激活/重发语义。确认不把PREPARING或PREPARED当作可兑换凭据。

- 检查完整ACTIVE schema、挑战摘要、本人ID、原邮箱/未绑定标记、新邮箱、规范认证版本、BCrypt摘要、原reservedAt/expiresAt、实际PEXPIRETIME和当前错误次数。Java对脚本回包及字段再次严格校验，不将空、未知或错误类型回包当成功。
- 请求challenge不匹配、无ACTIVE、自然到期、已消费或被替换，统一400 EMAIL_CHANGE_VERIFICATION_FAILED，不披露是否属于他人。
- 存在于本人键却存有其他userId、非法schema/字段/版本/摘要/TTL等属于损坏状态，503；不能把内部损坏伪装成普通错码或自动清空后放行。
- Token有效但ACTIVE绑定旧认证版本，属于失效申请，400；服务查库发现当前Token已撤销或角色不再允许则401。
- 原邮箱与数据库现状不符但当前Token仍有效，409 EMAIL_CHANGE_CONFLICT。原邮箱比较先遵循已有trim/Locale.ROOT小写与null/全空白未绑定规则；写入CAS另保留原始数据库值，不能用不区分大小写/重音的SQL比较替代并发快照。
- 存量绑定或目标地址异常时503，不作为客户端传入newEmail的校验错误。地址保持已有规范化、最长120、合法单地址、CR/LF及孤立UTF-16代理字符拒绝规则，不增加服务商别名转换。

ACTIVE中的非空原邮箱及目标邮箱必须已经是生产者约定的规范形式；不能把损坏记录重新规范化后继续使用。数据库原始邮箱则保留原值，单独规范化后用于与绑定比较。

BCrypt匹配在数据库写事务外执行。错误码只对仍匹配本人、challenge摘要、原/新邮箱、版本、码摘要和原时间字段的当前ACTIVE原子计错。错误次数0—4；第五次错误使该申请失效并删除。不存在、不同归属或被替换后计错操作不得改动新申请；正常情况下仍统一返回400，不返回remainingAttempts。

正确码消费也按上述申请快照原子CAS删除，只要求消费时错误次数仍小于5，不因为其他失败将次数由0变1而误拒仍有效的正确码。消费前再次按Redis TIME及绝对TTL检查到期。

计错、正确消费并发时：第五次错误先完成则正确请求不能消费；正确消费先完成则迟到计错不能复活或影响其他申请。同一正确码并发最多一个消费成功；即使重发碰巧生成相同六位码，也按完整申请归属区分。

任何Redis读取/计错/消费故障或结果未知返回503；不恢复次数、不复活申请、不重试消费。尤其消费可能执行成功但回包丢失时，本次不能进入数据库更新。

## 7. 数据库条件更新与事务边界

复用已有四字段EmailChangeAccountSnapshot投影，保留数据库原始email、账户角色、tokenVersion与userId；不加载密码或完整Entity返回HTTP。绑定校验所用规范化原邮箱与SQL并发控制所用原始邮箱各有用途，不能混为一值。

正确码匹配后、消费前复核当前账户授权、原邮箱及目标占用：目标命中其他账户409 EMAIL_ALREADY_EXISTS；目标已等同本人当前邮箱409 EMAIL_CHANGE_CONFLICT，不返回幂等成功、不无意义递增版本。占用查询遵循现有ix_users_email及MySQL排序规则。预检查不预占邮箱，不能替代最终唯一约束。

消费前发现tokenVersion为Long.MAX_VALUE，503，不消费码；禁止溢出或回绕。记录将要用于写入的最新已核验账户快照，之后仍可能并发变化，由条件更新兜底。

收到Redis明确消费成功后，才启动数据库短写事务。条件更新按主键定位，要求原始邮箱（含NULL，以空值安全及二进制精确比较）、认证版本和已核验账户角色仍匹配，且版本可递增。一次SQL只修改email、token_version=token_version+1、updated_at=UTC时间；不覆盖用户名、密码、created_at、last_login_at等字段，不新增用户表不存在的审计列。

事务内不进行BCrypt、Redis、SMTP或Token签发。受影响行数必须为1；0行时在短事务内检查现状：账户删除、版本已变化或原登录角色不再允许按TOKEN_REVOKED处理，其余快照冲突409 EMAIL_CHANGE_CONFLICT。异常行数作为程序错误，不能假成功。

消费后不得以重新读取的新账户快照替换原CAS预期值；0行后的查询仅用于分类，不据此重试写入。否则会绕过已确认的并发快照保护。

目标邮箱在消费后才被并发占用，唯一约束拒绝更新，明确认定为邮箱唯一约束冲突时409 EMAIL_ALREADY_EXISTS并回滚；其他完整性问题、事务启动/执行/提交故障503，不能一概映射邮箱占用。整个事务中的邮箱、版本和更新时间一起提交或回滚。

无条件保存旧Entity或仅按ID更新都不允许。并发修改用户名不应被覆盖；改密或角色修改带来的认证版本变化必须使旧快照更新失败，不扩大修改这些既有接口。

## 8. 重发交错、失败后重试与登录撤销

Redis消费是“旧申请是否还能取得修改资格”的分界，不是MySQL已提交的证明：

| 顺序 | 结果 |
| --- | --- |
| 新申请先激活，旧确认后消费 | 旧归属不再匹配，400，不写数据库 |
| 旧申请先消费，新申请随后激活 | 已消费的确认可继续执行其条件更新；提交后的版本递增使同期旧版本申请失效 |
| 多个相同正确码同时确认 | 最多一个获准进入数据库更新；其他不能复用消费结果 |
| 消费前目标被占用或账户变化 | 拒绝确认，不主动删除尚未消费申请；已获准额度不返还 |
| 消费后数据库明确回滚/冲突 | 码保持已消费，邮箱按事务结果未更新；重新申请受既有发送限流约束 |
| Redis消费已执行但回包丢失 | 503，申请可能已消费；本次不执行SQL，不恢复码 |
| 数据库提交结果未知 | 503，邮箱及版本可能已更新；不承诺修改未发生，不重试SQL或恢复码 |
| 数据库成功提交但204响应丢失 | 邮箱已改、全部旧Token失效，不提供成功响应重放或查询恢复接口 |

不在确认完成后按userId无条件删除ACTIVE/pending；新申请可能已经存在，旧请求不得误删。旧版本记录依赖后续核验及原TTL失效，新发码仍按既有绑定检查和激活规则工作。

数据库成功提交后，现有TokenVersionAuthenticationFilter在后续认证中拒绝所有旧版本Token。不是只撤销当前设备，也不是只撤销其他设备；本接口不签发新Token，不把邮箱变更当作再次验证来刷新auth_time。前端收到204后清除当前Token，进入登录流程。

401近期资格不足时保留输入并再次验证，新Token可在原申请期限内继续。503或响应丢失后先核实当前邮箱；若旧Token已被撤销，则恢复有效登录后核实，可使用未改变的用户名登录。不能仅凭旧Token被拒绝就推断一定是本次修改成功；密码/角色变化等也可能撤销旧登录。

PUT的重复请求不要求返回相同状态码。本接口保证重复相同凭据不会再次修改邮箱或再次递增版本；额度计数属于请求防护。首次成功后用旧Token重试通常401，用后续新Token重放旧申请也不能据此获得204。

## 9. 成功及错误响应

只有数据库事务明确提交成功才返回204 No Content，Cache-Control: no-store，无响应JSON、Location或新Token。

业务错误使用application/problem+json与稳定code，type沿用现有urn:problem:music:加code转小写、保留下划线的规则。普通认证链的签名/过期错误继续沿用现状，可能无ProblemDetail；前置认证基础设施故障保留原AUTH_STATE_STORE_UNAVAILABLE。

| 状态 | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 请求结构/字段/编码/16384字节上限或标识、验证码格式错误 |
| 400 | EMAIL_CHANGE_VERIFICATION_FAILED | 错码、申请不存在、自然过期、已消费、被替换、不属于本人或绑定旧认证版本 |
| 401 | REAUTHENTICATION_REQUIRED | 有效登录但缺auth_time或超300秒 |
| 401/403 | 沿用现有认证授权错误码 | Token、可信主体或账户授权失效；本接口不另造角色403场景 |
| 409 | EMAIL_ALREADY_EXISTS | 目标被其他账户占用，含更新时邮箱唯一约束冲突 |
| 409 | EMAIL_CHANGE_CONFLICT | 原邮箱等快照冲突或目标已经等同本人当前邮箱 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 缺失/非法/不支持的媒体类型或显式非UTF-8字符集 |
| 429 | EMAIL_CHANGE_CONFIRMATION_RATE_LIMITED | 本用户提交额度超限，附Retry-After |
| 503 | EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE | 本接口DB/Redis故障、损坏状态、结果未知或版本不能递增 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 现有前置认证状态基础设施故障 |
| 500 | INTERNAL_ERROR | 意外程序错误，响应脱敏 |

不向客户端返回验证码、码摘要、认证版本、剩余尝试次数或底层异常。含challengeId、邮箱及摘要的请求/状态对象toString脱敏，SLF4J日志保留traceId；原SQL/Redis异常消息、cause/suppressed敏感链不得直接进入日志。

## 10. 组件与改动范围

确认Controller只取可信认证上下文、传递延迟解析入口、返回204；不接收客户端userId，不在Controller写门禁或邮箱业务规则。Service完成准入、快照比对、码验证、可用性、消费与条件更新协调；Repository只读投影或执行条件更新；业务异常经GlobalExceptionHandler统一映射。

预计新增user模块下确认controller/reader/dto/service、专用Redis确认存储及状态快照/异常，新增确认admit/read/fail/consume脚本。复用现有近期门禁、PasswordHasher、邮箱变更状态schema及最小账户投影，UserRepository追加条件更新，GlobalExceptionHandler仅补必要映射。不修改已执行V1—V8，不需要V9，不增加通用操作表或分布式事务基础设施。

准确文件名、跨任务方法签名及测试类在书面设计获批后的实施计划固定。任何必要的共享校验调整只限本流程，保留既有生产者状态和专用错误码契约，避免为复用而重构无关模块。无SecurityConfig、JWT签发、SMTP配置、CORS或Actuator范围扩展。

## 11. 测试与交付要求

- HTTP：唯一PUT路径、204无正文/no-store；USER/ADMIN本人、首次绑定；普通无认证/撤销/过期与近期不足区分，缺auth_time、299/300/超窗、准入后跨窗不重算。
- 输入：规范challengeId解码32字节及规范重编码、43字符但非规范末位/填充拒绝；六位ASCII数字与前导零、非字符串/混合用途/未知/重复转义字段/空体/非法UTF-8，16384/16385字节及未知长度正文；415和旧接口错误契约回归。
- 真实Redis：独立900秒窗口，第10次允许/第11次429、跨Token/角色/设备共享，不退款不续窗；真实TTL、UTC时间源、损坏配额/ACTIVE、未知回包与执行后丢回包，不能推进Java Clock伪装Redis过期。
- 码状态：真实发码入口生成ACTIVE，不只手工造成功记录；pending不可确认、到期、被替换、外用户challenge不改他人或本人新申请；错误1—4/第五次删除、正确一次性消费、相同随机码值但不同归属、计错与正确/重发交错。
- 真实MySQL：原始NULL、空白或大小写存量邮箱与规范绑定兼容；数据库排序规则下目标占用；条件更新只改邮箱/版本/更新时间，其他列保持；Long.MAX_VALUE不溢出。
- 并发：同码最多一次消费且最多一次邮箱/版本更新；新申请先激活与旧消费先发生两种顺序；与改密/角色/原邮箱/删除交错，条件更新不覆盖新状态；并发改名保留用户名。
- 故障：额度/读取/计错/消费故障、消费执行后丢回包不进入SQL；消费后事务开始失败、唯一冲突、明确回滚、提交已执行但回包未知、响应丢失，不退款、不恢复、不自动重试，故障注入不得伪造已提交成功。
- 撤销：成功后当前、其他设备的旧Token走真实认证链均拒绝，版本恰递增1，未签发Token；新邮箱/用户名可重新登录并使用新版本；失败回滚时用户行不改变。
- 日志/文档：新旧用途隔离、敏感DTO和异常脱敏，OpenAPI实际生成结果核对请求schema与全部响应码，不把仅声明403写成已触发403测试。

MySQL使用Testcontainers 8.4，Redis使用真实Testcontainers，保留既有测试镜像约定，不使用H2或只mock外部状态。并发与时序采用受控Clock、闩锁和真实Redis期限夹具，避免依赖sleep或测试顺序；不新增生产调试入口。

新增中文API文档docs/api/user/confirm-email-change.md，包含Method/完整Path、本人/角色/近期认证、请求字段校验、204、错误code/type、独立额度、重发交错和失败恢复边界、可执行curl。必要部署说明明确本接口不承诺跨Redis/MySQL原子性及503状态核实流程。

实现基准验证为PowerShell：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

如实聚合通过/失败/错误/跳过及类数，必要MySQL/Redis/MinIO测试不得跳过。实现完成按AGENTS.md准备全文件（含untracked）、基线、API/迁移、实际命令和数量、行为/权限、curl及反馈处置材料，再交Claude独立只读审核；遵守三轮及escalated边界。

以下为实施完成后的调用契约示例，目前不表示端点已经可用：

```bash
curl -i -X PUT 'http://localhost:8080/api/v1/users/me/email' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"challengeId":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","code":"012345"}'
```

ACCESS_TOKEN、challengeId及验证码示例均为占位调用数据，执行时替换为本人的有效凭据，不记录到日志。

## 12. 自查及后续步骤

已核对：单接口、既有发码兼容、仅两字段请求、精确300秒门禁、独立900秒10次额度、第五错失效、消费与替换顺序、原始邮箱CAS、NULL及唯一排序规则、版本溢出、数据库事务外消费、未知结果及不恢复、全部旧登录撤销、无迁移/依赖/其他接口、真实验证及独立审核。无未决定业务分支或实现占位条款。

用户审阅本文并批准后，使用writing-plans编写中文实施计划；此设计提交不直接开始业务代码实现，不以设计通过替代实现审核。最终确认接口的设计、计划、实现及审核仍按同一任务推进，原始提交记录保留。
