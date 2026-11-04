# 新邮箱变更验证码申请接口实现审核材料

日期：2026-11-04。阶段：本地实现与自验完成，首次独立实现送审；设计已获第1/3轮accepted，设计轮次与实现审核区分。用户已提供Claude对c49b8fc的第1/3轮实现审核accepted，零阻断；原始送审证据保留如下，审核结论与本轮非阻断处置见末尾。用户已授权收尾后推送，当前不开始下一个接口。

## 本次接口

POST /api/v1/users/me/email-change-requests，唯一新增HTTP端点。

## 需求与权限

有效Bearer JWT的USER/ADMIN仅操作本人；允许未绑定用户首次绑定。服务端可信auth_time距准入不超过300秒（含端点），缺失/超窗401 REAUTHENTICATION_REQUIRED；普通认证过期/撤销沿用原契约。身份、角色和认证版本来自可信上下文，不接受客户端指定本人ID。

## 声称实现的行为

- 唯一newEmail字符串字段，原CR/LF及孤立UTF-16代理字符拒绝，trim/Locale.ROOT小写后合法单地址、最长120字符；严格JSON/UTF-8/重复字段校验；含尾随空白的正文最多16384字节。近期门禁先于正文解析，后续发送耗时不重复计算门禁。
- 与本人邮箱按规范化或MySQL唯一排序规则等同返回409 EMAIL_UNCHANGED，其他账户占用409 EMAIL_ALREADY_EXISTS；不预占新邮箱。
- 用户/目标邮箱各60秒一次、UTC日10次，IP固定60秒5次；Redis七键全预检后原子准入及计数，拒绝不部分扣额，准入后失败不退款。
- 独立用途、私有Redis状态，绑定本人/原邮箱或未绑定标记/新邮箱/认证版本。6位ASCII码仅存BCrypt，32随机字节challengeId只存摘要。每人一个ACTIVE/一个pending，发信及绑定复核后CAS激活成功才返回201；新申请成功才替换旧申请。
- Redis准入时间加300秒绝对到期，pending租约60秒，不续命；SMTP独立4槽/零队列/20秒等待、各网络超时5000ms。迟到任务无激活能力，明确激活前失败保留旧ACTIVE，激活结果未知503不承诺旧码保留、不恢复旧状态。
- 本接口不写用户表、不签发Token、不刷新auth_time、不递增版本、不撤销登录。最终确认/refresh均未实现，未来最终更新后的全部登录撤销仅保留设计约束。
- 邮件/状态/DTO日志脱敏，失败输出ProblemDetail及稳定code/type，日志保留traceId；415仅本接口专用异常映射，不改变旧端点媒体类型处理。

## 数据库迁移与依赖

无迁移，V1—V8未修改；无新增依赖、配置、Compose或Actuator暴露变更。UserRepository仅新增四字段账户投影与邮箱ownerId查询；GlobalExceptionHandler仅追加本用途专用映射，既有映射和problem构造规则不变。

## 文档与基线

- API：docs/api/user/request-email-change.md。
- 部署：docs/deployment/email-change-requests.md。
- 设计：docs/superpowers/specs/2026-11-03-email-change-request-design.md。
- 计划：docs/superpowers/plans/2026-11-04-email-change-request-plan.md。
- 设计反馈：docs/reviews/2026-11-04-email-change-request-design-review.md。
- main；代码基线36cf271，设计6f29752、计划076415d；实现任务提交d66ef55/feed686/a2d6b1d/f3aaa1f/b174c81/5d13abb（含编码边界修复及真实链路测试）。审核范围取当前main相对36cf271的本接口全部变更，含上述文档，不以单个最后提交缩小范围。

## 实际验证

- 请求/门禁、存储、邮件、协调、HTTP入口各任务均先观察缺实现编译失败，再实现并执行定向命令。邮件文案断言和Mockito重设桩方式曾各失败一次，修正后同范围重跑通过，未删除失败场景或跳过断言。
- 编码边界补充前的真实链路命令：cmd /c gradlew.bat test --tests "org.example.music.user.EmailChangeRequestIntegrationTest" --tests "org.example.music.user.EmailChangeRequestStoreIntegrationTest" --console=plain；47项HTTP/MySQL/Redis/真实JWT及29项Redis脚本测试，共76通过，0失败，0错误，0跳过。
- 最终全量：设置MINIO_INTEGRATION_TESTS=true，运行.\gradlew.bat cleanTest check --console=plain。首次沙箱内调用被Gradle Wrapper锁文件权限拒绝，未开始构建；获准以同一命令沙箱外重跑，已进入测试。首轮全量BUILD SUCCESSFUL（11分37秒），114类、1441通过/0失败/0错误/0跳过。随后依赖源码检查发现孤立UTF-16代理字符可被邮箱校验接受，新增回归实证39项中36通过/3失败（输入高/低代理各1、存量地址1），已在本接口两处校验显式拒绝，并增加真实HTTP前置拒绝用例；修复后受影响范围87通过/0失败/0错误/0跳过（Reader 30、SnapshotReader 9、真实HTTP链路48）。最终同命令全量重跑BUILD SUCCESSFUL（11分15秒），114类、1445通过/0失败/0错误/0跳过；全部必要容器实跑，跳过项：无。最终结果对应代码及测试5d13abb，后续仅提交审核/执行文档。新接口11类160项，既有103类1285项，合计与XML一致。收尾可见容器停止后的连接池告警，Gradle退出码0、JUnit失败/错误均0；不修改无关测试或关闭行为来隐藏告警。

最终实际执行命令：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

新接口逐类结果（均来自本次最终JUnit XML）：

| 类 | 通过 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| user.controller.EmailChangeRequestControllerTest | 5 | 0 | 0 | 0 |
| user.controller.EmailChangeRequestReaderTest | 30 | 0 | 0 | 0 |
| user.email.BoundedEmailChangeEmailDeliveryTest | 5 | 0 | 0 | 0 |
| user.email.IsolatedEmailChangeMailClientFactoryTest | 2 | 0 | 0 | 0 |
| user.email.SmtpEmailChangeEmailSenderTest | 2 | 0 | 0 | 0 |
| user.EmailChangeRequestIntegrationTest | 48 | 0 | 0 | 0 |
| user.EmailChangeRequestStoreIntegrationTest | 29 | 0 | 0 | 0 |
| user.repository.EmailChangeRequestStoreTest | 13 | 0 | 0 | 0 |
| user.service.EmailChangeRecentAuthenticationGuardTest | 3 | 0 | 0 | 0 |
| user.service.EmailChangeRequestServiceTest | 14 | 0 | 0 | 0 |
| user.service.EmailChangeSnapshotReaderTest | 9 | 0 | 0 | 0 |

## curl

```bash
curl -i -X POST 'http://localhost:8080/api/v1/users/me/email-change-requests' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"newEmail":"new@example.com"}'
```

ACCESS_TOKEN应为本人有效且满足近期资格的凭据，示例不包含真实Token。

## 默认项与交付边界

用户已批准本端点16384字节正文上限。沿用remoteAddr、单Redis假设及既有SMTP/TLS/发件配置；代理汇聚IP会共享额度。真实SMTP收件箱投递、代理容量和网关超时仍需部署验收，受控邮件测试不冒充外部送达。激活确认或响应丢失时存在已替换旧申请的可能，客户端冷却后重新申请。

## 审核反馈复核与处置

首次实现审核：不涉及既往实现阻断项。设计阶段NOTE-001沿用原编号和非阻断分级，已核实事实与方向，处置见设计反馈表：新增UNSUPPORTED_MEDIA_TYPE及专用advice，固定小写下划线type，真实HTTP/OpenAPI测试验证，旧读取器仍400；已实现待Claude复核，不自行宣布审核方关闭。审核报告C13的challengeId概述笔误按原201两字段契约处理，验证码不返回、challengeId返回，二者均不入日志。

## 变更文件（含untracked）

以下为本任务相对36cf271的文件清单，包含本材料自身。未列无关文件，不包含密钥或本地构建输出：

- docs/api/user/request-email-change.md
- docs/deployment/email-change-requests.md
- docs/reviews/2026-11-04-email-change-request-design-review.md
- docs/reviews/2026-11-04-email-change-request-review.md
- docs/superpowers/plans/2026-11-04-email-change-request-plan.md
- docs/superpowers/specs/2026-11-03-email-change-request-design.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/main/java/org/example/music/user/controller/EmailChangeRequestController.java
- src/main/java/org/example/music/user/controller/EmailChangeRequestReader.java
- src/main/java/org/example/music/user/domain/EmailChangeAccountSnapshot.java
- src/main/java/org/example/music/user/domain/EmailChangeBinding.java
- src/main/java/org/example/music/user/domain/EmailChangeReservation.java
- src/main/java/org/example/music/user/dto/EmailChangeRequest.java
- src/main/java/org/example/music/user/dto/EmailChangeRequestResponse.java
- src/main/java/org/example/music/user/email/BoundedEmailChangeEmailDelivery.java
- src/main/java/org/example/music/user/email/EmailChangeEmailSender.java
- src/main/java/org/example/music/user/email/EmailChangeMessage.java
- src/main/java/org/example/music/user/email/IsolatedEmailChangeMailClientFactory.java
- src/main/java/org/example/music/user/email/SmtpEmailChangeEmailSender.java
- src/main/java/org/example/music/user/exception/EmailChangeDiagnostics.java
- src/main/java/org/example/music/user/exception/EmailChangeEmailAlreadyExistsException.java
- src/main/java/org/example/music/user/exception/EmailChangeRateLimitedException.java
- src/main/java/org/example/music/user/exception/EmailChangeRequestConflictException.java
- src/main/java/org/example/music/user/exception/EmailChangeRequestUnavailableException.java
- src/main/java/org/example/music/user/exception/EmailChangeSendFailedException.java
- src/main/java/org/example/music/user/exception/EmailChangeUnsupportedMediaTypeException.java
- src/main/java/org/example/music/user/exception/EmailChangeValidationException.java
- src/main/java/org/example/music/user/exception/EmailUnchangedException.java
- src/main/java/org/example/music/user/exception/ReauthenticationRequiredException.java
- src/main/java/org/example/music/user/repository/EmailChangeRequestStore.java
- src/main/java/org/example/music/user/repository/UserRepository.java
- src/main/java/org/example/music/user/service/EmailChangeRecentAuthenticationGuard.java
- src/main/java/org/example/music/user/service/EmailChangeRequestService.java
- src/main/java/org/example/music/user/service/EmailChangeSnapshotReader.java
- src/main/resources/redis/email-change/activate.lua
- src/main/resources/redis/email-change/cancel.lua
- src/main/resources/redis/email-change/common.lua
- src/main/resources/redis/email-change/prepare.lua
- src/main/resources/redis/email-change/reserve.lua
- src/test/java/org/example/music/user/EmailChangeRequestIntegrationTest.java
- src/test/java/org/example/music/user/EmailChangeRequestStoreIntegrationTest.java
- src/test/java/org/example/music/user/controller/EmailChangeRequestControllerTest.java
- src/test/java/org/example/music/user/controller/EmailChangeRequestReaderTest.java
- src/test/java/org/example/music/user/email/BoundedEmailChangeEmailDeliveryTest.java
- src/test/java/org/example/music/user/email/IsolatedEmailChangeMailClientFactoryTest.java
- src/test/java/org/example/music/user/email/SmtpEmailChangeEmailSenderTest.java
- src/test/java/org/example/music/user/repository/EmailChangeRequestStoreTest.java
- src/test/java/org/example/music/user/service/EmailChangeRecentAuthenticationGuardTest.java
- src/test/java/org/example/music/user/service/EmailChangeRequestServiceTest.java
- src/test/java/org/example/music/user/service/EmailChangeSnapshotReaderTest.java


## 独立实现审核与非阻断建议闭环

用户提供Claude对main截至c49b8fc的第1/3轮实现审核accepted，零阻断；独立执行MINIO_INTEGRATION_TESTS=true的cleanTest check，BUILD SUCCESSFUL（10分54秒，退出码0），114类1445通过/0失败/0错误/0跳过，MySQL/Redis/MinIO实跑。本段为用户提供的独立证据，不冒称Codex本轮执行。NOTE-001已由Claude核实关闭，C13的challengeId笔误也由审核方确认更正。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断，沿用设计阶段编号 | 专用415异常/advice、下划线type、真实HTTP/OpenAPI及旧读取器400回归均获独立核实 | 认可原非阻断分级 | 采纳且已落实 | 审核方已明确关闭；无需重复修改 | 独立全量1445/0/0/0，114类 |
| NOTE-002 非阻断，GlobalExceptionHandler原138行502日志标签 | 成立：502与503原先均记录Email change request unavailable；exceptionType与HTTP响应正确，不影响接口业务 | 认可，不作为accepted的附加条件 | 采纳，仅区分事件文案，不改响应、日志级别或脱敏 | 本轮将502标签改为Email change send failed，503保持原标签 | Controller 5项及真实HTTP链路48项均通过；日志脱敏、502/503响应回归正常 |
| NOTE-003 非阻断，EmailChangeRequestIntegrationTest原104行IP断言 | 回归防护不足成立：原断言只检查没有明文127.0.0.1，不能区分真实IP与伪造转发头的摘要。原断言对明文键回归仍有作用，“恒真”仅限当前摘要实现前提 | 认可，现有remoteAddr-only实现正确，不是越权或限流实现缺陷 | 采纳加强方向，显式控制真实连接IP及伪造X-Forwarded-For，检查两者摘要键 | 本轮断言仅真实IP摘要键存在、count=1，伪造IP摘要键不存在；覆盖USER/ADMIN及保留USER登录角色的ADMIN账户三种场景 | 同一真实MySQL/Redis链路测试48项通过，无跳过；原3个参数化成功场景保留，未删减测试 |

本轮实际运行：`cmd /c gradlew.bat test --tests "org.example.music.user.controller.EmailChangeRequestControllerTest" --tests "org.example.music.user.EmailChangeRequestIntegrationTest" check --console=plain`，BUILD SUCCESSFUL（52秒），2类53通过/0失败/0错误/0跳过。此命令对test使用筛选后执行check，是受影响范围验证，不是再次全量1445项；符合本次accepted报告关于两处非阻断收尾只需重跑受影响测试的要求。最新已独立验证的全量仍为c49b8fc上的1445/0/0/0，不把历史全量冒充本轮收尾结果。

报告表述复核：C2“403逐一测试断言”应收紧为OpenAPI声明403，本接口未实际触发403；角色/版本失效按既定契约返回401。纯追加也不能单独证明没有行为变化，范围结论需结合具体diff及回归证据。两处说明均不改变accepted，无阻断争议，不额外扩大改动。

本次收尾仅修改502日志文案、IP断言及审核/设计/计划记录，无迁移/依赖/配置/接口契约变化。NOTE-002/003登记为本轮已处理、自验通过，不伪称Claude已再次审核该收尾提交；原accepted报告明确非阻断可本轮处理、无重审前置。用户已明确授权完成后推送，后续执行正常快进推送，不强制覆盖远端。
