# 本人再次验证身份接口：首次实现送审

日期：2026-11-03。实现审核第1/3轮已获Claude独立只读审核accepted，审核基线main@d595fef，零阻断、零非阻断。用户已授权推送；以下保留原首次送审证据，审核闭环见文末。本次不开始后续邮箱变更接口。

## 本次接口

`POST /api/v1/users/me/reauthentications`

仅PASSWORD/EMAIL_CODE再次验证本人并签发新auth_time的访问Token；不实现新邮箱变更请求、确认、refresh、注销、找回密码或调试接口。

## 需求与权限

普通有效Bearer JWT，USER/ADMIN操作本人；旧Token缺auth_time可用，不要求近期验证。ID/登录角色/版本只取AuthenticatedUserPrincipal与可信AuthenticatedSessionContext。严格UTF-8 application/json请求，PASSWORD＋password或EMAIL_CODE＋challengeId＋code二选一，未知/重复/混用/强制类型/非对象/多根均400，头/查询不能覆盖身份或方式。

## 声称实现的行为

- 局部流式绑定，字段名/token128字符、深度1、解码后键查重、严格UTF-8及EOF检查，等价Bean Validation；未改变全局ObjectMapper、HTTP媒体类型处理或其他接口绑定。原密码非全空白1—128字符、保留原值和历史非ASCII兼容，不调用hash；码为六位ASCII，challengeId规范43字符且回编码一致。
- 独立Redis准入key music:auth:reauth:user:<id>，从首次准入TIME起900000ms最多10次，所有方式/Token/设备共享。精确两字段count/resetAtMs、PEXPIREAT/PEXPIRETIME校验；拒绝不计数或续期，已准入所有结果不退款，不因成功/重发重置，不新增IP桶或降级绕过。
- 新五字段主键投影仅ID/email/passwordHash/userRole/tokenVersion，源码属性passwordHash映射SQL password_token，不查询username，不改旧授权快照。独立只读TransactionTemplate包围启动/查询/完成，BCrypt/Redis/JWT在事务外，用户表整行不写。
- PASSWORD读取并固定原摘要/验证版本，matches成功后立即采样认证Clock截断秒；最终读库先核对版本/角色，再精确比较原摘要，不以旧证明升级新版本。EMAIL_CODE只读本人既有ACTIVE并匹配绑定/原期限/摘要；错误计数按同一快照原子CAS，最多五次、第5次删除；正确匹配后立即采样，原子消费确认后最终读库复核邮箱/角色/版本，再签发。
- 四个新Lua严格回包：admit [0,0]/[1,1..900]/[2,0]；read [0,九项String]/[1]/[2]；fail [0,1..5]/[1,0]/[2,0]；consume [0]/[1]/[2]。错类型/null/非规范数值/schema/TTL损坏或确认未知503；Long.MAX_VALUE版本以文本精确比较。复用既有common.lua，不修改发送脚本/九字段协议或pending。
- ACTIVE原300000ms期限不续命，attempts0—4可用、5耗尽、超过5损坏。旧快照遇同码新挑战也不能计错或消费新ACTIVE。PASSWORD不影响ACTIVE/pending，EMAIL_CODE只消费匹配ACTIVE、不影响pending/发送额度。消费后失败不恢复、不退款、不自动重试。
- JwtTokenService专用入口用三字段JwtTokenSubject（ID/accountRole/version），原loginRole、受控method和显式verifiedAt；保留原版本/角色，新来源仅REAUTHENTICATION_PASSWORD/REAUTHENTICATION_EMAIL_CODE，公开LoginType/登录请求不扩展。原签发入口签名/声明保持，私有encode仅做必要复用，无新增legacy生产调用或now回填。
- 200/no-store严格accessToken/tokenType/expiresAt/authenticatedAt，tokenType=Bearer、无Location/user资料。认证时间来自匹配完成，不取消费/复核/签发完成时刻；原Token和其他登录保持原期限与auth_time，不撤销、不提升角色、不更新活动字段、不产生角色选择Ticket。
- 新四异常进入GlobalExceptionHandler：400 VALIDATION_FAILED、401 REAUTHENTICATION_FAILED、429 REAUTHENTICATION_RATE_LIMITED＋Retry-After、503 REAUTHENTICATION_UNAVAILABLE；复用原401/409/认证链503。未知验证/绑定提供器/签发异常安全包装后500，日志仅安全诊断，不保留原消息/cause/suppressed或秘密。

## 变更文件（含送审前untracked）

相对代码基线f20c9ec共36文件：生产Java19（既有修改3、新增16）、Lua4、新增测试7＋修改JwtTokenServiceTest1、文档5。相对计划基线0d2b6ad，原三份设计/计划/反馈文档只更新执行状态与意见落实。

```text
docs/api/user/reauthenticate-current-user.md
docs/reviews/2026-11-03-reauthentication-design-review.md
docs/reviews/2026-11-03-reauthentication-review.md
docs/superpowers/plans/2026-11-03-reauthentication-plan.md
docs/superpowers/specs/2026-11-03-reauthentication-design.md
src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
src/main/java/org/example/music/security/JwtTokenService.java
src/main/java/org/example/music/security/JwtTokenSubject.java
src/main/java/org/example/music/user/controller/ReauthenticationController.java
src/main/java/org/example/music/user/controller/ReauthenticationRequestReader.java
src/main/java/org/example/music/user/domain/ReauthenticationChallengeSnapshot.java
src/main/java/org/example/music/user/domain/ReauthenticationMethod.java
src/main/java/org/example/music/user/domain/UserReauthenticationSnapshot.java
src/main/java/org/example/music/user/dto/ReauthenticationRequest.java
src/main/java/org/example/music/user/dto/ReauthenticationResponse.java
src/main/java/org/example/music/user/exception/ReauthenticationDiagnostics.java
src/main/java/org/example/music/user/exception/ReauthenticationFailedException.java
src/main/java/org/example/music/user/exception/ReauthenticationRateLimitedException.java
src/main/java/org/example/music/user/exception/ReauthenticationUnavailableException.java
src/main/java/org/example/music/user/exception/ReauthenticationValidationException.java
src/main/java/org/example/music/user/repository/ReauthenticationStore.java
src/main/java/org/example/music/user/repository/UserRepository.java
src/main/java/org/example/music/user/service/ReauthenticationService.java
src/main/java/org/example/music/user/service/ReauthenticationSnapshotReader.java
src/main/resources/redis/reauthentication/admit.lua
src/main/resources/redis/reauthentication/consume.lua
src/main/resources/redis/reauthentication/fail.lua
src/main/resources/redis/reauthentication/read.lua
src/test/java/org/example/music/security/JwtTokenServiceTest.java
src/test/java/org/example/music/user/ReauthenticationIntegrationTest.java
src/test/java/org/example/music/user/ReauthenticationStoreIntegrationTest.java
src/test/java/org/example/music/user/controller/ReauthenticationControllerTest.java
src/test/java/org/example/music/user/controller/ReauthenticationRequestReaderTest.java
src/test/java/org/example/music/user/repository/ReauthenticationStoreTest.java
src/test/java/org/example/music/user/service/ReauthenticationServiceTest.java
src/test/java/org/example/music/user/service/ReauthenticationSnapshotReaderTest.java
```

原工作区干净，无排除的用户未提交文件；本任务所有新增文件均纳入提交。此前本机Redis排障的.env仍被Git忽略，本任务未重配或重启本地服务。

## 数据库、安全配置与API文档

迁移：无，V1—V8未动。依赖/配置/Compose/SecurityConfig/认证过滤链/公开LoginType/既有登录业务/邮件发送/common.lua/PasswordHasher均未改。既有生产修改仅UserRepository新增六行投影、JwtTokenService专用入口及必要私有encode复用、GlobalExceptionHandler新增四映射。

API：[reauthenticate-current-user.md](../api/user/reauthenticate-current-user.md)，OpenAPI同步。部署条件引用既有[邮件说明](../deployment/reauthentication-email-requests.md)和[认证时间说明](../deployment/auth-time-binding.md)，没有新环境配置项或生产调试入口。

## 实际测试

PowerShell实际完整命令：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
cmd /c gradlew.bat cleanTest check --console=plain
```

2026-11-03本次BUILD SUCCESSFUL，exit0，10分36秒。103类、1285通过/0失败/0错误/0跳过；新XML统一生成于22:19:50（UTC+08:00），未复用旧结果。MySQL8.4、Redis8.10.2、MinIO容器全部实跑，跳过项：无，失败摘要：无。相对1105基线新增180项、7个测试类。

| 本任务类 | 通过 | 失败/错误/跳过 |
| --- | --- | --- |
| ReauthenticationRequestReaderTest | 46 | 0/0/0 |
| ReauthenticationStoreTest | 13 | 0/0/0 |
| ReauthenticationStoreIntegrationTest | 24 | 0/0/0 |
| ReauthenticationSnapshotReaderTest | 3 | 0/0/0 |
| JwtTokenServiceTest | 6（新增2、保留原4） | 0/0/0 |
| ReauthenticationServiceTest | 18 | 0/0/0 |
| ReauthenticationControllerTest | 11 | 0/0/0 |
| ReauthenticationIntegrationTest | 63 | 0/0/0 |
| 合计 | 184（新增180、原4回归） | 0/0/0 |

另实际运行各任务定向测试，以及`test --tests "*Reauthentication*" --tests "*JwtTokenServiceTest" --tests "*AuthTimeBindingIntegrationTest" --tests "*AuthVersionBindingIntegrationTest" --tests "*PasswordChangeIntegrationTest" --tests "*EmailSendCodeIntegrationTest" --console=plain`，3分11秒、20类428通过/0失败/0错误/0跳过。该次之后补充两项Long.MAX_VALUE端到端用例，均已纳入上述最终全量；定向结果不替代cleanTest check。

验证重点：严格解码/128界限/重复转义键/多根/媒体类型；短、非ASCII、首尾空格密码及128字符输入遵守既有matches行为；新旧真实签名Token与角色矩阵、伪造身份字段/头/参数、坏声明/撤销；实际发送HTTP生成ACTIVE后兑换，五错/共享额度/同码替换/单次并发消费/第五错先于正确匹配；真实DB改摘要/版本/角色/邮箱/删除及并发改名，整行不变；匹配后立即采样、消费和查询延迟不刷新，旧Token原auth_time保持；Long.MAX_VALUE实际发送/签发/过滤链；实际Lua执行前故障与执行后丢确认区分；消费后DB/签发/响应写失败不恢复；最终复核后真实版本递增，实际过滤链拒绝新签出的原版本Token。

Redis期限用真实过期指令和合法夹具，不用Java Clock冒充Redis TIME；并发用限时闩锁/future及finally清理，无sleep或生产时间覆盖参数。SMTP仅控制专用邮件传输边界，不声称真实外部邮箱送达。响应写失败使用test-only Filter注入IO错误，证明实际消费/签发后不恢复，不声称模拟所有真实网络丢包情形。

过程记录：Reader/Store/SnapshotReader/JWT/Service/Controller首次红灯为缺新类的编译失败，不能称作业务断言先红；Service测试另有Class泛型条件表达式编译错误，已修正。真实Redis及部分集成用例在同阶段补全，未逐条严格先测后实现。首轮真实集成61项有2项失败，发生在时间用例对Spring Data接口调用callRealMethod；按项目已有AuthTimeBindingIntegrationTest的默认Answer委托方式修正，继续执行真实查询，未放宽断言或改生产逻辑。后续428定向及1285全量均通过。

构建关闭阶段出现缓存连接池对已关闭MySQL连接的告警，位于shutdown hook；Gradle及XML均无失败。本任务未扩大范围调整共享测试生命周期。

## Git基线与实现提交

- 代码基线：main @ f20c9ec，本地origin/main跟踪点；设计2a69c48，设计回执/计划0d2b6ad。
- 0837dfb：请求局部严格绑定及脱敏DTO。
- fb94e89：固定额度、ACTIVE读取/计错/消费及真实Redis测试。
- c6670dc：五字段主键投影、独立只读快照及真实MySQL基础设施。
- fc413d8：三字段JWT身份、专用签发及新旧入口测试。
- ac31ab9：凭据验证/时间采样/最终复核与签发协调。
- 96aaea8：唯一HTTP端点、四异常映射、绑定器未知故障安全包装及HTTP/隐私测试。
- bbc0084：真实发送到再次验证、并发/故障/时钟及Long.MAX_VALUE完整链路测试。
- 本材料所在提交仅API与审核/设计/计划文档；全量后不再改运行时代码或测试，未推送。

## curl示例

```powershell
'{"method":"PASSWORD","password":"example-password"}' | curl.exe --request POST 'http://localhost:8080/api/v1/users/me/reauthentications' --header "Authorization: Bearer $env:ACCESS_TOKEN" --header 'Content-Type: application/json' --data-binary '@-'
```

两种请求及POSIX示例见API文档；均为虚构凭据，真实Token/密码/码不得入日志。

## 审核反馈复核与处置

首次实现审核：不涉及旧实现反馈。设计审核原编号及分级保留：

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断，设计§6原103行username无用途 | 成立：本次验证/响应/JWT编码不需要username，原编码只取ID/角色/版本 | 认可，不作为原设计通过的额外条件 | 采纳删除，不以增加用户名日志制造用途；原报告允许此微调无需重审 | 新投影/record精确五字段，无username；新JwtTokenSubject仅ID/账户角色/版本，旧UserAuthorizationSnapshot及登录响应不变，无null占位或新增用户名日志 | 真实MySQL投影与整行不变、并发改名不影响ID身份、JWT角色/版本/时间及完整链路均通过，1285/0/0/0、103类；已实现待Claude独立复核，不自行替代审核关闭 |

设计报告C7提及502为文字笔误，已按设计§9沿无502的错误矩阵实施，无业务争议或待人类裁定事项。未改变问题原分级或新增审核轮次。

## 未决问题、默认项与边界

无待决定的业务规则。请求局部媒体类型处理及128上限、固定额度、时间、来源值、五次错误、失败不恢复等均按已确认设计。仅新接口及其必要组件，不开始邮箱变更后续端点。

代码自验不代表独立accepted或生产部署：代理共享桶/SMTP/Redis权限及版本/全实例认证加固/严格时钟同步仍须部署验收；本机此前调通Redis不替代这些条件。最终复核非原子、消费/响应未知可能已生效、旧Token不自动获得新auth_time，API文档已明确，不掩盖不确定性或提供补偿假成功。

## 独立审核结果与Codex复核

用户提供Claude对main@d595fef的第1/3轮实现审核：accepted，零阻断、零非阻断、无剩余争议。审核者独立执行MINIO_INTEGRATION_TESTS=true的cleanTest check --console=plain，1285通过/0失败/0错误/0跳过、103类；本任务8类184项（新增180＋原4回归）逐类与上述自验一致，MySQL/Redis/MinIO均实跑。

按AGENTS.md §3.1复核：无新问题需要修复；核对五字段JPQL、三字段JwtTokenSubject及未改配置/迁移/公开枚举等事实，认可结论。设计NOTE-001由Claude独立核实后关闭，上表“待Claude复核”由本结论覆盖，原编号和非阻断分级保留。设计报告C7的502笔误也已由审核方确认，不构成实现问题。

本闭环提交只更新设计/计划/审核记录，不改代码、测试或构建输入，不为状态登记重复跑全量。用户已授权推送已审核提交及本记录；本机.env仍不纳入Git。代码accepted不代表代理、SMTP、Redis权限、全实例升级及同步时钟等生产环境验收完成，也不宣告改邮箱或refresh能力已经交付。
