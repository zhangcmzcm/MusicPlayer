# 找回密码三步式重构之重置密码提交接口设计

日期为2026-11-09。设计基线为main @ feb13c9，本地与origin/main同步，开始设计时工作区干净。

本文件仅设计POST /api/v1/users/password-resets的第三步改造。用户已逐节确认请求响应、处理顺序、并发与故障、额度与错误、组件与验证范围。书面设计尚待用户及独立审核确认，代码尚未实施。

## 1. 目标与现状

三步式流程的前两步已经实现，第二步已获第1/3轮独立实现审核accepted并推送。第二步独立全量验证168类1926通过、0失败、0错误、0跳过，该结果属于既有代码。

| 步骤 | 方法与路径 | 请求 | 成功 |
| --- | --- | --- | --- |
| 1 申请验证码 | POST /api/v1/users/password-reset-requests | email | 202，challengeId、expiresAt |
| 2 校验验证码 | POST /api/v1/users/password-reset-verifications | challengeId、code | 200，resetTicket、expiresAt |
| 3 提交新密码，本轮 | POST /api/v1/users/password-resets | resetTicket、newPassword | 204，空正文 |

现有第三步仍接收challengeId/code/newPassword。本轮直接切换为resetTicket/newPassword，兼容第二步现有schema 1票据。完整验收需经过真实申请、邮件投递、验证码验证、票据消费及新密码登录。

## 2. 方案选择与变更范围

用户选择方案A。

| 方案 | 处理 | 取舍 |
| --- | --- | --- |
| A，采用 | 调整现有第三步解析器及业务链路，复用既有条件更新 | 变更集中于第三步，严格解析引擎复用继续排期 |
| B | 同时提取第二、三步共享严格JSON解析引擎 | 减少重复，需要额外验证第二步解析完全等价 |

沿用前轮已确认的随机不透明票、JSON传递、前端内存暂存、独立300秒、每账户最新一张、先消费再数据库条件更新策略。当前任务在原方法和路径上完成契约切换。

预计无需Flyway迁移、新依赖、生产配置、Compose服务或后台任务。保留现有第二步签发协议与生产者队列。具体变更文件清单由实施计划逐项列明。

## 3. 认证、归属与请求契约

公开接口，无需登录、旧密码或近期auth_time。USER、ADMIN同规则。目标由服务端票据绑定确定。主动携带另一账户有效Bearer不会改变目标。无效、过期、撤销Bearer沿用既有认证链拒绝，认证状态不可用沿用AUTH_STATE_STORE_UNAVAILABLE。现有公开路径配置已满足本轮需求。

请求媒体类型为application/json，charset省略或UTF-8，严格UTF-8解码。正文上限16384字节，含尾随空白，有界多读一个字节检测超限。

| 字段 | 规则 |
| --- | --- |
| resetTicket | 必填字符串，43字符规范Base64URL无填充，解码32字节且重编码相同，原值保留 |
| newPassword | 必填字符串，8至72位可打印ASCII U+0020至U+007E，非全空格，保留首尾空格 |

仅允许这两个字段，各一次，顺序不限。缺体、缺字段、null、非字符串、非对象、嵌套、未知字段、重复及转义同名字段、多根、尾随非空白、非法UTF-8、孤立代理及超预算均400 VALIDATION_FAILED。旧challengeId/code/newPassword请求、混合新旧字段同样拒绝。确认密码由前端本地核对。

沿用现有严格解析器及Bean Validation。密码原值参与比较与哈希，避免trim、规范化、截断或预哈希。resetTicket及newPassword均在OpenAPI中标记为敏感输入，DTO和内部快照toString脱敏。

示例仅展示字段，实际凭据由第二步响应取得。

```json
{
  "resetTicket": "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
  "newPassword": "new-example-password"
}
```

## 4. 成功与登录撤销

成功返回204、空正文、Cache-Control为no-store。响应不携带新Token、认证版本或票据。前端清理旧登录状态，使用新密码走原登录流程，ADMIN沿用既有角色选择流程。

数据库仅修改password_token、token_version加1及updated_at。用户名、角色、邮箱及其他用户字段由各自原有规则管理。目标账户全部旧JWT和旧角色选择票在后续认证版本校验中失效，绑定旧版本的密码重置票也失效。其他账户有效Token保持有效。撤销作用于后续认证版本校验，已通过认证的在途请求具有既有执行边界。

## 5. 独立额度与顺序准入

| 接口 | Redis额度前缀 | IP额度拒绝 | 存储故障 |
| --- | --- | --- | --- |
| password-reset-requests | music:auth:password-reset:，沿用发码各维度 | PASSWORD_RESET_REQUEST_RATE_LIMITED | PASSWORD_RESET_REQUEST_UNAVAILABLE |
| password-reset-verifications | music:auth:password-reset-confirmation: | PASSWORD_RESET_VERIFICATION_RATE_LIMITED | PASSWORD_RESET_VERIFICATION_UNAVAILABLE |
| password-resets，本轮 | music:auth:password-reset-completion: | PASSWORD_RESET_CONFIRMATION_RATE_LIMITED | PASSWORD_RESET_CONFIRMATION_UNAVAILABLE |

第三步新增额度键为ip:<客户端IP摘要>及user:<可信账户ID>，接在completion前缀后。每IP固定60秒10次，每账户固定900秒10次，HASH恰count/resetAtMs两个字段，保留首次准入确定的PEXPIRETIME。额度原语复用既有严格Lua quota逻辑，使用本轮前缀的入口校验。

格式通过后，先扣IP额度，随后读取票据，完整校验后才按绑定账户扣额，再访问DB及比较密码。客户端IP沿用ClientIpResolver的remoteAddr。缺失假票仅扣IP，不根据请求参数创建账户键。

仅IP拒绝429，Retry-After取Redis原窗口剩余毫秒向上取整秒数，至少1。账户拒绝统一400 PASSWORD_RESET_VERIFICATION_FAILED，隐藏账户、拒绝层、剩余次数和等待时间。拒绝当前层不增、不续窗，已准入的前层及后续失败保留已扣计数。重发、签新票、换设备或登录均保留原账户预算。

第三步与第二步各自独立窗口。切换时第二步原confirmation窗口继续保留，本轮completion从首次准入建立自身窗口，避免迁移或清空第二步在途预算。IP摘要复用PasswordResetIdentifiers.digest。

持有有效票据的调用方能消耗绑定账户额度，合法用户可能在原900秒窗口剩余时间内收到统一400。按原TTL自然恢复，后续窗口仍可能再遇到消耗。无清额或人工删键恢复行为。

## 6. 票据协议与完整读取

沿用第二步已签发的schema 1及现有PasswordResetTicketSnapshot、Rules和Identifiers。

| 键 | 类型 |
| --- | --- |
| music:auth:password-reset-ticket:active:<userId> | STRING，当前票摘要 |
| music:auth:password-reset-ticket:ticket:<digest> | HASH，恰10字段 |

10字段为schema、digest、userId、email、emailDigest、rawEmail、role、tokenVersion、issuedAt、expiresAt。schema固定1，digest为resetTicket的SHA-256规范Base64URL摘要。原始邮箱、规范邮箱及邮箱摘要关联沿用已审核规则，userId为正Integer，角色USER/ADMIN，版本为0至Long.MAX_VALUE规范十进制字符串。issuedAt由Redis TIME生成，expiresAt恰加300000毫秒，HASH与活动指针的PEXPIRETIME均须等于expiresAt。

应用按票摘要定位记录，由记录绑定账户定位活动指针。单次Lua原子读取并校验实际类型、字段白名单与数量、键与摘要关系、所有者、时间及TTL，Java随后核验规范编码、邮箱规范化及摘要关联，形成完整不可变快照。时间授权依据Redis TIME，精确到毫秒，now小于expiresAt有效，等于端点已到期。

读取分类如下。

| 可定位状态 | 结果 |
| --- | --- |
| 摘要记录不存在 | 普通失效400，涵盖假票、自然过期、已消费及正常被替换 |
| 记录存在且当前指针匹配，全部规则满足 | 返回完整快照 |
| 记录存在，指针指向另一张结构有效的当前票 | 旧票失效400，当前票保留 |
| 记录存在，指针缺失、类型错误或当前记录缺失 | 损坏503 |
| 记录字段、邮箱关联、版本、类型、归属或TTL损坏 | 损坏503 |

按摘要读取时，记录不存在便无法得到可信userId，无法发现未知用户指针下的孤立状态，返回普通失效且不做全局扫描。孤立记录若仍存在而指针缺失可定位为损坏503。该可定位边界与前轮设计一致。

本轮消费中的完整预期快照提供已知userId和digest，因此消费时能检查已知双键半缺失。结构损坏保留现场，健康过期状态依原TTL清理。

## 7. 业务顺序与数据库边界

1. 既有安全链处理主动Bearer，随后严格解析resetTicket/newPassword。
2. 准入第三步IP额度，计算票摘要，完整读取票据及指针。
3. 按可信userId准入第三步账户额度。拒绝使用统一验证失败。
4. 以短只读事务读取现有PasswordResetPreparedSnapshot，核对ID、原始邮箱、角色和认证版本，验证密码摘要可用。DB缺行或可解析绑定变化为400，非法内部字段、无法解析投影或DB/事务故障为503。
5. 事务外调用现有PasswordHasher比较新旧密码。相同返回400 NEW_PASSWORD_SAME_AS_OLD。检查版本上限，生成新摘要并校验BCrypt格式。摘要异常或版本Long.MAX_VALUE在消费前返回503。
6. 以完整预期票据快照调用单次Lua，再次核对记录、当前指针与原期限，明确消费成功。
7. 用第4步prepared快照及新摘要，调用既有PasswordResetConfirmationTransactionService.update，执行一次短MySQL写事务。

复用现有最小prepared投影及密码重置专用SQL。WHERE匹配ID、null-safe binary原始邮箱、binary角色、binary旧密码摘要、精确版本及版本小于Long.MAX_VALUE。SET仅密码摘要、版本加1及UTC_TIMESTAMP(6)。更新1行成功，0行统一400，其他行数500并回滚。

读取事务在密码计算与Redis消费前结束，写事务仅在明确消费后开始。CAS继续使用原prepared，避免重新读取最新版本后升级原票授权。仅用户名并发变化可成功并保留新用户名。密码摘要即使对应相同明文，字节变化也必须触发原CAS冲突保护。

第三步使用随机256位票据校验，不再比较邮箱code。本轮技术取值为缺票、绑定失效、账户限额等普通失败直接拒绝，密码比较仅在有效票据、额度和prepared绑定均通过后执行。沿用第二步真/伪验证码比较矩阵，第三步不调用验证码Verifier。该选择属于随本文审阅的技术细化。

## 8. 原子消费与并发

消费Lua输入包含精确digest、userId及schema 1全部10字段预期，脚本校验参数、键关系及数据状态后才写入。记录本身不可变，同一摘要任一字段相对已读快照变化按损坏503处理。有效记录须由相同userId当前指针授权。

明确成功仅删除本票记录及匹配当前指针，两键在同次Lua正常执行中原子消费。普通失效与损坏返回不同内部状态，应用严格校验返回类型、长度及数值。null、未知状态、超时、连接错误或脚本异常为503。

已知记录及指针均消失按已消费或过期400。预期指针仍指向该digest但记录缺失，或预期记录存在而指针缺失，为可定位半状态503。若当前指针已合法转向新票，旧票消费400并保留新票。脚本须校验可定位当前指针和记录的基本结构，损坏场景503。

| 交错 | 结果 |
| --- | --- |
| 同票并发提交 | 最多一个明确消费成功并进入写事务，后续400 |
| 新票先签发 | 旧票消费400，保留新票 |
| 旧票先消费，新票后签发 | 已获消费资格的原CAS可继续，新票若绑定旧版本则随成功改密失效 |
| 消费前账户绑定变化 | prepared复核或最终CAS拒绝400 |
| 消费后账户并发变化 | 最终CAS拒绝400，票已消费 |
| 到期前消费，到期后提交 | 允许按原prepared完成 |
| 消费执行时等于或晚于expiresAt | 拒绝400，零写事务 |

第二步兑换与第三步消费通过同Redis脚本执行串行化。保持第二步对已知旧票双键同时消失时可按无旧票签发的规则。Redis与MySQL之间存在跨存储间隙，由账户绑定和数据库CAS约束最终更新资格。

## 9. 故障、重试与响应丢失

| 阶段与结果 | 对外及后续状态 |
| --- | --- |
| 字段或密码格式不合法 | 400 VALIDATION_FAILED，票据保留原期限 |
| 有效绑定后发现同密码 | 400 NEW_PASSWORD_SAME_AS_OLD，票据保留原期限，已准入额度保留 |
| 消费前新摘要计算故障、非法密码摘要或版本上限 | 503，票据保留原期限 |
| 读取、额度或只读事务故障 | 503，状态按实际已执行操作保留 |
| 消费未知 | 503，本次零写事务/更新SQL，票可能已消费 |
| 明确消费后DB事务开始、执行或真实回滚故障 | 503，票保持已消费，数据库按实际事务结果保留 |
| MySQL实际提交后确认丢失 | 503，可能已改密及撤销旧登录 |
| 未预期异常或不可能更新行数 | 500，按事务边界回滚，已消费票保持消费 |

结果未知涵盖操作未执行与已执行两种状态。Redis消费未执行时票可能仍可使用，实际消费后票已失效。消费未知的本次请求结束，不进入密码更新。数据库实际提交后确认丢失时，通过新密码正常登录核实，必要时重新申请找回密码。

沿用已批准的服务端策略，消费及SQL自动重试、票恢复、额度退款、成功响应重放均关闭。成功204被客户端丢弃后重试为统一400。重复400无法证明首次未提交。前端不得根据503或重试400直接判断密码更新结果。

Lua正常执行提供单次原子状态转换，运行时错误没有数据库式回滚承诺。任意未知回复按503关闭授权，仅明确成功允许后续SQL。Redis持久化和故障转移沿用既有部署前提，避免回放已消费票。

## 10. 错误、缓存与观测

| HTTP | code | 场景 |
| --- | --- | --- |
| 204 | 无 | 密码更新及版本递增明确成功 |
| 400 | VALIDATION_FAILED | 请求字段、编码、JSON、密码规则或预算不合法 |
| 400 | PASSWORD_RESET_VERIFICATION_FAILED | 票缺失/到期/替换/消费、账户额度拒绝、绑定变化或CAS零行 |
| 400 | NEW_PASSWORD_SAME_AS_OLD | 有效绑定后新旧密码相同，尚未消费 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 需要UTF-8 application/json |
| 429 | PASSWORD_RESET_CONFIRMATION_RATE_LIMITED | 仅IP额度，带Retry-After |
| 503 | PASSWORD_RESET_CONFIRMATION_UNAVAILABLE | Redis/DB/事务/摘要故障、损坏、版本上限、结果未知 |
| 500 | INTERNAL_ERROR | 未预期异常或不可能更新行数 |
| 401/403 | 既有安全链错误码 | 主动Bearer拒绝等现有行为 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 主动Bearer认证状态查询失败 |

业务错误沿用ProblemDetail、application/problem+json、稳定code及urn:problem:music:加小写下划线code。普通失败继续复用现有title“密码重置未完成”及detail“验证码或找回申请无效，请检查或重新申请”，作为跨第二、三步的既有公共契约保留，前端按code处理。

Controller在解析前设置no-store，成功及进入Controller后的错误均保持该头。现有认证链拒绝沿用其缓存与WWW-Authenticate行为。日志保留traceId，经PasswordResetDiagnostics脱敏，排除密码、凭据、摘要、Redis键、内部版本、邮件正文及底层敏感异常。

PasswordResetTicketStore/Rules现有读取与兑换使用VERIFICATION不可用异常。本轮复用时在第三步边界安全转换成CONFIRMATION错误族，保持第二步调用方行为。第三步新限流组件使用CONFIRMATION异常。保持现有全局映射及Actuator暴露范围。

## 11. 组件与文件范围

| 组件 | 改造职责 |
| --- | --- |
| PasswordResetConfirmationController、Request DTO、RequestReader | 两字段严格契约、204/no-store及OpenAPI |
| PasswordResetConfirmationService | 票据、独立限流、prepared、密码准备、消费、CAS及异常转换 |
| PasswordResetTicketStore及本轮Lua | 按摘要读取及完整预期原子消费，复用既有票据规则 |
| 新第三步额度存储组件及Lua入口 | completion命名空间IP/账户窗口，复用既有quota原语 |
| PasswordResetConfirmationSnapshotReader及必要规则 | 增加或替换第三步prepared入口，使其接受票据绑定，保持第二步isCurrent(challenge)行为 |
| PasswordResetConfirmationTransactionService、UserRepository | 复用既有prepared投影和专用CAS，预计无需修改SQL |
| docs/api/user/confirm-password-reset.md | 两字段、完整三步调用、限流、错误、故障及curl |
| docs/deployment/password-reset-confirmations.md及必要流程对照 | 最终命名空间映射、客户端/实例切换和恢复边界 |
| 对应测试及审核材料 | 第三步迁移、三步闭环、并发、故障与反馈记录 |

生产者协议、第二步方法/路径/字段/签发协议及原额度窗口保持兼容。旧第三步仅使用的消费方法和脚本如在切换后确无生产调用方，可移除其死代码及对应过时断言，保留第二步仍在使用的read、recordFailure及共享Lua原语。实施计划需先核对所有调用方并列明删除范围。

本轮同时评估了前轮实现NOTE-001，采用修改现有Confirmation Reader的方案。共享解析引擎继续排期，不以满足维护建议扩大当前接口范围，未自行标记该意见已获审核关闭。

## 12. 测试与验收

- HTTP验证两字段、规范Base64URL末位/重编码、缺失/null/非字符串、重复和转义重复、未知/混合/旧字段、嵌套、多根、16384/16385字节、UTF-8与孤立代理、415。密码长度7/8/72/73、全空格、首尾空格、可打印ASCII边界及非ASCII分别覆盖。
- 真实Redis验证第二步现有schema 1兼容、完整字段及邮箱关联、Long.MAX_VALUE字符串、指针归属、无TTL、错TTL、错误类型、额外/缺失字段、当前票替换、假票、自然过期、半状态及孤立边界。消费前预期快照同摘要篡改503，保持现场。
- 第三步独立IP/账户固定窗口、第二步原窗口保持、原Retry-After、拒绝不增/续窗、前层不退、假票不造账户键、同账户跨新票/设备累计。额度实际执行后丢回复验证计数保留。
- 新旧密码相同时原票及期限保持，后续使用不同新密码可在剩余期限内成功。摘要计算故障、非法旧/新摘要、版本上限均验证零消费/零写入。
- 并发以闩锁或可控真实操作顺序覆盖同票仅一次消费、签发与消费双向先后、记录读取后到期、账户邮箱/角色/摘要/版本/删除变化、用户名并发保留，以及消费后提交跨期限。
- 故障覆盖Lua执行前/执行后丢回复，明确未知时零写事务/SQL；真实只读事务开始/查询/提交、写事务开始/执行/回滚/提交确认丢失。验证旧值或已提交值及已消费状态，禁止测试伪造业务成功。
- E2E走真实申请、受控邮件发送器处理、获取验证码、第二步兑换、第三步消费、新密码登录。USER/ADMIN、旧JWT/角色选择票、他账户有效Bearer及目标隔离、无Token和无效/过期/撤销Token分别覆盖。新密码登录按正常入口断言。
- 既有旧第三步测试随两字段契约迁移，保留对应安全、SQL原子性、并发及故障断言。第二步中“旧组合入口仍可用/共享双入口额度”的阶段性断言改为最终“旧请求400/两步独立额度”，保留其跨申请累计和统一拒绝覆盖，其他第二步行为保持回归。
- 真实OpenAPI验证第三步两字段与响应错误，前两步契约不变。DTO/toString、异常链及HTTP无敏感信息回归。
- Windows最终执行以下完整验证，MySQL8.4、Redis、MinIO均实跑。统计新鲜JUnit XML通过/失败/错误/跳过及类数，记录源代码冻结commit、实际命令、退出码与耗时。必要容器测试须零跳过。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

既有1926结果仅作基线，不作为本轮实现通过证据。测试数量可因删除旧契约并加入新契约合理变化，需说明迁移对应关系。

## 13. 部署切换与curl

本轮沿用用户确认的直接切换策略。应用实例须协调版本切换，避免同一路径新旧请求体契约混用，前端在第三步独立审核accepted且新版服务可用后整体切换三步式。部署文档需标明前两步已上线及第三步新契约启用条件。旧码的剩余有效期仍可在第二步验证，第二步已签发且尚有效的schema 1票据可直接供新版第三步消费。

客户端安全准备PASSWORD_RESET_BODY_FILE，含resetTicket/newPassword两个字段，实际内容不入Git。生产使用HTTPS。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-resets' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_BODY_FILE}"
```

## 14. 审核处置、默认项与设计自查

前轮独立验证接口实现审核NOTE-001原分级为非阻断，问题是两个严格Reader引擎重复。当前已核实成立，认可维护建议方向，用户在本轮明确选择A，继续排期共享引擎提取。后续审核材料沿用原编号并标注来源，记录问题、分级、方向、处置与证据，不将排期等同审核关闭。本轮设计首次提交尚无本任务审核反馈。

技术细化随本文提交审核，包括按摘要读取的可定位边界、完整快照CAS和同摘要变更损坏分类、精确到期端点、第三步普通失败直接拒绝而密码比较仅在有效绑定后执行、复用票据组件时的错误族转换、旧测试按最终阶段迁移。均围绕本接口授权与一致性，具体文件和顺序在writing-plans阶段列出。

自查已核对单接口范围、前轮跨步骤约定、schema 1兼容、300秒及到期端点、两步独立预算、先消费后CAS、消费未知零写入、数据库提交未知、并发新票保护、账户版本撤销、旧请求直接切换、正常新密码登录及全量验证。各章节无占位业务约定，实施状态与既有验证结果已明确区分。

书面设计提交后，等待用户审阅及既有独立设计审核流程确认，再使用writing-plans编写实施计划。
