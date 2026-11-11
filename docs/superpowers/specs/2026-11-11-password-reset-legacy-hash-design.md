# 找回密码兼容历史密码摘要修复设计

日期为2026-11-11。基线为main @ ff191ac，开始设计时与origin/main同步，工作区干净。既有三步找回密码实现已获第1/3轮独立实现审核accepted，独立完整1973通过、0失败、0错误、0跳过，该数字属于既有基线。

用户确认方案A，邮箱验证和票据复核通过后，非空历史摘要可重置为当前BCrypt摘要。用户同时确认null、空字符串或全空白摘要返回503。原始f0f2abe已获第1/3轮独立设计审核accepted，零阻断项，用户已确认实施计划，当前实现与完整自验已完成，源码/测试冻结3a4ffdc，完整2034通过/0失败/0错误/0跳过，第1/3轮独立实现审核accepted，零阻断项、无新增非阻断项。

## 1. 现象、根因与目标

用户报告账号zhangcm来自项目开始前的旧数据库，希望通过找回密码将旧密码摘要迁移为当前项目使用的格式。附件8de8f0b6-047b-46ee-a666-917294cb13e6/已粘贴的文本.txt的traceId为a76b014e-b093-4d19-b232-18d7c0c44433。

日志定位到PasswordResetConfirmationSnapshotReader.prepareTicket调用PasswordResetConfirmationRules.requirePasswordHash时拒绝旧摘要，返回503 PASSWORD_RESET_CONFIRMATION_UNAVAILABLE，发生于票据消费之前。当前规则要求旧值满足BCrypt格式及成本范围，此前设计遗漏了历史账号迁移场景。

本轮目标是在完整邮箱验证票据授权下，将非空历史存量摘要替换为当前PasswordHasher生成的新摘要，并通过正常登录入口验证可用性。具体旧算法由历史数据形成，恢复资格由服务端票据和账户绑定复核确定。

## 2. 单接口范围与方案选择

唯一改造接口为POST /api/v1/users/password-resets。

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| A，已选 | 将非空、当前算法无法识别的存量摘要作为原始并发快照，验证票据后生成新BCrypt摘要 | 覆盖历史格式，原始值继续参与CAS；同密码比较适用于当前支持的BCrypt值 |
| B | 按明确识别的历史算法格式开放重置 | 需要先取得旧算法及格式信息，覆盖范围受白名单约束 |

用户已确认A。null及Java String.isBlank判定为空白的旧值返回503，保留原票期限，已准入额度计数保留。该约定覆盖空字符串、ASCII空格及Java判定的其他空白字符。

现有password_token列具有NOT NULL约束，null分支属于应用防御边界，由单测注入覆盖。真实MySQL集成测试使用可存储的空串和空白串验证503及保票行为。

本设计将旧设计2026-11-09-password-reset-completion-design.md中“旧密码摘要必须符合BCrypt，否则503”的取值更新为本轮兼容规则。既有审核结论作为原版本事实保留，本轮按独立兼容修复任务提供实现证据。

## 3. HTTP契约与授权

请求为application/json的resetTicket/newPassword两个字符串，规范Base64URL、UTF-8与16384字节预算，以及新密码8至72位可打印ASCII、非全空格、保留首尾空格等规则沿现有接口执行。成功204空正文，Cache-Control为no-store。

公开访问，USER和ADMIN同规则。服务端完整读取schema 1票据，核验当前指针、原始/规范化邮箱、角色、认证版本及原300秒期限后，读取prepared复核目标绑定。主动他账户有效Bearer按票据账户确定目标，主动无效Bearer由现有安全链处理。

本轮通过相同请求支持历史账号恢复。API和OpenAPI需明确历史摘要兼容条件、当前BCrypt同密码判断，以及空白旧值和新摘要生成故障的503边界。

## 4. 存量摘要分类与处理

| 旧值 | 同密码判断 | 重置处理 |
| --- | --- | --- |
| 当前支持的BCrypt格式 | 使用现有PasswordHasher.matches | 相同400 NEW_PASSWORD_SAME_AS_OLD，不同则生成新摘要 |
| 非空、当前不支持的历史值 | 依据完整票据授权进入密码生成 | 用当前PasswordHasher.hash生成新BCrypt摘要 |
| null或String.isBlank | 503 PASSWORD_RESET_CONFIRMATION_UNAVAILABLE | 发生于消费前，票据按原期限保留 |

当前支持格式复用现有规则，固定60字符，前缀$2a$、$2b$或$2y$，两位十进制成本04至31，后续53字符属于现有BCrypt字符范围。识别使用原始字符串。

历史兼容覆盖非空未知格式，包括具有BCrypt前缀但长度、字符或成本不满足现有规则的值。其语义作为当前无法识别的存量值处理。对该分支，邮箱票据完成身份授权，新摘要按当前规则生成。

支持格式中的实际比较或编码器运行故障返回503，并在消费前结束。新摘要必须经过现有严格BCrypt校验。验证码摘要同样使用现有严格规则。

## 5. 组件与执行顺序

优先复用PasswordResetConfirmationRules，定义以下内部职责。

- boolean isSupportedPasswordHash(String hash)识别现有支持格式，对不支持值返回false。
- void requireStoredPasswordHash(String hash)要求旧值非null且非空白，失败抛现有ConfirmationUnavailable。
- void requirePasswordHash(String hash)继续作为严格BCrypt校验入口，复用支持格式判断，供新摘要、验证码及既有调用方使用。

PasswordResetConfirmationSnapshotReader.prepareTicket在账户绑定匹配后调用requireStoredPasswordHash，返回包含旧值原文的PasswordResetPreparedSnapshot。

PasswordResetConfirmationService按以下顺序处理。

1. IP额度准入、完整票据读取、可信账户额度准入。
2. 短只读事务复核账户绑定并取得非空旧摘要原文。
3. 在事务外判断旧值是否为支持格式，支持时调用matches并处理同密码。
4. 检查tokenVersion上限，调用现有hasher.hash生成新摘要，严格校验新摘要。
5. 单Lua完整预期消费票据及当前指针，明确成功后将原prepared与新摘要交既有事务更新组件。
6. 原SQL条件更新成功，返回204，认证版本递增，旧登录资格按现有认证版本校验失效。

历史旧值以原始字符串传递，数据库条件更新精确匹配其binary值。原始值的大小写、前缀、首尾字符及字节表达均作为CAS依据。该规则覆盖历史摘要读取后被另一请求修改的并发场景。

## 6. 一致性与故障语义

票据消费使用完整预期及当前指针，数据库更新使用原prepared单次CAS。并发密码、邮箱、角色、版本或账户存在性变化导致CAS零行时统一400，已消费票保留消费状态。仅用户名变化可按原CAS完成。

旧摘要空白、当前算法比较异常、新摘要生成或校验故障、版本上限发生于消费前，返回503并保留原票期限，已准入额度保留。消费未知503且本次零写事务；明确消费后数据库故障按已确认策略保持消费。提交确认丢失可能已改密，调用方通过新密码正常登录核实。

日志、ProblemDetail及DTO脱敏沿既有边界执行。历史原值和新摘要属于敏感数据，测试证据采用合成样本及断言，真实账号通过正常三步入口恢复。

## 7. 文件与文档范围

| 文件 | 职责 |
| --- | --- |
| src/main/java/org/example/music/user/domain/PasswordResetConfirmationRules.java | 存量非空校验、支持格式判断及严格摘要校验复用 |
| src/main/java/org/example/music/user/service/PasswordResetConfirmationSnapshotReader.java | prepared接受非空历史摘要，保留原值 |
| src/main/java/org/example/music/user/service/PasswordResetConfirmationService.java | 支持格式才比较同密码，所有新密码使用当前hasher生成 |
| src/main/java/org/example/music/user/controller/PasswordResetConfirmationController.java | OpenAPI增加历史摘要兼容及503说明 |
| 对应Rules、SnapshotReader、Service测试 | 格式分类、空白拒绝、比较调用顺序、新摘要严格校验 |
| 现有ConfirmationIntegration、Failure、Concurrency测试 | 历史账号三步闭环、故障保票、原值CAS及并发变化 |
| docs/api/user/confirm-password-reset.md | 历史摘要兼容、同密码适用范围及错误边界 |
| docs/deployment/password-reset-confirmations.md | 存量恢复与故障说明 |
| 本轮设计、实施计划、审核材料 | 设计取值、实际测试及独立审核记录 |

预计通过现有组件完成修复，数据库结构与专用SQL作为现有依赖复用。文件精确清单、提交顺序及测试命令由writing-plans阶段列出。历史算法识别只服务当前重置接口的分支判断。

## 8. 验证与验收

- Rules验证支持前缀、成本04/31、60字符长度，以及null、空、全空白、非空未知、过长/过短、无效字符、成本03/32和带前后字符的样本。严格requirePasswordHash仍对非法新摘要及验证码摘要拒绝。
- SnapshotReader验证非空历史原值精确保留，null/空白503，邮箱/角色/版本/删除等绑定变化走原400，事务异常503。
- Service验证历史分支跳过matches，仍调用hash及严格新摘要校验，随后消费和原prepared更新。支持BCrypt分支执行同密码判断，同密码保留票期限。
- 真实MySQL/Redis三步测试使用合成历史值，经申请、受控邮件投递、独立验证、票据提交获得204，数据库新值符合当前BCrypt且hasher验证成功，新密码通过正常登录入口可用。USER/ADMIN覆盖版本递增及旧Token失效。
- 将既有“非空broken旧摘要返回503”用例迁移为历史值成功恢复，空白旧值保留503测试。新增新摘要损坏或生成故障的保票断言，确保旧值兼容与新值严格校验分别有证据。
- 历史原值带首尾字符时CAS使用原文，并发改变旧值后CAS零行400，保护并发写入。实际Redis消费未知、DB回滚/提交未知及跨期限等既有回归继续实跑。
- 更新真实OpenAPI与API文档说明，完整执行MINIO_INTEGRATION_TESTS=true的.\gradlew.bat cleanTest check --console=plain，统计本次通过/失败/错误/跳过及源码冻结点，必要MySQL/Redis/MinIO零跳过。
- 按AGENTS.md提交唯一接口的权限、行为、完整文件含untracked、实际命令与数量、API/迁移/基线、安全curl、默认项与意见复核，交Claude独立只读审核。

## 9. 自审与下一步

已核对单接口范围、A/B取舍、空白边界、历史值原文保留、支持格式分支、新生成摘要及验证码严格性、票据授权、同密码判断、CAS并发、消费前后故障、文档与测试迁移。书面设计包含本次业务决定及实现边界，具体计划在用户审阅后编写。

本轮设计阶段仅整理文档，1973通过属于原版本基线验证。用户已提供设计accepted报告，实施计划已获用户确认，当前实现定向24类303通过/0失败/0错误/0跳过，完整check另行记录。

## 10. 第1/3轮独立设计审核登记

审核对象main @ f0f2abe，用户粘贴审核报告结论为accepted，零阻断项。审核方独立执行MINIO_INTEGRATION_TESTS=true的.\gradlew.bat cleanTest check --console=plain，BUILD SUCCESSFUL，用时16分50秒，172套件1973通过/0失败/0错误/0跳过，必要MySQL、Redis、MinIO实跑，属于既有基线独立复验。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断 | 成立，V1第5行password_token为NOT NULL，空串/空白可存储，null由数据库约束拒绝 | 认可，测试可达性与防御边界建议 | 认可，null用单测注入，真实MySQL用空串/空白 | 本文§2及实施计划任务1/2/3已明确测试分配 | null防御单测及真实MySQL空串/空白503保票已通过，待独立实现审核复核 |
| NOTE-002 非阻断 | 成立，历史分支省去一次matches，持有效票据者可观察自身旧摘要格式相关耗时差异 | 认可，低信息量非阻断建议 | 补时伪比较是可选方向，当前已确认历史分支直接编码，额外BCrypt开销收益低 | 本轮不采纳补时伪比较，保留已确认顺序，计划以历史分支matches为0断言验证 | 授权前置及历史分支0次matches测试已通过，实际响应有路径耗时差异，待独立实现审核复核 |

实施计划为docs/superpowers/plans/2026-11-11-password-reset-legacy-hash-plan.md。实现已按计划推进，功能比较基线ff191ac，完整验证的源码冻结点与实际结果在实施计划及本轮审核材料记录。

## 11. 实现自验记录

用户确认计划后完成四份生产文件修改、六个测试类迁移与补充，以及API/OpenAPI/部署说明更新。源码/测试冻结3a4ffdc，最终定向24类303通过/0失败/0错误/0跳过，3分27秒。完整MINIO_INTEGRATION_TESTS=true cleanTest check退出0、BUILD SUCCESSFUL，用时16分39秒，172类2034通过/0失败/0错误/0跳过，必要MySQL、Redis、MinIO均实跑。

设计NOTE-001的null仅单测及真实空白保票证据已落实，NOTE-002维持历史分支直接编码。实施审核材料为docs/reviews/2026-11-11-password-reset-legacy-hash-review.md，当前首次第1/3轮独立实现审核accepted，零阻断项、无新增非阻断项。

## 12. 独立实现审核结论

审核对象47c88b5，第1/3轮独立实现审核accepted，零阻断项、无新增非阻断项、无剩余争议。独立完整验证172套件2034通过/0失败/0错误/0跳过，退出0、BUILD SUCCESSFUL，用时16分16秒，必要MySQL、Redis、MinIO实跑。NOTE-001已核实闭环，NOTE-002处置已被接受且维持非阻断。用户授权核实后推送，详细报告登记见本轮审核材料。
