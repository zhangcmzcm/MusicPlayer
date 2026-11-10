# 找回密码第三步契约切换实现审核请求

## 本次接口与审核状态

唯一接口为POST /api/v1/users/password-resets。原challengeId/code/newPassword切换为resetTicket/newPassword，本轮首次提交独立实现审核。书面设计646c1e6已获第1/3轮设计审核accepted，零阻断项；实现已完成自验，并获第1/3轮独立实现审核accepted，零阻断项、无新增非阻断项。

## 需求与权限

公开接口，无需登录、旧密码或近期auth_time。USER与ADMIN同规则。目标由服务端票据绑定确定，主动携带他账户有效Bearer仍重置票据账户。无效、过期或撤销Bearer受现有安全链拒绝。

前两步保持原方法、请求响应及生产者协议。第三步严格接收两个字符串，resetTicket为规范43字符Base64URL无填充、解码32字节且重编码相同。newPassword为8至72位可打印ASCII U+0020至U+007E、非全空格，首尾空格原值保留。UTF-8 application/json，正文16384字节含尾随空白。未知/缺失/重复及转义同名/嵌套/多根/null/非法编码等400，媒体类型415。旧三字段在业务准入前400。

## 声称实现的行为

- 按resetTicket的SHA-256摘要读取第二步现有schema 1票据及当前账户指针，完整核验10字段、邮箱关联、归属、规范版本、Redis时间和两键PEXPIRETIME。签发起300000毫秒，等于到期端点即失效。
- 第三步使用music:auth:password-reset-completion:，IP固定60秒10次、账户固定900秒10次。第二步保留原confirmation前缀及窗口，两步各自计数。IP先准入，完整票据后才按可信账户扣额。假票只耗IP。
- 仅IP额度拒绝429 PASSWORD_RESET_CONFIRMATION_RATE_LIMITED及原窗口Retry-After。账户额度和普通失效统一400 PASSWORD_RESET_VERIFICATION_FAILED，无等待、层级或次数字段。已准入额度保留，拒绝不增、不续窗，新票/重发/登录保留原窗口。
- 短只读事务读取现有prepared投影，精确核对账户ID、原始邮箱、角色和认证版本，并校验密码摘要。事务外比较新旧密码、检查版本上限及计算新摘要。
- 同密码400 NEW_PASSWORD_SAME_AS_OLD、摘要故障503及版本Long.MAX_VALUE在消费前结束，保留原票与期限。普通缺票/限额/绑定失效直接拒绝，密码比较仅在有效票据及prepared之后执行。
- 单Lua用已读完整不可变快照重新核验票据与当前指针，明确成功时原子删除本票及匹配指针。同摘要字段变动、可定位半状态、类型/TTL损坏503保留现场，普通失效400。
- 只有明确消费成功后才调用既有PasswordResetConfirmationTransactionService及一次专用MySQL CAS，使用原prepared匹配ID、binary原始邮箱/角色/旧密码摘要、精确版本及上限。只改password_token、token_version加1、updated_at，0行400，1行成功，其他500回滚。
- 成功204空正文、no-store，目标全部旧JWT、旧角色选择票及旧版本重置票在后续版本校验中失效。新密码通过原正常登录入口可用，ADMIN继续原角色选择流程，他账户有效Token不受目标改密影响。
- 消费未知503且本次零写事务/SQL，明确消费后数据库故障保持消费，提交确认丢失可能已改密。服务端自动重试、票恢复、退款与204重放均关闭。客户端用新密码正常登录核实，必要时重新申请。
- 新票先签发则旧票消费400且保护新票；旧票先消费则原CAS继续，后签发旧版本新票在成功递增后失效。期限内消费、跨期限SQL提交可完成。
- 复用TicketStore/Rules时在第三步边界把VERIFICATION不可用安全转换为CONFIRMATION不可用，保持第二步错误行为。敏感DTO/toString和异常链脱敏，Controller解析前no-store，日志保留traceId。
- 六份API/部署文档同步最终三步流程及额度/错误三方表。按已批准方案A修改现有Reader，共享严格解析引擎继续排期。

## 数据库迁移、依赖及配置

数据库迁移为无。现有UserRepository投影、专用SQL及事务更新组件复用，本轮无SQL修改。已执行Flyway、ddl-auto、依赖、Compose、安全配置和全局错误映射均无变更。原验证码专用consume方法及Lua脚本在所有调用迁移后删除，第二步read/recordFailure及共享Lua原语保留。

## API与部署文档

| 步骤 | API文档 | 部署文档 |
| --- | --- | --- |
| 1 | docs/api/user/request-password-reset.md | docs/deployment/password-reset-requests.md |
| 2 | docs/api/user/verify-password-reset.md | docs/deployment/password-reset-verifications.md |
| 3 | docs/api/user/confirm-password-reset.md | docs/deployment/password-reset-confirmations.md |

设计为docs/superpowers/specs/2026-11-09-password-reset-completion-design.md，计划为docs/superpowers/plans/2026-11-10-password-reset-completion-plan.md。

## 基线与源码冻结

- 设计审核对象main @ 646c1e6。该提交仅新增设计文档，功能代码基线仍为feb13c9。
- 实施计划9f1fd01，任务1至6依次为729f91d、874e808、c049c37、d43642f、89e5068、4eb9425。
- 本轮源码/测试冻结为4eb9425。完整验证后源码、测试、build.gradle与docker-compose.yml相对该点零变化，后续仅登记计划/设计及本审核材料。
- 交审对象为main上本审核材料所在提交，使用git rev-parse HEAD取得实际对象。独立实现审核尚未作结论。

## 实际测试与构建

### 最终完整验证

执行环境为Windows PowerShell，源码冻结4eb9425。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

退出码0，BUILD SUCCESSFUL，用时16分59秒。cleanTest清除原结果，test实际执行，逐份本次JUnit XML聚合172类1973通过、0失败、0错误、0跳过。必要MySQL8.4、Redis8.10.2、MinIO均实跑，S3AudioStorageIntegrationTest为5通过/0失败/0错误/0跳过，跳过项为无。

本次全量中的找回密码模块为46类374项，包含新增4类36项及旧契约迁移后各项，均通过。容器关闭期间有既有Hikari连接池关闭告警，构建成功依据实际退出码及新鲜XML结果。

### 最终定向验证

```powershell
cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordResetConfirmation*Test" --tests "org.example.music.user.*PasswordResetTicket*Test" --tests "org.example.music.user.*PasswordResetCompletion*Test" --tests "org.example.music.user.*PasswordResetVerification*Test" --console=plain
```

退出0，BUILD SUCCESSFUL，用时3分31秒，26类252通过/0失败/0错误/0跳过。该定向验证之后仅提交文档4eb9425再执行上面的完整check。

任务4阶段另运行cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordReset*Test" --console=plain，4分56秒、46类369通过/0失败/0错误/0跳过。此结果早于任务5补充5项测试，当前模块数量以最终完整check的374为准。

### 重点测试

下表是完整check中的部分重点类，所有数量均为实际执行且0失败/0错误/0跳过。

| 类 | 项数 | 覆盖 |
| --- | --- | --- |
| PasswordResetTicketLookupIntegrationTest，新增 | 15 | 真实生产者票、按摘要读取、归属/字段/TTL损坏、版本精度 |
| PasswordResetTicketConsumeIntegrationTest，新增 | 11 | 跨实例一次消费、签发/消费双向顺序、同摘要变动、已知半状态 |
| PasswordResetCompletionQuotaIntegrationTest，新增 | 7 | 原窗口、独立命名空间、损坏及自然恢复 |
| PasswordResetCompletionQuotaStoreTest，新增 | 3 | 统一账户拒绝、未知回复及单次执行 |
| PasswordResetTicketStoreTest | 7 | 原读取/兑换与新读取/消费回复严格校验 |
| PasswordResetConfirmationRequestReaderTest | 11 | 两字段、规范编码、严格JSON、密码及字节预算边界 |
| PasswordResetConfirmationControllerTest | 3 | 公开HTTP/remoteAddr/ProblemDetail/空204/no-store |
| PasswordResetConfirmationServiceTest | 13 | 顺序、消费前保票、未知零更新、错误族及脱敏 |
| PasswordResetConfirmationSnapshotReaderTest | 5 | 原始邮箱、绑定变化、非法字段、密码摘要与事务故障 |
| PasswordResetConfirmationIntegrationTest | 18 | 三步闭环、USER/ADMIN、新密码登录、旧登录/角色票撤销、额度、OpenAPI、大版本精确递增 |
| PasswordResetConfirmationConcurrencyIntegrationTest | 11 | 六路并发一次SQL、签新票双向交错、prepared字段并发变化、用户名保留及闩锁 |
| PasswordResetConfirmationFailureIntegrationTest | 17 | 实际消费/准入丢回复、零写事务、真实DB查询/只读提交/写事务故障、跨期限与响应丢失 |
| PasswordResetVerificationIntegrationTest | 16 | 第二步原规则、最终独立额度及旧三字段双端拒绝 |
| PasswordResetVerificationConcurrencyIntegrationTest | 9 | 原猜码第五错/非终态错与兑换、迟到操作及新票版本保护 |
| PasswordResetTicketExchangeIntegrationTest | 16 | 原生产者兼容、原子兑换、候选碰撞、并发、版本和未知结果 |

### 测试过程中的修正

任务1至4各次TDD先以缺少新方法/构造契约的编译失败证明新增测试生效，再实现并验证。该阶段性失败未计入最终通过。

任务5首轮定向252项中，查询故障夹具1项失败，其余251项通过。夹具对Spring Data抽象方法使用callRealMethod，未制造预期数据库异常，得到500。已将该用例改为测试容器内临时RENAME TABLE制造真实查询失败，并通过finally恢复表名。故障类单独17项通过，随后完整定向252项及最终全量1973项均通过。该修正涉及测试夹具，生产异常契约保持设计取值。

## 旧测试迁移对应

| 原行为/断言 | 最终验证位置 |
| --- | --- |
| 旧验证码非终态错后仍可成功、第五错失效 | StoreIntegration保留计错/期限；TicketExchange.should_allow_nonterminal_attempt_change_but_reject_fifth_failure及VerificationConcurrency对应两种交错 |
| 旧验证码跨实例只消费一次 | TicketExchange.should_exchange_once_across_two_store_instances验证验证码→票，TicketConsume.should_consume_both_keys_once_across_instances验证票消费，ConfirmationConcurrency验证一次SQL |
| 迟到旧码不触新ACTIVE、pending保留 | 原Store.recordFailure断言与TicketExchange.should_not_touch_new_active_when_old_exchange_or_failure_arrives；ConfirmationConcurrency.should_preserve_pending_mail_during_ticket_consumption |
| 新激活先后与旧SQL资格 | VerificationConcurrency验证新码激活先后；ConfirmationConcurrency验证新票签发和旧票消费双向先后 |
| 旧组合入口与第二步共享额度 | VerificationIntegration迁移为两步独立计数及原窗口保留，账户跨重发累计与统一拒绝继续验证 |
| 旧三字段正常提交 | VerificationIntegration.should_reject_legacy_body_at_both_endpoints_without_quota_and_complete_ticket_flow及新Reader严格拒绝；真实三步取得票后204 |
| 原验证码伪比较矩阵 | 第二步Verifier/Verification测试完整保留，第三步缺票/账户拒绝/绑定变化验证零密码计算 |
| 原密码条件更新、真实回滚及提交确认丢失 | 原TransactionIntegration/Test继续运行，第三步Failure在票消费链路验证状态及单次SQL |

## 变更文件清单，含新增及删除

以下相对仓库根，A新增、M修改、D删除，共39份，包含本审核材料本身。新增文件在相应任务提交纳入Git，审核材料提交前为untracked，提交后按git status核对。

- M `docs/api/user/confirm-password-reset.md`
- M `docs/api/user/request-password-reset.md`
- M `docs/api/user/verify-password-reset.md`
- M `docs/deployment/password-reset-confirmations.md`
- M `docs/deployment/password-reset-requests.md`
- M `docs/deployment/password-reset-verifications.md`
- A `docs/reviews/2026-11-10-password-reset-completion-review.md`
- A `docs/superpowers/plans/2026-11-10-password-reset-completion-plan.md`
- M `docs/superpowers/specs/2026-11-09-password-reset-completion-design.md`
- M `src/main/java/org/example/music/user/controller/PasswordResetConfirmationController.java`
- M `src/main/java/org/example/music/user/controller/PasswordResetConfirmationRequestReader.java`
- M `src/main/java/org/example/music/user/dto/PasswordResetConfirmationRequest.java`
- A `src/main/java/org/example/music/user/repository/PasswordResetCompletionQuotaStore.java`
- M `src/main/java/org/example/music/user/repository/PasswordResetConfirmationStore.java`
- M `src/main/java/org/example/music/user/repository/PasswordResetTicketStore.java`
- M `src/main/java/org/example/music/user/service/PasswordResetConfirmationService.java`
- M `src/main/java/org/example/music/user/service/PasswordResetConfirmationSnapshotReader.java`
- A `src/main/resources/redis/password-reset-completion/admit-account.lua`
- A `src/main/resources/redis/password-reset-completion/admit-ip.lua`
- D `src/main/resources/redis/password-reset-confirmation/consume.lua`
- A `src/main/resources/redis/password-reset-verification/consume.lua`
- A `src/main/resources/redis/password-reset-verification/read-by-digest.lua`
- M `src/test/java/org/example/music/user/controller/PasswordResetConfirmationControllerTest.java`
- M `src/test/java/org/example/music/user/controller/PasswordResetConfirmationRequestReaderTest.java`
- A `src/test/java/org/example/music/user/PasswordResetCompletionQuotaIntegrationTest.java`
- M `src/test/java/org/example/music/user/PasswordResetConfirmationConcurrencyIntegrationTest.java`
- M `src/test/java/org/example/music/user/PasswordResetConfirmationFailureIntegrationTest.java`
- M `src/test/java/org/example/music/user/PasswordResetConfirmationIntegrationSupport.java`
- M `src/test/java/org/example/music/user/PasswordResetConfirmationIntegrationTest.java`
- M `src/test/java/org/example/music/user/PasswordResetConfirmationStoreIntegrationTest.java`
- A `src/test/java/org/example/music/user/PasswordResetTicketConsumeIntegrationTest.java`
- A `src/test/java/org/example/music/user/PasswordResetTicketLookupIntegrationTest.java`
- M `src/test/java/org/example/music/user/PasswordResetVerificationIntegrationSupport.java`
- M `src/test/java/org/example/music/user/PasswordResetVerificationIntegrationTest.java`
- A `src/test/java/org/example/music/user/repository/PasswordResetCompletionQuotaStoreTest.java`
- M `src/test/java/org/example/music/user/repository/PasswordResetTicketStoreTest.java`
- M `src/test/java/org/example/music/user/service/PasswordResetConfirmationServiceTest.java`
- M `src/test/java/org/example/music/user/service/PasswordResetConfirmationSnapshotReaderTest.java`
- M `src/test/java/org/example/music/user/service/PasswordResetVerificationServiceTest.java`

## curl示例

先完成前两步，安全本地文件写入resetTicket/newPassword两个字段，将路径置于PASSWORD_RESET_BODY_FILE，文件及实际凭据不入Git。生产HTTPS。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-resets' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_BODY_FILE}"
```

成功为204空正文。示例输入字段与格式见API文档，实际票来自第二步响应。

## 未决问题、已确认取舍及默认项

业务未决问题为无。按用户确认的直接切换、独立额度、先消费后CAS及消费后故障不恢复策略实施。共享严格Reader提取按方案A继续排期。

实现技术细化沿设计，包括按摘要首次缺记录时的可定位边界、已知完整快照消费的半状态分类、同摘要变化503、大版本字符串及错误族边界转换。仅凭503或重试400无法判定首次是否改密，客户端依正常登录核实。跨存储间隙由原始绑定和CAS约束，原票在期限内消费后提交可跨期限。

除本轮设计NOTE-001点名的两份第二步文档，核对时发现第一步两份文档仍写“后续确认交付”，已在批准计划中列明并同步现行流程关系。第一、二步业务契约保持原规则。

## 审核反馈复核与处置

首次实现审核反馈为不涉及。本轮设计第1/3轮accepted及跨任务非阻断项逐项记录如下，原编号及分级保持。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| 本轮设计NOTE-001 非阻断 | 成立，设计审核时第二步两份文档仍描述旧组合共用预算/completion后续交付 | 认可，切换文档同步建议，无设计阻断 | 认可，明确文档清单并同步最终状态 | 已修改verify-password-reset.md及password-reset-verifications.md，记载旧请求400、两步独立额度、completion启用，另同步第一步流程说明 | 实际OpenAPI/HTTP/独立额度定向252通过，完整1973通过/0失败/0错误/0跳过；本轮独立实现审核已核实修复并关闭 |
| 前轮独立验证接口实现NOTE-001 非阻断 | 成立，两个严格Reader引擎重复，第三步设计已评估 | 认可，维护建议，原实现已accepted | 认可共享方向，用户确认方案A聚焦本接口 | 修改现有Confirmation Reader，共享引擎继续排期 | 本轮未提取引擎，未自行标记审核关闭 |

当前无已收到但未处理的实现阻断项或争议。本材料供Claude独立只读实现审核，代码修复与后续意见复核继续遵循AGENTS.md第3.1节。

## 第1/3轮独立实现审核登记

用户提供报告附件3b8c2342-8c54-4acf-be16-ba6d17921f11/已粘贴的文本.txt。审核对象main @ 20896cb，源码/测试冻结4eb9425，结论accepted。审核方逐项核对39份变更与旧测试迁移，零阻断项、无新增非阻断项、无剩余争议。

审核方独立执行$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain，退出0、BUILD SUCCESSFUL，用时17分06秒，1973通过/0失败/0错误/0跳过，MySQL8.4、Redis、MinIO均实跑。核对当前JUnit XML为172类1973项，与独立报告及本轮自验一致。

本轮设计NOTE-001（第二步文档阶段性表述）已由审核方独立核实修复并关闭。前轮独立验证接口实现NOTE-001（双Reader引擎重复）维持原非阻断分级与排期，本轮按用户确认方案A处理，未标记关闭。审核方认可真实RENAME TABLE故障夹具修正及生产异常契约。

用户已授权核实后推送。本轮仅登记审核结果，源码/测试相对4eb9425零变化，未重新运行测试。前端三步整体切换及实例版本协调按部署文档另行执行。
