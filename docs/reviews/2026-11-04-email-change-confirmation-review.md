# 本人邮箱变更确认接口实现审核材料

日期：2026-11-04。阶段：实现与本地全量自验完成，首次独立实现送审材料已准备。本接口尚未获得Claude独立实现审核accepted，不开始下一个接口，不自动推送。

## 本次接口与需求权限

唯一新增PUT /api/v1/users/me/email。USER/ADMIN仅操作本人，允许首次绑定或更换邮箱；有效Bearer JWT并要求0≤now−auth_time≤300秒，含300秒端点，不看iat。通过一次门禁后严格解析唯一challengeId/code两字符串，目标邮箱从服务端ACTIVE读取。缺auth_time/超窗401 REAUTHENTICATION_REQUIRED，普通认证过期/撤销保留原契约。

## 声称实现的行为

- 规范32字节Base64URL无填充43字符标识、六位ASCII数字及前导零，不trim；严格JSON/UTF-8/重复转义键及16384字节预算，415沿用本流程专用映射。
- 每用户首个获准起900秒10次，与发码/再次验证隔离，跨Token/设备/角色，获准后失败不退款，429不计数不续窗并附Retry-After。
- 读取既有ACTIVE及完整归属、原始期限/实际TTL、BCrypt摘要与0—4次状态，损坏关闭放行。第五错失效，正确码原子最多消费一次；旧确认/计错不能动替换后的新申请。
- 只有Redis明确消费成功才进入SQL，消费未知503不执行SQL。消费后数据库回滚、冲突、提交未知均不恢复码、不退款、不重试SQL；数据库提交未知503可能已改变邮箱及版本。
- 条件更新匹配消费前保存的本人/原始邮箱（NULL安全、二进制精确）/角色/版本；只SET邮箱、版本加1、UTC updated_at。0行只分类不刷新预期重试，MAX不溢出，邮箱唯一约束明确才409，其他存储失败503。
- 数据库明确提交后204、no-store、无正文/Location/新Token；当前与其他全部旧登录失效。旧消费先于新激活时可完成原修改，旧操作不清理新ACTIVE/pending。
- 无密码/用户名/创建时间/登录时间覆盖，无新Token签发，无SMTP/Redis等待进入写事务，无新增查询/恢复/refresh端点。日志/DTO/状态及异常链脱敏，traceId保留。

## 基线、文档、迁移与依赖

- main，代码基线97215a0；设计189367a、计划d78df50；实现31e3e06/b74fea0/cdc49cc/af9e68e/5381e2c/94ac5fe。最终全量验证对象为94ac5fe，后续仅更新审核及执行文档；审核范围为当前main相对97215a0的本接口所有变更，包含本记录。
- API：docs/api/user/confirm-email-change.md。
- 部署：docs/deployment/email-change-confirmations.md。
- 设计：docs/superpowers/specs/2026-11-04-email-change-confirmation-design.md。
- 计划：docs/superpowers/plans/2026-11-04-email-change-confirmation-plan.md。
- 数据库迁移：无，V1—V8未改；无新依赖/配置/Compose/SecurityConfig/JWT/Actuator变更，旧生产者脚本不变。

## 实际验证

各组件先观察缺实现编译失败，再实现及定向验证。真实SQL覆盖原始NULL/空白/大小写、唯一约束、版本上限、提交后确认丢失；Controller回归包含原发码和再次验证入口。未删除失败断言或跳过必要容器。

定向运行 `cmd /c gradlew.bat test --tests "org.example.music.user.EmailChangeConfirmation*IntegrationTest" --console=plain`，实跑Store、Transaction、主链路、Concurrency、Failure五类80项，初次79通过/1失败/0错误/0跳过。失败是故障夹具直接callRealMethod调用Spring Data抽象查询，未发生预期数据库故障；改在真实SQL执行后、commit前用真实事务manager.rollback并抛TransactionSystemException，保留用户整行不变、真实更新已调用、码已消费及重放失败断言。随后单独重跑Failure类10通过/0失败/0错误/0跳过。此前集成夹具Container导入歧义及Docker条件注解继承造成的初始化失败均已修正，条件保留在具体测试类，并未关闭容器条件或将跳过当通过。

本次最终全量基准已执行，JUnit XML结果为126类、1599通过／0失败／0错误／0跳过，其中本接口新增12类、154项全部通过，真实MySQL、Redis及MinIO测试均无跳过：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

长任务结束后会话输出未能再次读取，未臆造该次进程退出码；随后保持相同MinIO环境运行 `.\gradlew.bat check --console=plain`，退出码0、BUILD SUCCESSFUL，test/check均UP-TO-DATE，确认完整运行后的构建状态成功。数量来源为本次cleanTest生成的build/test-results/test/TEST-*.xml，不将前置接口1445或历史定向数量作为本接口最终结果。数据库提交后回包丢失测试使用真实commit并JDBC核实已更新；Redis消费确认丢失测试执行真实Lua再抛错，断言零SQL，未mock持久化成功。

新增测试逐类结果（以下均0失败／0错误／0跳过）：

| 测试类 | 通过 |
| --- | ---: |
| EmailChangeConfirmationConcurrencyIntegrationTest | 9 |
| EmailChangeConfirmationControllerTest | 5 |
| EmailChangeConfirmationFailureIntegrationTest | 10 |
| EmailChangeConfirmationIntegrationTest | 26 |
| EmailChangeConfirmationRequestReaderTest | 30 |
| EmailChangeConfirmationRulesTest | 7 |
| EmailChangeConfirmationServiceTest | 10 |
| EmailChangeConfirmationSnapshotReaderTest | 4 |
| EmailChangeConfirmationStoreIntegrationTest | 27 |
| EmailChangeConfirmationStoreTest | 13 |
| EmailChangeConfirmationTransactionIntegrationTest | 8 |
| EmailChangeConfirmationTransactionServiceTest | 5 |

## 默认项与边界

沿用单Redis及已有应用Clock/Redis TIME职责，提交900秒额度独立；生产者状态schema不变。本接口不支持成功重放或自动补偿，503/响应丢失后恢复有效登录并核实当前邮箱，可用未改变的用户名登录；旧Token失效并不能独立证明改邮箱成功。

计划执行的小调整：增加抽象测试夹具EmailChangeConfirmationIntegrationSupport，统一真实容器、签名JWT及发送入口；@DirtiesContext(AFTER_CLASS)防止跨类复用已停止容器，Docker条件在具体测试类显式保留。该辅助文件仅测试复用，不增加生产能力或依赖。事务唯一冲突使用实际MySQL1062/23000及消息末尾精确ix_users_email证据，未知错误不泛化为邮箱占用；规则和驱动行为有真实MySQL验证。

## 审核反馈复核与处置

首次本接口实现提交：不涉及既往反馈。此前发码接口的accepted属于另一接口，不挪用为本接口审核结论。当前没有未解决审核阻断或人类业务分歧；本地自验不替代Claude独立审核，收到报告后按AGENTS.md保留原编号/分级/轮次逐项复核。

## curl

```bash
curl -i -X PUT 'http://localhost:8080/api/v1/users/me/email' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"challengeId":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","code":"012345"}'
```

示例为虚构调用数据，实际用本人近期有效Token及新邮箱申请的标识/验证码，不写日志。

## 变更文件（含untracked）

以下为相对97215a0的全部任务文件，包含本材料自身；不包含本地构建输出或凭据：

- docs/api/user/confirm-email-change.md
- docs/deployment/email-change-confirmations.md
- docs/reviews/2026-11-04-email-change-confirmation-review.md
- docs/superpowers/plans/2026-11-04-email-change-confirmation-plan.md
- docs/superpowers/specs/2026-11-04-email-change-confirmation-design.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/main/java/org/example/music/user/controller/EmailChangeConfirmationController.java
- src/main/java/org/example/music/user/controller/EmailChangeConfirmationRequestReader.java
- src/main/java/org/example/music/user/domain/EmailChangeChallengeSnapshot.java
- src/main/java/org/example/music/user/domain/EmailChangeConfirmationRules.java
- src/main/java/org/example/music/user/dto/EmailChangeConfirmationRequest.java
- src/main/java/org/example/music/user/exception/EmailChangeConfirmationRateLimitedException.java
- src/main/java/org/example/music/user/exception/EmailChangeConfirmationUnavailableException.java
- src/main/java/org/example/music/user/exception/EmailChangeConflictException.java
- src/main/java/org/example/music/user/exception/EmailChangeVerificationFailedException.java
- src/main/java/org/example/music/user/repository/EmailChangeConfirmationStore.java
- src/main/java/org/example/music/user/repository/UserRepository.java
- src/main/java/org/example/music/user/service/EmailChangeConfirmationService.java
- src/main/java/org/example/music/user/service/EmailChangeConfirmationSnapshotReader.java
- src/main/java/org/example/music/user/service/EmailChangeConfirmationTransactionService.java
- src/main/resources/redis/email-change-confirmation/admit.lua
- src/main/resources/redis/email-change-confirmation/common.lua
- src/main/resources/redis/email-change-confirmation/consume.lua
- src/main/resources/redis/email-change-confirmation/fail.lua
- src/main/resources/redis/email-change-confirmation/read.lua
- src/test/java/org/example/music/user/EmailChangeConfirmationConcurrencyIntegrationTest.java
- src/test/java/org/example/music/user/EmailChangeConfirmationFailureIntegrationTest.java
- src/test/java/org/example/music/user/EmailChangeConfirmationIntegrationSupport.java
- src/test/java/org/example/music/user/EmailChangeConfirmationIntegrationTest.java
- src/test/java/org/example/music/user/EmailChangeConfirmationStoreIntegrationTest.java
- src/test/java/org/example/music/user/EmailChangeConfirmationTransactionIntegrationTest.java
- src/test/java/org/example/music/user/controller/EmailChangeConfirmationControllerTest.java
- src/test/java/org/example/music/user/controller/EmailChangeConfirmationRequestReaderTest.java
- src/test/java/org/example/music/user/domain/EmailChangeConfirmationRulesTest.java
- src/test/java/org/example/music/user/repository/EmailChangeConfirmationStoreTest.java
- src/test/java/org/example/music/user/service/EmailChangeConfirmationServiceTest.java
- src/test/java/org/example/music/user/service/EmailChangeConfirmationSnapshotReaderTest.java
- src/test/java/org/example/music/user/service/EmailChangeConfirmationTransactionServiceTest.java
