# 找回密码三步式重构之独立验证码验证接口设计

日期为2026-11-08。代码基线为main @ da146b2，本地与origin/main同步，开始设计时工作区干净。基线密码重置接口已获第1/3轮独立实现审核accepted，独立全量158类1825通过、0失败、0错误、0跳过，MySQL、Redis、MinIO均实跑；该数字属于既有实现。

用户已逐段确认三步流程、直接切换、凭据方式与期限、替换策略、失败策略、方法路径、额度、错误契约及分阶段交付。本文件整理第二步的可实施设计，并固定后续第三步的衔接约束。原始1675fae已获第1/3轮书面设计审核accepted，零阻断项，计划07e19e3已由用户确认，当前源代码/测试冻结be2f091，最终定向10类101项通过，完整验证168类1926通过/0失败/0错误/0跳过。设计NOTE-001处置见§14及实现审核材料。

## 1. 目标与单接口范围

本轮唯一实现对象为`POST /api/v1/users/password-reset-verifications`。用户提交申请标识和邮箱验证码，后端验证本人邮箱归属并兑换一次性重置凭据，供第三步设置新密码。

最终流程如下。

| 步骤 | 方法与路径 | 请求字段 | 成功 |
| --- | --- | --- | --- |
| 1 申请邮件验证码 | POST /api/v1/users/password-reset-requests | email | 202，challengeId、expiresAt |
| 2 验证并兑换凭据，本轮 | POST /api/v1/users/password-reset-verifications | challengeId、code | 200，resetTicket、expiresAt |
| 3 提交新密码，后续单独任务 | POST /api/v1/users/password-resets | resetTicket、newPassword | 204，空正文 |

第二步完成代码、测试、文档、完整构建与独立审核后，再单独设计落实第三步的接口改造。前端在两项均审核通过后整体切换。阶段一交付的是第二步接口；第三步消费凭据及其新请求契约属于后续交付。

用户选择直接切换，最终第三步采用resetTicket/newPassword两字段契约。阶段一保持既有第三步可运行，以遵守一次只实现一个Method+Path的项目约束。最终部署切换需协调服务端实例版本与前端，完整切换后原challengeId/code/newPassword请求按字段校验400处理。阶段一签发的数据按本文schema固定，供后续消费者兼容读取。

## 2. 方案与已确认取舍

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| 随机不透明凭据，已选 | 验证成功以JSON返回resetTicket，Redis保存摘要和绑定，前端内存暂存 | 契合现有API调用方式，可原子替换及一次性消费，无新增数据库迁移 |
| HttpOnly Cookie | 浏览器自动携带服务端签发的资格 | 需要同时确定Cookie、跨域和CSRF规则，用户选择前一种方式 |

验证码与凭据形成两段独立300秒期限。用户选择凭据从成功兑换起计时、每账户仅最新一张、第三步消费后故障不恢复的策略。第二步与第三步各自有提交额度。用户已确认兑换结果未知的修正语义，见§8。

## 3. 认证、归属与HTTP契约

公开接口，无需旧密码、登录或近期auth_time。USER和ADMIN同规则，目标由服务端ACTIVE申请绑定确定。主动附带有效他账户Bearer也以申请绑定为目标；无效、过期或撤销Bearer按既有认证链拒绝。SecurityConfig仅增加本POST公开路径。

### 请求

```http
POST /api/v1/users/password-reset-verifications
Content-Type: application/json

{
  "challengeId": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "code": "012345"
}
```

| 字段 | 类型与规则 |
| --- | --- |
| challengeId | string，必填，43字符规范Base64URL无填充，解码32字节且重编码相同，原值保留 |
| code | string，必填，恰六位ASCII数字，前导零保留，原值保留 |

严格UTF-8 application/json，charset省略或UTF-8。正文最多16384字节，包含尾随空白，有界读取16385字节检测超限。两个字段各一次，顺序不限。缺体、缺字段、null、非字符串、非对象、嵌套、未知字段、重复或转义重复字段、多根、尾随非空白、非法UTF-8及孤立代理均400 VALIDATION_FAILED。newPassword、email、userId、role等属于未知字段。缺失/非法媒体类型或其他charset为415。

### 成功响应

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{
  "resetTicket": "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
  "expiresAt": "2026-11-08T12:05:00Z"
}
```

示例标识仅为格式示意。实际resetTicket独立安全随机生成，前端从响应取得，暂存在内存，第三步自动提交。响应恰两个字段，expiresAt为UTC ISO-8601 Instant，由Redis兑换时间加300秒确定。成功只授予重置密码用途资格；密码更新及旧登录撤销由第三步完成。

resetTicket仅在明确兑换成功响应中返回，属于敏感凭据。请求、响应DTO及内部快照toString脱敏，成功与错误响应均禁止缓存。错误正文及日志排除验证码、完整标识、凭据、摘要、内部版本、Redis键和邮件内容。

## 4. 额度衔接与统一失败

| 维度 | 第二步额度 |
| --- | --- |
| TCP客户端IP摘要 | 固定60秒10次 |
| 申请绑定账户ID | 固定900秒10次，跨申请、设备及重发累计 |

沿用ClientIpResolver的remoteAddr。格式通过后先扣IP额度，再读取完整ACTIVE，取得可信账户后扣账户额度，之后才查询DB和比较验证码。不存在/未激活/假申请扣IP但不创建用户额度键。前层已准入额度及后续失败不退，拒绝当前层不增计数或续窗。

为继承既有码校验预算，第二步继续使用现有内部额度键`music:auth:password-reset-confirmation:ip:<digest>`及`...:user:<id>`，HASH仍为count/resetAtMs，原窗口及PEXPIRETIME保留。阶段一旧组合提交入口与新第二步共同扣这组码校验额度，避免双入口增加猜码预算。该内部命名作为兼容技术取值，不决定HTTP错误码。后续第三步使用独立`music:auth:password-reset-completion:`额度前缀，各自同为10/60秒和10/900秒；第二步签发只扣本步额度。第三步额度同样按账户ID累计，新票签发、重发或重新登录均不清零。

仅IP拒绝429 PASSWORD_RESET_VERIFICATION_RATE_LIMITED，Retry-After为Redis原窗口剩余毫秒向上取整秒数，至少1。账户拒绝统一400 PASSWORD_RESET_VERIFICATION_FAILED，同title/detail及字段，不附Retry-After、scope、账户ID、拒绝层或剩余次数。损坏键、无TTL或未知回复503，关闭放行。

复用现有PasswordResetCodeVerifier的同源成本伪摘要与比较规则。获IP准入、尚未真实比较验证码即决定普通400的路径，仅做一次伪比较且忽略结果；真实错码完成一次实际比较后直接计错及失败。IP拒绝和存储503不补比较。正确码证明后的绑定或兑换冲突也不另补伪比较。不使用sleep填时，不承诺完全等时。

账户额度残余降级沿用已接受取舍。持有真实申请标识者可跨申请消耗账户额度，合法用户可能在当前原900秒窗口剩余时间内得到统一400。重发不清额，按原TTL自然恢复；反复攻击可在后续窗口再次触发，受现有发码邮箱60秒冷却和每UTC日10次约束。部署记录覆盖该风险。

## 5. 业务顺序与数据库边界

1. 既有安全链处理主动Bearer，随后严格解析两个字段。
2. 扣IP额度，按challengeId的SHA-256摘要读取完整ACTIVE，正常缺失走统一失败及一次伪比较。
3. 按服务端绑定userId扣账户额度，拒绝走统一失败及一次伪比较。
4. 短只读事务读取账户ID、原始邮箱、角色和版本，与申请精确复核。缺行或绑定变化走统一失败及一次伪比较。
5. 事务外比较邮箱code与申请codeHash。错码对仍属当前申请的完整绑定原子计错并返回统一400；第1至4错原期限不变，第5错原子失效。
6. 正确码后再次以短只读事务复核同一绑定，保存既有原始值。缺行、原始邮箱值变化或可解析角色/版本变化统一400；投影或内部字段不可解析、非法ID/角色/负版本、查询或事务故障503。两次查询均采用不含密码的既有投影。
7. 读取该账户当前重置凭据及指针，完整验证参与替换的旧状态，形成交换时的预期快照。生成新的32字节安全随机resetTicket，仅将其SHA-256摘要传入Redis。
8. 以单次Lua原子兑换重新检查完整当前申请及旧凭据预期，消费验证码、签发新凭据并替换旧凭据。只有明确成功及回复结构验证通过才构造200响应。

数据库访问采用既有最小账户投影，BCrypt和Redis在只读事务外。第二步对用户表只读，认证版本与auth_time仍由既有账户变更或认证流程维护。Long.MAX_VALUE允许作为合法绑定版本保存，第三步在消费前按既有溢出防护503拒绝改密。

DB复核与Redis兑换之间存在跨存储间隙。若期间凭据变更，票仍绑定原邮箱/角色/版本，第三步复核会拒绝。新凭据采用保存的绑定，不能重新取新版本后将旧身份校验升级。若已有活动票绑定更高版本，迟到低版本兑换按失效400处理并保留较高版本票。

## 6. Redis协议与签发原子性

### 验证码兼容

沿用生产者12字段HASH `music:auth:password-reset:challenge:<digest>`及STRING `...:active:<emailDigest>`。字段为schema、digest、emailDigest、email、admittedAt、expiresAt、userId、rawEmail、role、tokenVersion、codeHash、attempts，schema=1。

原准入起300秒期限、两个键的实际PEXPIRETIME、完整不可变绑定、当前指针归属、BCrypt摘要及attempts 0至4均核验。计错复用既有行为。正确兑换比较全部不可变字段及原期限，要求当前attempts小于5，排除读取时attempts精确值，允许非终态错码后正确兑换。

### 重置凭据schema 1

| 键 | 类型与内容 |
| --- | --- |
| music:auth:password-reset-ticket:active:<userId> | STRING，当前ticket摘要 |
| music:auth:password-reset-ticket:ticket:<digest> | HASH，以下10个字段 |

| 字段 | 规则 |
| --- | --- |
| schema | 固定1 |
| digest | resetTicket的SHA-256摘要，43字符规范Base64URL |
| userId | 正整数Integer规范十进制字符串，与指针所有者一致 |
| email | 规范化邮箱，复用PasswordResetEmailRules |
| emailDigest | SHA-256规范化邮箱摘要 |
| rawEmail | 原始绑定邮箱，原值保留 |
| role | USER或ADMIN |
| tokenVersion | 0至Long.MAX_VALUE的规范十进制字符串 |
| issuedAt | Redis TIME签发的epoch毫秒规范十进制字符串 |
| expiresAt | issuedAt加300000毫秒，与两个键的PEXPIRETIME一致 |

凭据独立32字节随机生成并复用PasswordResetIdentifiers编码/摘要逻辑，原challengeId与code不能替代凭据。role、tokenVersion、原始与规范化邮箱均取自已复核申请快照。版本比较使用规范十进制字符串的长度及字典序，排除Lua浮点转换；时间为Redis可精确表示的毫秒整数。

旧票读取先以原子读获取指针和HASH，再经Java完整核验邮箱规范化、摘要关联、绑定、字段白名单/数量及期限，形成兑换预期。旧票较低版本或旧邮箱可能因正常账户变更留下，验证其自身结构并允许被新的有效证明替换。部分缺失、指针/记录所有者矛盾、额外字段、非法字段或TTL不一致为503，保留现场。

读取以该用户活动指针定位记录。无指针时返回“无当前票”，无法仅凭用户ID发现随机摘要键下的孤立记录，且不进行全局扫描；孤立记录本身没有当前指针资格，保留原TTL。有previous快照时，兑换脚本能检查该确定摘要的记录与指针，已知双键同时消失允许继续、已知一键缺失为503。上述损坏分类针对能定位到的状态，第三步仍须同时核验摘要记录和当前指针。

兑换脚本在首次写入前校验全部相关状态和类型。新候选摘要键已存在为503，保留原申请及旧票。当前申请已过期、替换或消费为已知失效400。旧票预期在读取后被其他成功签发替换时按失效400返回，保留当前票及申请。若旧票记录及指针同时自然到期或被后续第三步合法消费，允许按当前无旧票继续兑换；仅部分消失为损坏503。新候选、申请或旧票发生故障均不靠删除损坏键放行。

全部检查通过后，单次脚本完成以下状态转换。

- 按Redis TIME写入新票10字段及该用户active指针，两键设置同一PEXPIREAT，等于issuedAt加300000毫秒。
- 删除被当前用户指针及预期快照确认的旧票，保留每用户最新一张。
- 删除被完整归属确认的验证码challenge及其匹配ACTIVE指针。

操作通过一条Lua完成，避免先消费验证码再单独签发票形成中间失败窗口。脚本正常执行与成功回复时上述状态共同生效；命令错误或回复未知统一503，所有参与状态按严格校验关闭放行，服务端不自动重试或恢复。Lua运行错误不提供数据库式回滚，不能依赖异常撤销已经发生的写入。

兑换只处理当前凭据和对应验证码归属。pending、latest、ready/outstanding、其他用户或用途凭据及额度由各自既有协议管理。健康状态按TTL有界清理，单纯申请、入队或发送新邮件保留已签发重置凭据。

## 7. 并发与替换语义

| 交错 | 结果 |
| --- | --- |
| 同一正确码并发兑换 | 最多一次成功，后续已知消费返回400 |
| 读取attempts后新增非终态错码 | 正确兑换可成功 |
| 第5错先删除 | 正确兑换失败400，无新票 |
| 正确兑换先完成，旧错码迟到 | 旧计错不触及重置票或新验证码 |
| 新验证码仅pending | 旧ACTIVE可兑换，已签发重置票按自身期限有效 |
| 新验证码先激活 | 旧验证码兑换失败，已签发票仍有效 |
| 新票成功签发 | 该账户旧票失效，其他账户票保留 |
| 更高版本活动票已经存在 | 迟到低版本兑换失败400，不能覆盖它 |
| 兑换前绑定复核发现账户变化/删除 | 400，保留原票及未消费验证码，额度已扣 |
| DB最终复核后账户变更 | 新票保留旧绑定，第三步复核拒绝 |

后续第三步须与本协议串行化消费。新票签发先发生时，旧票消费失败；旧票消费先发生时，已获准的原SQL可以完成，新签发但绑定旧版本的票随版本递增失效。若旧票消费后、SQL提交前签发另一票，原SQL完成的资格保留，该票仍由绑定版本防护。前述跨存储间隙不构成原子DB/Redis事务。

## 8. 失败、重复请求与响应丢失

第二步仅在明确兑换成功时200。成功后原邮箱验证码已消费，再次提交为400；重复请求不重放原票、不续期或自动签新票。

Redis准入、计错或兑换执行后丢回复为503，额度或状态可能已经推进。尤其兑换结果未知时，若脚本未执行，原验证码可能仍可成功验证；若已执行，旧码失效、新票可能已签发且旧票可能已被替换。两种状态都可能发生，不能将重复提交400解释为首次未成功，也不能承诺未知后每次重试必定400。

恢复采用重新申请验证码，遵守原冷却、日额度及确认额度。服务端不执行自动兑换重试、票恢复、额度退款、明文票持久化或成功响应重放。成功响应被客户端丢弃亦采用该恢复路径。

第三步沿用已确认策略。参数不合法、同密码、新摘要计算失败在消费前保留票及原期限；真实存储损坏503关闭放行；明确消费后DB开始、执行、回滚或提交确认故障均不恢复票。提交未知可能已改密，客户端走新密码正常登录核实。原期限内消费成功，SQL提交允许跨期限。

## 9. 错误与观测

| HTTP | 第二步code | 场景 |
| --- | --- | --- |
| 200 | 无 | 恰resetTicket、expiresAt |
| 400 | VALIDATION_FAILED | 字段、编码、JSON、预算不符合规则 |
| 400 | PASSWORD_RESET_VERIFICATION_FAILED | 缺失/未激活/假申请/过期/替换/消费/五错、错码、账户额度拒绝、绑定变化或已知交换冲突 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | PASSWORD_RESET_VERIFICATION_RATE_LIMITED | 仅IP额度，带Retry-After |
| 503 | PASSWORD_RESET_VERIFICATION_UNAVAILABLE | DB/Redis故障、状态损坏、候选碰撞或结果未知 |
| 500 | INTERNAL_ERROR | 未预期异常 |
| 401/403 | 既有安全链错误码 | 主动Bearer拒绝，无新增角色禁限 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 主动Bearer的认证状态查询失败 |

复用统一验证失败title“密码重置未完成”、detail“验证码或找回申请无效，请检查或重新申请”，ProblemDetail媒体类型application/problem+json，type为urn:problem:music:加小写下划线code。DB绑定账户已不存在属于400，ACTIVE或重置票缺必需账户字段属于结构损坏503。签名/到期错误沿用既有WWW-Authenticate及可能空正文语义。

复用现有共享Store、Verifier、SnapshotReader时，其内部PASSWORD_RESET_CONFIRMATION_*异常须在新第二步边界转换为PASSWORD_RESET_VERIFICATION_*，保留原有调用方映射。新公开接口使用新code前缀，普通400继续复用现有统一code。响应和日志排除内部拒绝层、账户标识与秘密，SLF4J保留traceId，异常链用PasswordResetDiagnostics脱敏。

后续第三步继续使用PASSWORD_RESET_CONFIRMATION_RATE_LIMITED/UNAVAILABLE和普通PASSWORD_RESET_VERIFICATION_FAILED，NEW_PASSWORD_SAME_AS_OLD按既有契约处理。发码仍使用PASSWORD_RESET_REQUEST_*。OpenAPI/API及部署文档按设计NOTE-001列“接口、Redis额度键前缀、错误码”三方对照，区分当前阶段和最终阶段，并记录旧组合入口与第二步共享原额度窗口。

## 10. 组件与实施边界

| 职责 | 本轮设计 |
| --- | --- |
| HTTP入口 | 新PasswordResetVerificationController、RequestReader、Request/Response DTO，严格两字段与200契约 |
| 业务协调 | 新PasswordResetVerificationService，组织原配额/ACTIVE/绑定/BCrypt及新票兑换，安全转换共享组件异常 |
| 账户与验证码复用 | PasswordResetConfirmationStore的admitIp/admitAccount/read/recordFailure、PasswordResetCodeVerifier、PasswordResetConfirmationSnapshotReader.isCurrent、现有账户最小投影及规则 |
| 重置凭据 | 新PasswordResetTicketStore及所需内部记录/规则，原子读取旧票与原子兑换；仅实现当前签发和替换所需职责 |
| Lua复用 | 新重置票脚本复用现有confirmation common只读原语，旧mutation的参数契约保留；新兑换自行校验其新增参数和键，原代码调用方不受影响 |
| 安全及错误 | SecurityConfig追加本POST，GlobalExceptionHandler追加本步限流/不可用映射，可复用现有通用请求校验与媒体类型异常 |
| 文档 | 新docs/api/user/verify-password-reset.md、对应部署说明及审核材料，实施计划固定精确文件清单 |

PasswordResetIdentifiers、EmailRules、AccountSnapshot、ChallengeSnapshot、Diagnostics、PasswordHasher等已有表示与算法优先复用。旧登录角色选择票具有不同用途与状态协议，重置票采用自己的完整归属/损坏校验协议。

本轮用户表只读。预计无数据库迁移、新依赖、生产配置项、Compose服务或后台任务。保留生产者12字段schema与队列协议，旧组合提交入口的当前契约在阶段一用于既有回归。后续第三步再独立完成严格resetTicket/newPassword读取、票读取与消费Lua、独立额度及既有prepared/MySQL CAS衔接。

## 11. 验收与审核

- HTTP覆盖两个字符串、规范标识末位/重编码、前导零、未知newPassword/userId、重复/转义重复、缺失/null/类型/嵌套/多根、16384/16385预算、UTF-8/孤立代理及415。200恰两字段、TTL来自Redis，no-store，OpenAPI字段和各响应一致，秘密toString/异常链脱敏。
- 真实Redis覆盖旧12字段生产者兼容、共享窗口跨旧入口/新第二步累计、当前层拒绝不增不续、前层不退、假申请不造账户键、账户拒绝统一400/无等待字段、IP拒绝429/真实Retry-After、原TTL自然恢复及损坏关闭放行。
- 比较矩阵覆盖普通预验证码失败一次伪比较、真实错码一次真比较、伪比较true仍拒绝、IP拒绝/503零额外比较、正确码后绑定/交换冲突不补比较。
- 票覆盖32字节随机及仅摘要存储、10字段/角色/Long.MAX_VALUE/规范邮箱关系、每用户最新一张、两个PEXPIRETIME一致、候选碰撞不覆盖、旧票结构损坏503且验证码保留、迟到低版本保护、重复不重放/不续期、到原期限自然过期。
- 并发覆盖两实例同码至少两路实际兑换仅一次成功、非终态错码后兑换、第五错与兑换双向交错、新激活先后、旧错迟到、旧票预期变化、旧票自然到期后可新签发、他用户状态隔离。采用闩锁或可控实际操作顺序。
- 故障覆盖实际准入/计错/兑换后丢回复、脚本未执行与已执行两类未知结果、候选/旧票损坏预检、真实DB只读事务启动/查询/完成失败、最终复核后邮箱/角色/版本变化，以及成功200丢失后的重试和恢复。原子兑换成功共同状态验证与故障关闭放行分别举证。
- 真实MySQL/Redis/安全链验证USER/ADMIN、无Token、他账户有效Token、无效/过期/撤销Bearer；成功前后用户行逐列不变、token_version不递增，既有Token仍能按原版本访问，重置票与登录/角色票用途隔离。
- 密码更新、第三步真实回滚/提交丢确认、票消费未知零写事务、提交跨期限、同密码保留票、旧Token/角色Ticket失效的端到端验收归入后续第三步接口。该轮必须兼容本轮已签发schema 1，不以新增第二步测试冒充完成整条三步链路。
- 每个接口代码冻结后执行Windows命令`$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`，记录实际源代码版本、退出码、BUILD状态、JUnit XML类数及通过/失败/错误/跳过，必要MySQL/Redis/MinIO零跳过。设计阶段既有1825仅为基线。
- 按AGENTS准备唯一Method+Path、权限、声称行为、完整文件含untracked、迁移/API/基线、实际命令和数量、curl、默认项、风险及反馈复核表，交Claude独立只读审核。保持同任务轮次，accepted后推进下一接口，escalated按规则等待人类决策。

## 12. 第三步衔接与技术取值自查

第三步接收两个字符串resetTicket/newPassword，后端以票摘要定位并核对用户当前票指针和完整绑定，重新读取prepared原始邮箱、角色、密码摘要、版本。新密码继续采用8至72位可打印ASCII、非全空格、首尾空格保留规则。有效票才能进入同密码判断，完成新摘要计算后明确原子消费，之后执行原始prepared的一次MySQL CAS。只SET password_token、token_version+1及UTC updated_at，0行统一400，不回读重试。成功204无Token，前端清除旧登录并按原角色流程登录。

本轮技术取值补全如下，作为书面审阅内容。旧码校验额度沿用现有内部键以保留在途窗口，最终提交使用独立前缀。旧票先完整读取校验，再由兑换脚本匹配预期，防止覆盖损坏或并发新票；两键同时过期/合法消费允许按无旧票继续。存在更高版本票时，低版本迟到证明关闭兑换，避免使有效新票失效。所有期限由Redis TIME确定，无续期、后台扫描或人工删键恢复机制。

已核对唯一当前接口、阶段一/最终边界、三步方法与字段、公开归属、两段300秒、每用户单票、跨入口额度衔接、真实/伪比较、完整归属兑换、旧票完整校验及低版本保护、故障与响应丢失语义、第三步数据兼容、数据库CAS及撤销登录衔接。书面设计已获独立审核accepted，用户确认计划后已实施本接口，源代码/测试冻结be2f091，最终定向10类101通过/0失败/0错误/0跳过，完整验证168类1926通过/0失败/0错误/0跳过。独立实现审核待开展。

## 13. curl示例

调用者安全准备PASSWORD_RESET_VERIFICATION_BODY_FILE，文件包含本接口challengeId/code两个字段，文件不入Git，生产使用HTTPS。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-reset-verifications' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_VERIFICATION_BODY_FILE}"
```


## 14. 第1/3轮书面设计审核反馈复核与处置

审核对象为main @ 1675fae，结论accepted，零阻断项，剩余争议为无。用户提供的报告附件为8db407ce-996f-461f-a789-22db60344814/已粘贴的文本.txt。审核执行.\gradlew.bat check --console=plain，退出0、BUILD SUCCESSFUL、12秒，5个任务均UP-TO-DATE；158类1825通过/0失败/0错误/0跳过复用原5f45ad6独立全量结果，本轮无新功能测试实跑。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| 本任务设计NOTE-001 非阻断 | 成立，PasswordResetConfirmationStore前缀为confirmation，第二步HTTP错误使用VERIFICATION，后续第三步将用completion键及CONFIRMATION错误，按码名直接定位键可能混淆 | 认可，运维/开发可读性建议，行为与安全已通过设计审核 | 采纳三方对照及共享事实记录方向，原额度键保留符合在途窗口兼容约束 | 本文§9及计划任务5/7要求API和部署说明并列接口、Redis键前缀、错误码，分当前/最终阶段，记录两入口共享原预算 | API/部署三方表与真实混合入口额度测试已落实，最终定向101通过/0失败/0错误/0跳过；完整验证1926通过/0失败/0错误/0跳过，结果见实现材料，处置待独立审核核实，不作为新增通过条件 |

跨任务意见如实登记。上一password-resets实现审核NOTE-001（“无账户”文案）已由本次独立设计审核核实da146b2澄清并关闭，当前任务继续采用DB账户缺失400、可定位Redis结构损坏503的口径。

计划自查补充了无指针孤立记录的可定位边界，作为实现技术澄清交用户随计划审阅。该记录本身不能授予资格，已持有旧快照时部分缺失仍按503处理，未增加扫描、清额或删除损坏状态后放行机制。
