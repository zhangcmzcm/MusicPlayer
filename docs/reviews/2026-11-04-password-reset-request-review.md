# 找回密码申请及异步发码接口实现审核材料

日期：2026-11-05。文档文件名沿用已批准计划日期。阶段：实现与本地全量自验完成，首次独立实现审核材料已准备，尚未获得本接口独立实现审核accepted；不开始重置确认接口，不自动推送。

## 本次接口与权限

唯一`POST /api/v1/users/password-reset-requests`，公开，无需旧密码、Token或auth_time。USER/ADMIN同规则，前端仅提供email，不能指定账户ID、角色、用途或认证版本。主动附带无效Bearer仍由现有真实认证链拒绝，未扩大其他路径权限。后续验证码确认及密码更新另行设计，本接口不更新用户表、撤销登录或签发Token。

## 声称实现的行为

- 仅email、严格JSON/UTF-8/重复转义键/16384字节预算/415；规范化trim+Locale.ROOT小写、最长120、严格单地址。仅邮箱唯一索引等值查询后Java精确核验：大小写可匹配，重音不同不误发；NO PAD历史首尾空格按无匹配。
- Redis独立邮箱60秒1次/UTC日10次、IP固定60秒5次，先扣额后查询；所有邮箱同样入队占容量、同格式202及no-store。不承诺数据库等时或送达，不返回账户存在性/内部状态；quota拒绝不增不续，Retry-After取最大等待，后续失败不退款。
- 32字节随机challengeId/43字符规范Base64URL，Redis只存其摘要；Redis TIME准入起300秒固定deadline。受理快照包含本人ID、原始邮箱/角色/版本，不保存密码/验证码明文到任务。
- 单Redis有界队列默认1000未结束任务，每实例4并发、20秒SMTP等待；后台原子一次领取，未知领取不发送、无租约重投；队列满503不部分替换pending或驱逐其他任务。最新申请按准入时间排序，迟到老请求不能覆盖新任务，完成后仍保留水位至deadline。
- 最新pending与ACTIVE分离；旧未领取任务替换可释放容量，旧已领取任务失格仍占至结束/过期。发信前/激活前真实数据库复核；码仅BCrypt摘要，发送明确成功再原子激活替换旧ACTIVE，原期限不变，attempts=0供后续确认使用。
- SMTP未知/失败/超时不激活不重发，原有效码保留原期限；激活回包未知时新码可能已生效，不恢复旧码或清新ACTIVE。finish/reap只能处理自身归属，原索引按期限有界清理；不提供人工删键放行或公开恢复接口。
- 后台MDC关联受理traceId并恢复线程上下文，专用邮件/异常链/toString脱敏、专用SMTP客户端不影响其他用途。默认生产自动启动，测试profile显式控制调度，单轮异常不使后续调度永久停止。
- 最终DB复核与Redis激活非原子，期间凭据变更时ACTIVE保留旧版本，不重绑新版本；后续确认必须再核对并CAS，不宣称本轮已实现消费、第五次错误或密码更新。

## 基线、文档与工程变更

- 分支main，代码基线0705928；设计83bab37、第1/3轮设计审核accepted；计划及建议处置f96668e。
- 实现提交：b35a2aa、4d86f39、1bca5f8、e57c91c、6f1caa4、43f5952、57280e3；真实链路及最终代码冻结提交6a2881b，后续仅文档。独立实现审核范围为当前main相对0705928的全部本任务变更（含下列完整文件清单）。
- API：docs/api/user/request-password-reset.md；部署：docs/deployment/password-reset-requests.md。
- 设计/计划：docs/superpowers/specs/2026-11-04-password-reset-request-design.md、docs/superpowers/plans/2026-11-04-password-reset-request-plan.md。
- 数据库迁移：无，V1—V8未修改；无新依赖/Compose/Entity/密码策略/JWT/既有生产者脚本变更。SecurityConfig仅追加单POST公开路径，UserRepository仅追加两条最小投影，GlobalExceptionHandler追加专用映射。
- 配置：application.properties及.env.example追加3项本功能默认值；测试application-test.properties追加worker-auto-start=false，生产默认true，不弱化既有认证或容器条件。此文件已明确纳入范围。

## 实际验证

各任务先观察缺实现编译红灯，再实现并定向验证；Docker条件保留，未使用H2、未mock Redis/MySQL成功代替真实写入。SMTP为可控sender，外部存储与Lua均真实运行。

真实HTTP主链首轮14项：13通过/1失败/0错误/0跳过，失败是测试匿名读取原已受保护/v3/api-docs得到401；修正夹具以真实签名Token读取，不改安全白名单，不移除Filter。随后主链/并发/故障三类通过；全新接口定向一次BUILD SUCCESSFUL（1分59秒）。补齐损坏/UTC日/自然过期/真实JWT到期等边界后的最终定向为：

```powershell
cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordReset*Test" --console=plain
```

结果：退出0，BUILD SUCCESSFUL，2分1秒；19类119通过/0失败/0错误/0跳过。以下数量来自该次实际JUnit XML：

| 新测试类 | 通过 |
| --- | ---: |
| PasswordResetAdmissionIntegrationTest | 11 |
| PasswordResetQueueIntegrationTest | 18 |
| PasswordResetRequestConcurrencyIntegrationTest | 5 |
| PasswordResetRequestFailureIntegrationTest | 13 |
| PasswordResetRequestIntegrationTest | 15 |
| PasswordResetSnapshotIntegrationTest | 4 |
| PasswordResetPropertiesTest | 1 |
| PasswordResetRequestControllerTest | 2 |
| PasswordResetRequestReaderTest | 31 |
| PasswordResetEmailRulesTest | 1 |
| PasswordResetIdentifiersTest | 1 |
| BoundedPasswordResetEmailDeliveryTest | 5 |
| IsolatedPasswordResetMailClientFactoryTest | 2 |
| SmtpPasswordResetEmailSenderTest | 2 |
| PasswordResetRequestStoreTest | 1 |
| PasswordResetRequestServiceTest | 1 |
| PasswordResetSnapshotReaderTest | 1 |
| PasswordResetTaskProcessorTest | 4 |
| PasswordResetWorkerLifecycleTest | 1 |

本次最终全量基准已完成，固定对象6a2881b，后续只有文档变更：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

实际结果：退出码0，BUILD SUCCESSFUL，14分10秒；JUnit XML聚合145类、1718通过/0失败/0错误/0跳过，其中新接口19类119项，既有126类1599项全部运行。MySQL8.4、Redis及MinIO测试实跑，跳过项：无。数量依据build/test-results/test/TEST-*.xml逐份汇总，不以历史1599或定向119代替。容器关闭阶段出现既有连接池关闭连接告警，未计作测试通过证据；构建退出与完整XML均已核对成功。关键证据包括：真实Redis后丢admit/enqueue/claim/prepare/activate/finish/reap回复；两个真实Lifecycle并发领取；真实任务deadline自然过期；实际数据库事务开始/读取/完成故障、版本/邮箱/角色/删除交错；真实JWT签名/过期/撤销拒绝与生成OpenAPI契约。

## 默认项、部署前提与执行调整

单Redis、Redis≥7及持久化/容量/时钟同步前提见部署文档。Redis任务不保证故障后永久保存；SMTP最多一次尝试不保证邮件恰好一次送达。202/503或丢回复不能独立证明邮件是否发送；冷却后可重新申请。本轮找回申请独立额度，未改变既有通用验证码行为。

计划内默认1000/4/20s，内部轮询250ms、维护每秒100个。新增Redis与主链抽象夹具防止测试继承重复用例；测试profile关闭后台自动启动，实际业务Processor仍真实，调度用例显式启动两个Lifecycle，不是mock线程领取代替验证。新traceId规则对齐现有Filter允许点号，未改变公共Filter。

## 审核反馈复核与处置

首次本接口独立实现审核，不涉及既往实现阻断项；不把设计accepted或前一改邮箱accepted作为本次实现结论。设计审核第1/3轮的两条非阻断意见沿用编号，处置如下，待独立实现核实落地：

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断 | 成立；V1 NO PAD与索引等值查询下，首尾空格历史地址不能匹配trim输入 | 认可，文档口径问题非设计阻断 | 认可，保持索引、不做全表扫描 | 设计§4、API、部署说明已修正；Reader/Store无暗中扩大查询 | 真实MySQL首尾空格/大小写/重音测试定向通过；待独立复核 |
| NOTE-002 非阻断 | 成立；公开路径不绕过主动Bearer校验 | 认可，表格补全非新增业务限制 | 认可，403只沿用，不制造正常禁止角色 | 设计§8、API/OpenAPI已补401/403与AUTH_STATE_STORE_UNAVAILABLE说明 | 真实无Token202及签名/到期/版本撤销401测试、OpenAPI8响应码定向通过；待独立复核 |

当前无关键业务争议。收到独立实现报告按AGENTS.md保留其编号/分级/轮次逐项复核，不自行撤销阻断项。

## curl

```bash
curl -i --request POST 'http://localhost:8080/api/v1/users/password-reset-requests' \
  --header 'Content-Type: application/json' \
  --data '{"email":"alice@example.com"}'
```

示例为虚构邮箱，无需Token。生产使用HTTPS，不记录请求体/完整申请标识。

## 变更文件（含untracked）

以下为相对0705928的全部任务文件，包含本材料本身；不包含构建输出、.env或凭据：

- .env.example
- docs/api/user/request-password-reset.md
- docs/deployment/password-reset-requests.md
- docs/reviews/2026-11-04-password-reset-request-review.md
- docs/superpowers/plans/2026-11-04-password-reset-request-plan.md
- docs/superpowers/specs/2026-11-04-password-reset-request-design.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/main/java/org/example/music/config/SecurityConfig.java
- src/main/java/org/example/music/user/config/PasswordResetProperties.java
- src/main/java/org/example/music/user/controller/PasswordResetRequestController.java
- src/main/java/org/example/music/user/controller/PasswordResetRequestReader.java
- src/main/java/org/example/music/user/domain/PasswordResetAccountSnapshot.java
- src/main/java/org/example/music/user/domain/PasswordResetAdmission.java
- src/main/java/org/example/music/user/domain/PasswordResetClaim.java
- src/main/java/org/example/music/user/domain/PasswordResetEmailRules.java
- src/main/java/org/example/music/user/domain/PasswordResetIdentifiers.java
- src/main/java/org/example/music/user/domain/PasswordResetTask.java
- src/main/java/org/example/music/user/dto/PasswordResetRequest.java
- src/main/java/org/example/music/user/dto/PasswordResetRequestResponse.java
- src/main/java/org/example/music/user/email/BoundedPasswordResetEmailDelivery.java
- src/main/java/org/example/music/user/email/IsolatedPasswordResetMailClientFactory.java
- src/main/java/org/example/music/user/email/PasswordResetEmailSender.java
- src/main/java/org/example/music/user/email/PasswordResetMessage.java
- src/main/java/org/example/music/user/email/SmtpPasswordResetEmailSender.java
- src/main/java/org/example/music/user/exception/PasswordResetDiagnostics.java
- src/main/java/org/example/music/user/exception/PasswordResetRequestRateLimitedException.java
- src/main/java/org/example/music/user/exception/PasswordResetRequestUnavailableException.java
- src/main/java/org/example/music/user/exception/PasswordResetSendFailedException.java
- src/main/java/org/example/music/user/exception/PasswordResetUnsupportedMediaTypeException.java
- src/main/java/org/example/music/user/exception/PasswordResetValidationException.java
- src/main/java/org/example/music/user/repository/PasswordResetRequestStore.java
- src/main/java/org/example/music/user/repository/UserRepository.java
- src/main/java/org/example/music/user/service/PasswordResetRequestService.java
- src/main/java/org/example/music/user/service/PasswordResetSnapshotReader.java
- src/main/java/org/example/music/user/service/PasswordResetTaskProcessor.java
- src/main/java/org/example/music/user/service/PasswordResetWorkerLifecycle.java
- src/main/resources/application.properties
- src/main/resources/redis/password-reset-request/activate.lua
- src/main/resources/redis/password-reset-request/admit.lua
- src/main/resources/redis/password-reset-request/claim.lua
- src/main/resources/redis/password-reset-request/common.lua
- src/main/resources/redis/password-reset-request/enqueue.lua
- src/main/resources/redis/password-reset-request/finish.lua
- src/main/resources/redis/password-reset-request/prepare.lua
- src/main/resources/redis/password-reset-request/reap.lua
- src/test/java/org/example/music/user/PasswordResetAdmissionIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetQueueIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetRedisIntegrationSupport.java
- src/test/java/org/example/music/user/PasswordResetRequestConcurrencyIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetRequestFailureIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetRequestIntegrationSupport.java
- src/test/java/org/example/music/user/PasswordResetRequestIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetSnapshotIntegrationTest.java
- src/test/java/org/example/music/user/config/PasswordResetPropertiesTest.java
- src/test/java/org/example/music/user/controller/PasswordResetRequestControllerTest.java
- src/test/java/org/example/music/user/controller/PasswordResetRequestReaderTest.java
- src/test/java/org/example/music/user/domain/PasswordResetEmailRulesTest.java
- src/test/java/org/example/music/user/domain/PasswordResetIdentifiersTest.java
- src/test/java/org/example/music/user/email/BoundedPasswordResetEmailDeliveryTest.java
- src/test/java/org/example/music/user/email/IsolatedPasswordResetMailClientFactoryTest.java
- src/test/java/org/example/music/user/email/SmtpPasswordResetEmailSenderTest.java
- src/test/java/org/example/music/user/repository/PasswordResetRequestStoreTest.java
- src/test/java/org/example/music/user/service/PasswordResetRequestServiceTest.java
- src/test/java/org/example/music/user/service/PasswordResetSnapshotReaderTest.java
- src/test/java/org/example/music/user/service/PasswordResetTaskProcessorTest.java
- src/test/java/org/example/music/user/service/PasswordResetWorkerLifecycleTest.java
- src/test/resources/application-test.properties
