# 找回密码历史摘要兼容修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式沿项目协议，由Codex在本会话实现，Claude独立只读审核。执行子技能未列为可用，按本会话原生方式逐任务实施。用户已确认开始，任务1至5实现与自验完成，独立实现审核材料已备齐。

**Goal:** 让持有有效邮箱验证票据的历史账号通过POST /api/v1/users/password-resets将非空旧格式摘要替换为当前BCrypt摘要，随后使用新密码正常登录。

**Architecture:** 在现有Rules中区分存量非空校验与当前BCrypt格式识别。SnapshotReader保留历史原值，Service按支持格式决定同密码比较，再执行既有新摘要严格校验、票据消费及原prepared单次CAS。

**Tech Stack:** Java21、Spring Boot4.1.1、Jakarta Validation、既有BCrypt、JPA/MySQL8.4、Redis/Lua、JUnit5/MockMvc/Testcontainers、springdoc、Gradle Wrapper。

**Spec:** [历史密码摘要兼容设计](../specs/2026-11-11-password-reset-legacy-hash-design.md)。原始f0f2abe已获第1/3轮设计审核accepted，零阻断项。功能基线main @ ff191ac，计划开始时HEAD=f0f2abe。设计NOTE-001采纳null单测防御与数据库可达值测试分配，NOTE-002保留已确认分支顺序，处置见文末。

## Global Constraints

- 唯一改造接口POST /api/v1/users/password-resets。公开、USER/ADMIN同规则，目标由完整票据绑定及prepared复核确定，主动Bearer沿现有安全链。
- 请求resetTicket/newPassword，成功204空正文/no-store。严格UTF-8 application/json、16384字节预算、规范43字符Base64URL票据及8至72位可打印ASCII新密码按现行契约执行。
- 旧值原文非null且String.isBlank为false时可参与重置。null、空串、Java判定全空白为503 PASSWORD_RESET_CONFIRMATION_UNAVAILABLE，发生于消费前，原票期限与已扣额度按既有规则保留。
- 支持格式为60字符、$2a$/$2b$/$2y$前缀、两位成本04至31及后续53字符属于现有BCrypt字符范围。支持时执行hasher.matches，同密码400 NEW_PASSWORD_SAME_AS_OLD；其他非空值直接进入当前hasher.hash。
- 新生成摘要和验证码摘要使用现有严格requirePasswordHash。生成/比较异常及非法新摘要503，消费前结束。成本31只做格式分类测试，实际哈希生成和比较使用正常测试成本。
- 原历史值保留大小写及首尾字符，作为prepared.passwordHash传入原binary CAS。消费明确成功后单次数据库条件更新，成功密码/版本/时间按原SQL维护。
- 完整票据、原300秒期限、独立IP60秒10次/账户900秒10次、并发CAS、消费未知零写事务与消费后故障保持消费均按既有三步流程执行。
- null属于数据库NOT NULL约束下的单测防御边界，MySQL集成用例使用实际可存储的空串与空白串。所有测试使用合成历史样本。
- 设计NOTE-002保留历史分支直接编码顺序，格式识别及普通失败使用既有授权前置顺序，历史分支的matches调用次数为0。
- 中文文档与提交，源码范围为当前单接口及必要测试。预计复用现有SQL、迁移、配置、Redis协议与PasswordHasher。
- 最终MINIO_INTEGRATION_TESTS=true cleanTest check，MySQL/Redis/MinIO必要测试零跳过。1973/0/0/0为旧基线独立复验。

## Review Focus

1. requirePasswordHash复用识别函数后，新摘要及验证码仍拒绝成本03/32、非法字符或长度偏差，任务1/4。
2. Service单测使用mock Rules，现有支持分支必须显式stub true，避免默认false令同密码保护测试走错路径，任务2。
3. 历史值首尾空格及大小写原文参与CAS，消费后被并发改写应零行400，任务2/3。
4. null用单测注入，真实MySQL空串/空白503时票和原TTL保留，IP及账户额度已扣，任务1/2/3。
5. 历史分支matches为0但hash及严格新摘要校验必执行，编码失败和非法新摘要503且零消费，任务2/3。

## 文件地图与接口

| 目录 | 修改文件 |
| --- | --- |
| src/main/java/org/example/music/user/domain | PasswordResetConfirmationRules.java |
| src/main/java/org/example/music/user/service | PasswordResetConfirmationSnapshotReader.java、PasswordResetConfirmationService.java |
| src/main/java/org/example/music/user/controller | PasswordResetConfirmationController.java |
| src/test/java/org/example/music/user/domain | PasswordResetConfirmationRulesTest.java |
| src/test/java/org/example/music/user/service | PasswordResetConfirmationSnapshotReaderTest.java、PasswordResetConfirmationServiceTest.java |
| src/test/java/org/example/music/user | PasswordResetConfirmationIntegrationTest.java、PasswordResetConfirmationFailureIntegrationTest.java、PasswordResetConfirmationConcurrencyIntegrationTest.java |
| docs/api/user | confirm-password-reset.md |
| docs/deployment | password-reset-confirmations.md |
| docs/superpowers/specs | 2026-11-11-password-reset-legacy-hash-design.md，登记审核及本轮取值 |
| docs/superpowers/plans | 本文件，记录执行进度及实际验证 |

新增docs/reviews/2026-11-11-password-reset-legacy-hash-review.md用于本轮实现审核。代码与测试复用现有文件，测试夹具取现有ConfirmationIntegrationSupport的ready、issue、confirm、ticketState、row等方法。

~~~java
// PasswordResetConfirmationRules
boolean isSupportedPasswordHash(String hash);
void requireStoredPasswordHash(String hash);
void requirePasswordHash(String hash);

// 既有调用入口
Optional<PasswordResetPreparedSnapshot> prepareTicket(PasswordResetTicketSnapshot ticket);
void reset(PasswordResetConfirmationRequest request, String clientIp);
~~~

requirePasswordHash使用isSupportedPasswordHash统一现有格式判断。requireStoredPasswordHash校验非null且非空白。两者失败使用原ConfirmationUnavailable。Service在票据、额度及prepared通过后分类旧值，支持分支才调用matches。

## 任务1　规则分类与严格校验复用

**Files:** Rules及其Test。

**Interfaces:** 新增isSupportedPasswordHash(String)、requireStoredPasswordHash(String)，现有requirePasswordHash及validateChallenge继续作为严格摘要入口。

- [x] **1.1 写失败测试。** should_classify_supported_bcrypt_and_reject_unsupported_values覆盖三前缀及04/31成本，非法成本03/32、长度59/61、非法字符、前后空白、未知非空值及null均返回false。should_allow_nonblank_stored_values_but_reject_null_or_blank用NullSource和空白样本验证存量规则；严格入口和带非法codeHash的challenge均抛ConfirmationUnavailable。

~~~java
assertTrue(rules.isSupportedPasswordHash("$2b$04$" + "a".repeat(53)));
assertFalse(rules.isSupportedPasswordHash("$2b$03$" + "a".repeat(53)));
assertDoesNotThrow(() -> rules.requireStoredPasswordHash(" Legacy-V1 "));
assertThrows(PasswordResetConfirmationUnavailableException.class, () -> rules.requireStoredPasswordHash(null));
assertThrows(PasswordResetConfirmationUnavailableException.class, () -> rules.requirePasswordHash(" Legacy-V1 "));
~~~

- [x] **1.2 运行失败测试。** .\gradlew.bat test --tests "org.example.music.user.domain.PasswordResetConfirmationRulesTest" --console=plain，预期新增方法缺失编译失败或新断言失败。
- [x] **1.3 实现规则。** 在既有Rules集中实现支持格式判断，strict入口复用该判断，存量入口用null/isBlank检查。字符范围与成本取原规则，原值保持原文传递。
- [x] **1.4 验证调用方回归。** 重跑1.2并加 --tests "org.example.music.user.service.PasswordResetCodeVerifierTest" --tests "org.example.music.user.service.PasswordResetVerificationServiceTest"，要求实际通过，确认验证码严格性。
- [x] **1.5 提交。** 仅本任务文件，中文提交“fix: 区分存量密码与新密码摘要校验”。

## 任务2　prepared读取和重置分支衔接

**Files:** SnapshotReader、Service及各自Test。

**Interfaces:** 消费任务1三个规则方法，原prepared和updater接口按现有类型传递。SnapshotReader调用requireStoredPasswordHash，Service支持格式时调用matches，再按原顺序hash/strict校验/consume/update。

- [x] **2.1 写失败测试。** SnapshotReader的should_preserve_raw_email_and_legacy_hash_without_rebinding用带首尾字符旧值验证原文；迁移原“bad摘要503”断言为null/空白503，事务异常与绑定变化断言保留。Service现有支持分支显式stub isSupportedPasswordHash("oldHash")为true；新增历史分支设false，matches为0，仍执行hash及requirePasswordHash，updater收到原prepared。

~~~java
when(rules.isSupportedPasswordHash(prepared.passwordHash())).thenReturn(false);
service.reset(request, "127.0.0.1");
verify(hasher, never()).matches(anyString(), anyString());
verify(hasher).hash(request.newPassword());
verify(rules).requirePasswordHash("newHash");
verify(updater).update(prepared, "newHash");
~~~

- [x] **2.2 运行失败测试。** .\gradlew.bat test --tests "org.example.music.user.service.PasswordResetConfirmationSnapshotReaderTest" --tests "org.example.music.user.service.PasswordResetConfirmationServiceTest" --console=plain，预期历史值读取及分支调用断言失败。
- [x] **2.3 实现最小分支。** SnapshotReader在绑定匹配后做非空存量校验。Service仅在isSupportedPasswordHash为true时比较同密码，所有成功路径生成并严格校验新摘要，使用原prepared单次更新。编码/比较异常、上限、消费未知及脱敏沿现有边界。
- [x] **2.4 运行单测回归。** 重跑2.2，覆盖支持分支同密码保票、历史分支生成失败/非法新摘要零消费、匹配调用故障503、IP/账户拒绝与缺票零密码计算、消费false/未知零更新。
- [x] **2.5 提交。** 中文提交“fix: 允许历史密码摘要通过找回流程重置”。

## 任务3　真实历史账号闭环与并发故障证据

**Files:** ConfirmationIntegrationTest、FailureIntegrationTest、ConcurrencyIntegrationTest。

**Interfaces:** 复用save、ready/issue/confirm、ticketState/row及IntegrationTest已有login(user,account,type,password,role)。真实测试JDBC写入合成旧值，再通过正常三步入口完成恢复。

- [x] **3.1 写真实闭环测试。** should_reset_legacy_hash_and_allow_normal_login用MethodSource组合USER/ADMIN和合成样本。样本至少含32位十六进制、大小写差异、带首尾空格、任意非空旧值、带BCrypt前缀的03/32成本或非法长度。每个参数用例隔离清理，旧值写入发生于第一步申请之前。断言204、当前BCrypt识别成功、新密码matches成功、版本+1、旧Token401、正常登录200，其他用户列按原允许字段范围核对。

~~~java
jdbc.update("update users set password_token=? where id=?", legacy, user.getId());
var issued = issue(ready(user.getEmail()));
assertEquals(204, confirm(issued, "new-password").getResponse().getStatus());
assertTrue(hasher.matches("new-password", (String) row(user).get("password_token")));
assertEquals(200, access(login(user, user.getEmail(), "EMAIL_PASSWORD", "new-password", role)));
~~~

- [x] **3.2 迁移故障测试。** 原should_preserve_ticket_when_current_password_hash_is_damaged中broken样本迁移到3.1的成功恢复；该方法改成真实空串、空格及制表/换行值的参数化503保票测试，断言state/PEXPIRETIME保留及completion两额度已扣。null由任务1/2单测覆盖。旧值设置为历史格式后，注入真实hasher生成故障或非法新摘要，仍503且零consume/update。业务已在任务2实现的成功断言可直接通过，实际运行在3.4记录。
- [x] **3.3 补原值CAS测试。** should_conflict_when_legacy_hash_changes_after_consumption以带首尾字符历史值为prepared，spy consume真实成功后改写旧值大小写/首尾字符，CAS一次且0行400，保留并发新值。另有历史首尾原文成功用例证明未经trim。原数据库事务失败、消费未知及跨期限回归继续执行。
- [x] **3.4 运行真实定向回归。** .\gradlew.bat test --tests "org.example.music.user.*PasswordResetConfirmation*Test" --tests "org.example.music.user.*PasswordResetVerification*Test" --tests "org.example.music.user.*PasswordResetTicket*Test" --console=plain，要求MySQL/Redis实际通过、0失败/0错误/0必要跳过。注入hasher故障时在issue后设置spy，避免影响第二步验证码及后台生成。
- [x] **3.5 提交。** 中文提交“test: 覆盖历史密码恢复与原值并发保护”。

## 任务4　接口文档、OpenAPI与完整验证

**Files:** Controller、confirm-password-reset.md、password-reset-confirmations.md、IntegrationTest的真实OpenAPI断言，本计划及设计记录。

**Interfaces:** 以设计§3至§6为契约，同密码说明限定支持的BCrypt值，历史非空值通过票据恢复，空白存量及编码器/新摘要故障按503说明。

- [x] **4.1 写OpenAPI失败测试。** 在既有/v3/api-docs断言中检查第三步Operation含历史摘要兼容，503说明含空白旧摘要或新摘要生成异常；两个请求字段及响应状态/敏感标记继续校验。运行IntegrationTest对应方法，预期说明断言失败。
- [x] **4.2 更新文档。** Controller补充兼容语义。API/部署说明列明非空历史值的恢复路径、支持BCrypt时同密码判断、原值CAS及空白503，列出方法/权限/字段/示例/204/错误/安全curl。本设计审核NOTE-001明确null只做单测防御，NOTE-002记录沿已确认分支直接编码的处置。
- [x] **4.3 定向验证并冻结源码。** 重跑4.1并跑任务1至3涉及用例，git diff --check，核实完整变更文件含untracked。中文提交“docs: 说明找回密码历史摘要兼容行为”，记录源码/测试冻结commit。
- [x] **4.4 执行完整验证。** Windows PowerShell设置$env:MINIO_INTEGRATION_TESTS = "true"后运行.\gradlew.bat cleanTest check --console=plain，等待实际完成，记录命令、冻结点、退出码、BUILD状态、耗时及新鲜JUnit XML类数/通过/失败/错误/跳过。必要MySQL/Redis/MinIO均零跳过，任何源码修复后重新冻结并验证。
- [x] **4.5 核对严格摘要回归。** 完整结果核对CodeVerifier、Verification、Ticket及旧登录/密码修改相关测试实际执行。新摘要及验证码成本03/32、非法字符、长度差异的拒绝有任务1证据，null未通过修改数据库约束构造。

## 任务5　实施审核材料与交付

**Files:** 新增docs/reviews/2026-11-11-password-reset-legacy-hash-review.md，更新本计划与设计的真实状态。

**Interfaces:** AGENTS第14节材料及六列意见复核表。功能基线ff191ac、设计f0f2abe、当前源码冻结点与交审HEAD分别记录。

- [x] **5.1 编写审核材料。** 唯一Method+Path、权限、声称行为、完整文件含untracked、API/部署/设计/计划路径、数据库迁移情况、实际测试命令与精确数量、安全curl、默认项及未知结果边界齐备。列明broken用例迁移、null单测范围、历史值CAS证据、NOTE-002处置。
- [x] **5.2 交付前核对。** git diff --check、git status --short、基线至HEAD及工作树清单，与审核材料逐文件对照；源码/测试相对冻结点零差异，文档状态反映实际测试和独立审核阶段。
- [x] **5.3 提交审核材料。** 中文提交“docs: 记录历史密码恢复验证与审核材料”，供用户转Claude独立只读审核，后续反馈按同任务轮次核实处理。
- [x] **5.4 汇报实际交付。** 列明实现与自验结果、审核材料、相关取舍及实际提交。用户通过正常三步恢复账号所需的行为以API/部署说明为准。

## 设计审核反馈复核与处置

原始设计f0f2abe，第1/3轮accepted，零阻断项。审核方MINIO_INTEGRATION_TESTS=true cleanTest check为BUILD SUCCESSFUL，用时16分50秒，172套件1973通过/0失败/0错误/0跳过，属于既有基线独立复验。本轮计划阶段仅核对代码、约束与文档。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断 | 成立，V1第5行password_token VARCHAR(255) NOT NULL，数据库实际可存空串/空白，null由约束拦截 | 认可，测试可达性与防御边界建议 | 认可，null单测注入、真实MySQL覆盖空串/空白可证明同一边界 | 已写入Global Constraints及任务1/2/3，设计同步说明 | null规则/投影防御单测及真实MySQL空串/空白保票用例已通过，待独立实现审核复核 |
| NOTE-002 非阻断 | 成立，历史分支省去一次matches，持有效票据者可观察自身旧值格式相关的耗时差异 | 认可，授权已完成后的低信息量差异，报告允许不采纳 | 伪比较属于可选方向；已确认设计使用历史分支直接生成新摘要，额外BCrypt成本收益低 | 本轮不采纳补时伪比较，保留已确认顺序，并以历史分支matches为0的测试钉住 | 历史分支0次matches及完整票据授权前置测试已通过，保留路径耗时差异，待独立实现审核复核 |

## 自查与执行确认

设计各节已映射任务1至5，五项Review Focus均有测试落点，函数签名和已有夹具方法一致。任务3业务测试针对任务2已实现行为可直接通过，Task1/2/4新增缺口先取得失败证据，记录实际命令结果。旧broken值503断言迁移、null不可达约束、严格BCrypt复用及错误边界已有明确处置。

计划规模围绕四个生产文件及现有测试、API/部署说明。执行方式沿Codex本会话实现、Claude独立只读审核，用户确认后已按计划实施。

## 实施进度

- c277612完成任务1，新增规则方法前编译失败，随后Rules/CodeVerifier/VerificationService定向通过。
- 778bae9完成任务2，新增测试在旧逻辑下30项中7失败，改造后30项全部通过；失败对应历史摘要被拒、旧分支执行matches及顺序缺口。
- 5f940a1完成任务3，24类303项真实定向通过/0失败/0错误/0跳过，3分35秒。
- 任务4先以真实OpenAPI说明断言取得1项失败，再更新注解与文档。最终定向命令为cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordResetConfirmation*Test" --tests "org.example.music.user.*PasswordResetVerification*Test" --tests "org.example.music.user.*PasswordResetTicket*Test" --console=plain，退出0、BUILD SUCCESSFUL，3分27秒，24类303通过/0失败/0错误/0跳过。
- 本次文档提交冻结源码/测试，后续完整check及审核材料使用该冻结点记录实际结果。

## 最终验证与交审状态

源码/测试冻结3a4ffdc。Windows PowerShell执行$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain，实际退出0、BUILD SUCCESSFUL，用时16分39秒。新鲜JUnit XML为172类2034通过/0失败/0错误/0跳过，找回密码模块46类435项，MySQL8.4、Redis8.10.2、MinIO均实跑，S3AudioStorageIntegrationTest为5通过/0失败/0错误/0跳过。

源码冻结后仅登记本计划、设计及docs/reviews/2026-11-11-password-reset-legacy-hash-review.md。完整文件15份已纳入材料，包含新增设计/计划/审核文件。设计NOTE-001的null防御与真实空白测试已落实，NOTE-002按已确认顺序处理，待独立实现审核复核。
