# 校验找回验证码并完成密码重置接口设计

日期：2026-11-05。

状态：用户已确认方法与路径、失败策略、独立提交限流、新旧密码等价处理、统一验证错误及全部分段契约，授权整理书面设计。用户已确认自查修正A：仅IP额度拒绝返回429，账户额度拒绝统一400，不暴露等待时间。书面设计c2530f3已获第1/3轮设计审核accepted，零阻断项；当前使用writing-plans编写实施计划，尚未开始业务实现。NOTE-001/002处置见下方记录。

基线：main @ 10e38d7，已推送，开始设计时工作区干净。前置申请接口已获第1/3轮独立实现审核accepted（审核至32a8235），其NOTE-003等价收尾源代码b564f85，本地完整回归145类1718通过、0失败/错误/跳过，MinIO实跑；处置待复核为非阻断，不冒充审核方已关闭。本次只新增设计文档，不将既有1718作为新接口验证结果。

## 1. 唯一接口与已确认前提

本轮唯一接口为`POST /api/v1/users/password-resets`。忘记密码的用户提交challengeId、邮箱验证码和新密码，完成密码重置。无需登录、旧密码或近期auth_time，USER/ADMIN账户同规则；目标账户只由服务端有效申请绑定确定，不能使用请求参数、请求头或已登录Token主体替代。

沿用已交付的[申请找回密码接口](../../api/user/request-password-reset.md)与[申请设计](2026-11-04-password-reset-request-design.md)：

- 用户输入绑定邮箱，统一202受理，返回随机challengeId和原expiresAt；不存在账户也同样扣额入队，但不产生可兑换验证码。
- challengeId为32字节随机数的43字符规范Base64URL无填充编码，Redis只保存摘要。验证码为六位ASCII数字，保存BCrypt摘要。
- 申请从Redis准入起300秒到期，排队、发送、激活、校验均不续命。新申请入队只替换pending，发送明确成功且复核后激活才替换旧ACTIVE。
- ACTIVE绑定账户、原始/规范化邮箱、账户角色及token_version；凭据变更使旧版本申请无法兑换，不将后台最终DB复核与Redis激活当跨存储事务。
- 本接口将落实累计错误5次失效、正确码一次性消费；成功在同一MySQL事务更新密码及版本，全部旧登录失效，不自动登录或签Token。

不增加申请查询、状态恢复、幂等重放查询、取消、自动登录或refresh端点。不改变前置发码契约、密码规则、既有改密/改邮箱接口、邮件投递、队列和任务生命周期，不为复用重构无关公共组件。

## 2. 方案比较与选择

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| 先原子消费Redis，再MySQL条件更新（选定） | 完成验证码/绑定/新密码校验及摘要计算后，明确消费成功才进入短写事务 | 无新表和恢复状态；接受消费后数据库失败需重新申请，未知结果不恢复、不自动重试 |
| 新增持久化重置操作记录 | 保存操作状态并协调消费、数据库更新与重复请求恢复 | 可支持故障后的结果确认/继续处理，但需要迁移、幂等及恢复机制，超出当前需求，不采用 |

用户明确选择第一种。Redis消费与MySQL提交不是一个原子事务；不通过重放204、恢复验证码或重新读取快照后重试SQL掩盖边界。实际消费成功后的代码不可恢复，不论后续结果如何。

## 3. HTTP输入与成功响应

```http
POST /api/v1/users/password-resets
Content-Type: application/json

{
  "challengeId": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "code": "012345",
  "newPassword": "new-example-password"
}
```

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| challengeId | string | 必填；恰43字符，Base64URL无填充，解码32字节且重新编码相同；不trim，仅长度/字符集检查不够 |
| code | string | 必填；恰6位ASCII数字，保留前导零；不trim，不接受数字类型或Unicode数字 |
| newPassword | string | 必填；8—72个ASCII可打印字符U+0020—U+007E，非全空格；首尾空格原样保留，不规范化、不截断、不预哈希 |

仅允许这三个字段各一次，顺序不限。缺体、null、非对象根、缺字段、非字符串、未知字段（包括email/userId/角色/oldPassword/confirmPassword）、重复键（含转义同名）、嵌套、多根及尾随非空白均400 VALIDATION_FAILED。确认密码由前端本地校验，不上传第四字段。

请求严格UTF-8，有界读取16385字节检测超过16384字节预算，包含尾随空白，不信任Content-Length；拒绝非法UTF-8、孤立代理和密码中的非ASCII/控制字符。只接受application/json，charset省略或UTF-8；缺失/非法媒体类型或其他charset为415。

成功为204 No Content、Cache-Control: no-store，无正文、Location、密码摘要、认证版本、新Token或登录角色选择结果。客户端清除旧登录信息，进入原有登录流程，用新密码登录；管理员角色选择仍按现有登录契约。邮箱验证码登录等其他既有能力不顺手修改。

仅新增此Method+Path公开放行。正常调用无需Bearer，主动附带无效/过期/撤销Token时仍由现有安全链拒绝，不为找回流程绕过JWT校验；带其他账户有效Token也不能改变申请目标，且不要求该Token近期认证。

## 4. 独立提交限流

| 维度 | 额度与窗口 |
| --- | --- |
| TCP客户端IP摘要 | 首次获准起固定60秒最多10次 |
| 申请绑定的账户ID | 首次获准起固定900秒最多10次，跨申请、设备、Token、角色及重发累计 |

使用独立确认命名空间，例如`music:auth:password-reset-confirmation:ip:<digest>`与`...:user:<id>`，不复用发码、登录、改密、邮箱变更或再次验证额度。沿用ClientIpResolver取remoteAddr，不信任伪造转发头。Redis TIME/PTTL确定窗口，不用JWT iat/auth_time或应用时钟重算。

严格格式校验通过后先扣IP额度，再读取ACTIVE申请；缺失/未激活/无账户/无效标识的格式合法提交也消耗IP额度，但没有可信账户时不创建用户额度键。只有完整读取到服务端有效绑定后才按其中userId扣账户额度，在数据库读取和BCrypt前执行。

两层是顺序准入，不是跨键全有或全无：IP已获准后，账户额度拒绝、DB/Redis故障或其他失败均不退IP额度；获准账户额度也不退。拒绝的当前层不增加计数、不延长窗口。仅IP额度拒绝返回429，附IP键真实PTTL向上取整秒数，至少1秒。账户额度拒绝与其他正常验证失败完全相同，返回400 PASSWORD_RESET_VERIFICATION_FAILED、同一title/detail及响应结构，不附Retry-After、scope、账户ID、拒绝层或剩余次数。成功、重发、重新登录及版本变化不清除额度。

额度存储损坏、无TTL、无法解释的回复或超时为503，不删除损坏键后放行。一次准入执行后丢回包可能已计数，不自动重试。IP拒绝不进入BCrypt；账户拒绝不继续真实验证码处理、数据库密码读取或写事务，仅执行下述统一失败路径的伪比较。

### 账户存在性与统一失败路径

用户选择以隐藏账户层拒绝原因换取统一响应，不能向客户端提示其具体等待时间；账户额度仍按900秒原窗口自动恢复，不因重新申请恢复。不存在邮箱的假challenge、正常申请失效及账户额度拒绝，在IP尚未超限时均为同一400；跨多次发码累计10次后也不能通过400/429或Retry-After区分账户存在性。该选择明确替代此前分段讨论中“两层都429”的条款。

不能只统一状态而留下显著BCrypt耗时差异。对IP已获准、尚未执行真实验证码BCrypt比较就决定普通400的路径，执行一次固定成本伪比较，再返回统一错误。使用现有PasswordHasher在初始化时以安全随机值生成内存中的伪摘要，成本与本流程验证码生产者一致；不硬编码密码/密钥、不持久化或输出伪摘要，不把伪比较的结果用于认证、计错或消费。已执行真实验证码比较的正常失败不再额外补一次，存储异常503也不伪装成400；IP超限直接429且不做伪比较。

对尚未通过验证码证明的失败，每次格式合法且获IP准入的提交只进行一次验证码成本的真实或伪比较，账户超限时不进入额外密码运算；代价是不存在申请也占用一次受IP额度限制的BCrypt成本。正确验证码证明后的新旧密码匹配、新摘要计算及最终CAS失败属于后续分支，不另做伪比较来掩盖这些必需运算。不使用sleep或无界后台任务填充时间。数据库/Lua/I/O仍有细微差异，不承诺完全等时，但避免状态、头字段及是否执行BCrypt形成明显的存在性线索。

## 5. Redis ACTIVE读取、计错与消费

保持已推送生产者schema不变：`music:auth:password-reset:active:<emailDigest>`为当前challenge摘要指针，`...:challenge:<digest>`为12字段HASH：schema、digest、emailDigest、email、admittedAt、expiresAt、userId、rawEmail、role、tokenVersion、codeHash、attempts。schema=1；原期限expiresAt−admittedAt=300000毫秒；attempts只允许0—4。tokenVersion为规范Long十进制字符串，不转换为Lua浮点值。

读、计错、消费分别使用原子脚本。字段白名单/数量、规范摘要与emailDigest关联、ID/角色/版本、合法邮箱、BCrypt摘要、期限、两个键的实际PEXPIRETIME及当前指针归属均需核验。普通缺失、已过期或被替换为统一400；结构损坏、错误类型、相互矛盾的活跃状态或结果未知为503，不尝试修复或删除后放行。只读取已激活challenge，不拿QUEUED/CLAIMED/PREPARED任务兑换，不为返回具体失败原因查询任务状态。

错误验证码只对仍属同一完整绑定、相同摘要/hash及原始期限的当前申请累计：第1—4次增加attempts且TTL不变；第5次原子删除本challenge及仅当仍指向它的ACTIVE。旧错码操作不能改新challenge、pending、latest水位、ready/outstanding、其他验证码或限流键。计错操作执行后丢回复为503，次数可能已增加，不补偿不重试。

正确消费同样匹配完整不可变绑定、摘要/hash/原期限与当前ACTIVE，要求尚未到期且当前attempts<5；删除本challenge与匹配的ACTIVE指针，最多一次成功。不把读取时的attempts精确值作为CAS条件：读取后新增但尚不足5次错误，不应误拒正确码；第5次错误先删除则正确消费失败。

若读到旧申请后新码先激活，旧计错/消费返回统一失效，不修改新码。脚本在任何写入前完成相关状态/类型检查，不依赖Lua运行异常回滚此前写入。确认流程不调用生产者finish/cancel，不管理任务队列，不恢复ACTIVE。

## 6. 业务顺序、新密码及快照

1. 普通安全链处理；公开请求无登录门禁。严格解析输入，扣IP额度，读取完整ACTIVE，扣绑定账户额度；账户额度拒绝走统一400及一次伪比较，不读密码、不计验证码错误、不消费。
2. 短只读事务读取不含密码的账户绑定快照，确认账户存在，原始/规范化邮箱、账户角色和认证版本与申请一致。不以JWT主体代替申请账户，不因新Token或再次验证刷新申请版本。
3. 事务外BCrypt匹配code与申请codeHash。不匹配时原子计错并返回统一400；此时不读取密码来对外检查新旧密码是否等价。
4. 验证码正确后，短只读事务重新读取带当前密码摘要的最小投影并再次复核完整绑定，保存原始邮箱、角色、版本及密码摘要作为唯一prepared预期。缺行或合法绑定变化均统一400；字段/摘要损坏或查询/读事务启动完成失败为503。
5. 事务外用PasswordHasher.matches(newPassword, currentHash)判断等价，不比较随机盐摘要。等价返回400 NEW_PASSWORD_SAME_AS_OLD；码不消费、不计错、不续期，但IP/账户额度已消耗，用户可换新密码重试。版本已达Long.MAX_VALUE时503，消费前拒绝，不溢出。
6. 事务外生成新密码BCrypt摘要；在原expiresAt之前原子消费ACTIVE。消费前已过期或被替换则统一400，不进入写事务；消费结果未知503，不进入写事务。
7. 仅明确消费成功后进入短写事务，使用步骤4保存的prepared及步骤6的新摘要做一次条件更新。消费时仍有效即可，后续提交允许跨过原deadline，不重新检查期限并回滚已获准更新。

BCrypt、Redis、限流、SMTP及Token签发都不进入MySQL写事务。新摘要计算失败不提前消费验证码；不得为节省一次读取而在未正确验证验证码时返回新旧密码等价信息。

## 7. MySQL条件事务更新

新增专用最小投影及条件更新，不扩改既有changePasswordIfSnapshotMatches方法的调用契约。WHERE按申请绑定用户ID定位，匹配prepared原始邮箱（NULL安全、二进制精确）、账户角色（二进制）、原密码摘要（二进制）、原token_version，并有version<Long.MAX_VALUE守卫。绑定邮箱通常非空，SQL依旧使用明确NULL安全比较以避免隐式排序规则或NULL判断漂移。

仅SET password_token=:newHash、token_version=token_version+1、updated_at=UTC_TIMESTAMP(6)，三者在同一事务提交。不save整Entity、不覆盖用户名、邮箱、角色、created_at、last_login_at。不以规范化邮箱替代rawEmail作为SQL预期，不重新读取新快照后重试更新。

影响1行且明确提交才204。0行统一400 PASSWORD_RESET_VERIFICATION_FAILED，不向公开客户端区分删除、版本变化、角色/邮箱/密码并发变化；不返回401/404/409，不为错误分类再产生重试。异常行数为安全500并回滚。数据库/事务开始、执行或提交故障为503，无自动SQL重试。

已消费后任何结果都不恢复码、不退额度。真实回滚时用户行不变但码已消费；提交已执行但确认丢失时503，密码和旧登录撤销可能已生效，客户端应尝试新密码登录核实。重试旧申请不重放204，不通过自动补偿、恢复码或结果查询接口消除未知边界。

成功后目标账户当前及其他设备/登录角色的全部旧Token在后续认证版本校验中失效；旧角色选择Ticket或已验证旧版本身份不得升级为新版本凭据，沿用既有认证版本绑定保障。另一账户主动附带的有效Token不因此被撤销。本保证不承诺中断已经通过认证的在途业务请求或媒体传输。

## 8. 并发及重发顺序

- 新申请仅入队/准备，旧ACTIVE仍可兑换；不检查latest pending就提前拒绝旧码。
- 新码先激活：旧确认消费失败400，不执行密码更新。
- 旧码先消费：允许完成原密码更新，即使期间新码激活。旧确认不删除新ACTIVE/pending/latest/队列；密码更新成功递增版本，新激活但绑定旧版本的申请随后不能兑换。
- 多个正确提交最多一次消费、一次密码更新和版本加1。更晚的公开提交统一400；若主动附带已撤销Token，认证链可先401，这是既有安全语义。
- 并发错误未达5次时正确消费仍可成功；第5错先删则正确提交失败，正确消费先删则迟到错码不能影响新申请。
- 消费前或消费后的改密、换邮箱、角色/版本/密码摘要变化和删除，由绑定复核/最终CAS阻止覆盖。并发仅改用户名不阻止重置、不覆盖新用户名。
- 原始响应丢失后重复提交不保证成功；首次已提交时旧申请已消费，返回400不能证明首次未改密码。

## 9. 错误、安全与观测

| HTTP | code | 场景 |
| --- | --- | --- |
| 204 | 无 | 明确提交成功，无正文及新Token |
| 400 | VALIDATION_FAILED | 请求字段、JSON、编码或16 KiB预算非法 |
| 400 | PASSWORD_RESET_VERIFICATION_FAILED | 不存在/未激活/无账户/过期/替换/消费/五错失效、账户提交额度拒绝、验证码错误、绑定变化或最终CAS0行 |
| 400 | NEW_PASSWORD_SAME_AS_OLD | 验证码正确，新密码与当前密码等价，消费前拒绝 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | PASSWORD_RESET_CONFIRMATION_RATE_LIMITED | 仅IP层配额拒绝，带IP窗口Retry-After；账户层拒绝为统一400 |
| 503 | PASSWORD_RESET_CONFIRMATION_UNAVAILABLE | 限流/Redis/DB故障、损坏、摘要异常、版本上限、结果未知 |
| 500 | INTERNAL_ERROR | 未预期异常或不可能的更新行数 |
| 401/403 | 沿用现有认证授权错误码 | 主动附带无效Bearer等安全链拒绝，正常无Token调用不要求认证、无新增角色禁止分支 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 主动附带Token时的认证状态查询失败 |

业务错误采用RFC9457 ProblemDetail、application/problem+json、稳定code及现有`urn:problem:music:`加小写下划线code格式。验证码及账户额度拒绝统一文案“验证码或找回申请无效，请检查或重新申请”，不返回账户、邮箱、剩余次数、过期/替换等具体状态；存储损坏不伪装成普通400。JWT签名/过期沿用WWW-Authenticate及可能无ProblemDetail的既有响应。

成功及错误响应均禁止缓存，不回显密码、验证码、完整challengeId、摘要、内部版本或Redis键。日志使用SLF4J/traceId和脱敏异常链，DTO、快照与状态toString全部脱敏，不记录请求体或邮件内容；不扩大Actuator。沿用现有HS256、issuer/audience/token version验证和真实认证链，不新增jti撤销或刷新凭证。

## 10. 组件边界与变更范围

- 专用Controller/DTO/严格Reader：仅负责HTTP、Bean Validation等价入口校验、响应与OpenAPI。
- 确认Service：协调IP/账户额度、ACTIVE及账户绑定、验证码、新旧密码检查、新摘要、消费和事务更新。
- 确认Redis Store/独立Lua：按当前生产者schema读取、计错、消费及两层固定窗口，不改生产者状态/脚本或领取、激活规则；不直接复用只支持60秒窗口的生产者counter处理900秒额度。
- 快照Reader：最小投影、短只读事务与绑定复核，复用本任务体系已有PasswordResetEmailRules及Identifiers，不新增第二份邮箱规范化。
- 专用事务Service/Repository方法：只做prepared条件写入及事务外错误转换，0行统一分类，无重试或远程调用。
- 专用安全异常映射、API及部署文档、真实存储/认证/并发故障测试。

预计无需新表、Flyway迁移、依赖、Compose服务、后台任务或生产配置项；只追加必要安全公开路径、查询/更新及handler，不修改已执行迁移或ddl-auto=none。实现计划再固定具体文件及方法签名。旧发码12字段结构与已上线API保持兼容。

## 11. 验证与完成条件

- Reader/HTTP：三个字符串、43字符规范标识及末位、六位码/前导零、密码8/72及首尾空格/非全空格/ASCII、16384/16385字节、非法UTF-8/代理/重复转义键/未知字段/415、204空正文/no-store/无Location/Token，ProblemDetail及生成OpenAPI与契约一致。
- 真实Redis：IP10/60s、账户10/900s、跨申请重发不重置、拒绝不续窗/当前层不增/前层不退款；仅IP拒绝429及Retry-After，账户拒绝统一400且无Retry-After；格式合法不存在申请扣IP但不造用户键；损坏/无TTL/未知回复关闭放行；不影响原发码额度。
- 统一失败保护：跨多次申请累计耗尽真实账户额度，与不存在邮箱的假申请比较状态/code/type/title/detail及无Retry-After，不出现账户相关扩展字段；预验证码失效或账户拒绝仅一次同成本伪比较，真实错码不再追加；伪比较即使返回true也必须400、零计错/消费/写事务，IP拒绝零BCrypt。验证不使用sleep填时，伪摘要不落存储/日志。
- 验证码：原始deadline与PEXPIRETIME、五次错误及正确消费交错、读取后非终态attempts变化、被替换/未激活/假申请统一400、同码不同challenge隔离；等价密码不计错/不消费/不续期但扣额度。
- 真实MySQL：USER/ADMIN、原始邮箱/角色/版本/摘要匹配、仅三列更新、用户名并发不被覆盖、绑定变化/删除/CAS0行统一400、Long.MAX_VALUE不溢出。新旧密码BCrypt匹配通过真实调用验证，不用摘要字符串相等代替。
- 故障：实际Redis计数/计错/消费后丢回包，消费未知零写事务；消费后DB开始/执行/真实回滚/提交后丢确认，码不恢复/额度不退/SQL不重试；消费前过期拒绝，消费先成功提交跨窗允许；不假设503一定未更新。
- 真实生产者联动：原异步申请入队、准备、激活与确认竞态；新激活先于旧消费、旧消费先于新激活、旧错码迟到、旧确认不清新pending/ACTIVE/latest/队列；使用闩锁及可控调度，不用sleep猜顺序。
- 真实安全链：无Token可重置，主动无效/过期/撤销Token被拒绝；其他账户有效Token不改变申请目标/额度归属，不因重置目标账户而撤销非目标账户。成功后目标全部旧Token拒绝、新密码用户名/邮箱登录有效、旧密码不再登录；管理员仍按既有角色选择流程。
- 回归原发码、普通改密/改邮箱及登录版本绑定；MySQL8.4/Redis用真实Testcontainers，仅SMTP可控替换，禁止H2或mock持久化成功。新旧请求重复、响应丢失与角色选择Ticket不升级旧版本身份均验证。
- API文档含方法/完整路径/公开权限/字段规则/JSON/204解释/完整错误表及可执行占位curl；部署说明记录单Redis前提、时钟、未知结果不恢复及新密码登录核实流程，不提供生产删键放行命令。
- Windows最终`$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`，记录真实退出码、类数、通过/失败/错误/跳过；必要MySQL/Redis/MinIO零跳过，不将历史1718冒充新结果。按AGENTS准备含untracked完整材料，交独立实现审核，未accepted不启动下一接口。

## 12. curl示例

调用者安全准备PASSWORD_RESET_BODY_FILE，含三个字段；示例不在命令行放真实密码、验证码或标识，文件不入Git，生产使用HTTPS：

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-resets' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_BODY_FILE}"
```

## 13. 自查与下一步

已核对唯一接口、既有申请schema兼容、公开身份与JWT主体分离、严格三字段、两层顺序额度/失败不退、原期限与五错、等价密码消费前拒绝、完整归属消费、原始prepared及一次CAS、版本溢出、消费后不恢复、提交未知、重发两种先后及旧操作不动新状态、全部旧登录撤销、不自动登录、无迁移/依赖/其他端点及真实容器验证。

自查发现的账户额度429枚举线索已按用户选择A修正：仅IP拒绝429、账户拒绝统一400且无等待字段；额外明确同成本真实/伪比较，避免明显BCrypt时间分支。各章节、错误表和测试要求一致，不改变两层额度数值或前置发码schema。文档无实现占位或未决业务分支；本轮未修改业务代码、运行或声称新功能测试通过。设计审核已通过，下一步请用户审阅实施计划，确认后才实施；设计accepted不替代独立实现审核。


## 14. 第1/3轮设计审核反馈复核与处置

设计审核accepted，零阻断项；审核执行的check为UP-TO-DATE，1718份测试结果来自既有代码全量，不是本接口新实现结果。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断 | 成立；既有发码handler使用PASSWORD_RESET_REQUEST_RATE_LIMITED/UNAVAILABLE，原设计确认code仅少REQUEST一词，易混淆 | 认可，仅命名易用性，不影响原设计正确性 | 采纳CONFIRMATION前缀，与EMAIL_CHANGE_CONFIRMATION并行；尚无已发布确认端点客户端，不改变已上线发码code | 本文及计划统一为PASSWORD_RESET_CONFIRMATION_RATE_LIMITED/UNAVAILABLE，API要求列两个端点code归属对照 | 当前仅核对既有源码与文档；实现时以HTTP/OpenAPI测试逐行锁定，未声称已跑新测试 |
| NOTE-002 非阻断 | 成立；公开发码返回challengeId，持有有效标识者可通过错码提交累计账户900秒10次额度；超限统一400且失败不退，对合法用户构成有界降级 | 认可，是已选额度的残余可用性风险，不是认证绕过 | 采纳运维风险记录，不调整额度、错误映射或增加恢复端点 | 计划要求部署说明记录受发码冷却/日额度约束、按原窗口自然恢复；不提供清额放行 | 实现测试验证耗尽后统一400、不续窗、窗口恢复；不以“最长900秒”声称反复攻击永远不能再次触发 |
