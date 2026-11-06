# 密码重置确认接口实现审核材料

日期为2026-11-06，文件名沿用已批准设计与计划日期。当前为第1/3轮独立实现审核准备阶段，设计审核accepted不替代实现审核。

## 本次接口与权限

唯一`POST /api/v1/users/password-resets`。公开，无需登录、旧密码或近期auth_time，USER/ADMIN同规则。仅提交challengeId/code/newPassword，目标账户取自服务端ACTIVE申请绑定。主动附带另一账户有效Token不改变目标，主动附带无效/过期/撤销Bearer仍走现有认证链拒绝。

## 声称实现的行为

- 三字段严格UTF-8 JSON，最多16384字节，保留前导零和密码首尾空格。申请标识为规范43字符Base64URL，密码8至72位可打印ASCII且非全空格。缺失、重复或转义重复、未知、错误类型、嵌套、多根及无效编码均400，媒体类型不支持415。
- 独立IP固定60秒10次、账户固定900秒10次。格式通过后IP先准入，完整ACTIVE后账户准入，前层与后续失败不退款，拒绝当前层不增不续。IP拒绝429与真实窗口Retry-After，账户拒绝统一400，无拒绝层、等待或剩余次数。
- IP获准后，预验证码普通失败仅一次同成本伪比较，忽略结果后统一拒绝。真实错码仅一次验证码比较，IP拒绝与存储503不补伪比较。不使用sleep填时，不承诺完全等时。私有伪摘要由同一PasswordHasher以随机值生成，不持久化或输出。
- 消费原发码12字段ACTIVE，原准入起300秒期限不变。累计第五次错码原子失效，正确码一次性消费，比较完整不可变绑定与当前指针，读取后非终态attempts变化不拒绝正确消费。迟到旧操作不修改新ACTIVE或队列状态。
- 正确码后再次读取最小密码快照，等价密码400且保留验证码。版本MAX、坏摘要与新摘要计算故障均在消费前503退出。摘要计算和Redis操作在写事务外完成。
- 明确消费成功后只执行一次MySQL条件更新，匹配prepared用户ID、NULL安全二进制原始邮箱、二进制角色/密码摘要、原版本及溢出守卫。仅SET密码摘要、版本加1、UTC更新时间。0行统一400，无回读重试，异常行数500并回滚。
- 消费未知503且零写事务。消费后真实回滚或提交确认丢失均不恢复、不退款、不重试。提交未知可能已改密及撤销登录，需走新密码正常登录核实。原期限内消费后允许跨期限提交，旧请求不重放204。
- 成功204空正文、no-store、无Location或新Token，目标全部旧Token及旧角色选择Ticket不能升级。另一账户有效Token仍有效，管理员按原角色选择登录。并发仅改名仍可重置且保留新名。
- 新pending不阻止旧ACTIVE使用。新激活先于旧消费则旧确认失败，旧消费先于新激活则可完成原SQL且不删除新ACTIVE，新码绑定旧版本随后不可兑换。

## 基线与范围

- 代码基线为main @ 10e38d7，设计c2530f3、计划9bbf073。最终源代码与测试冻结e2909bd，包含dae434e、ba86eac、6560fb1、7b9f9df、3a762af、339d14c及e2909bd七个实现/测试提交。
- API文档为docs/api/user/confirm-password-reset.md，部署说明为docs/deployment/password-reset-confirmations.md。
- 数据库迁移为无，已执行V1至V8零变更。无新依赖、应用属性、Compose服务或后台任务。原发码Lua/schema、旧changePasswordIfSnapshotMatches、JWT版本策略未变。SecurityConfig仅增加当前单POST公开路径，UserRepository仅追加prepared投影和专用CAS，GlobalExceptionHandler追加5种异常映射。

## 实际验证

首轮完整命令在339d14c执行，退出0，BUILD SUCCESSFUL，用时16分43秒。实际JUnit XML为158类1825通过、0失败、0错误、0跳过，含本接口13类107项。检查期间复核过期JWT夹具，将token_version改为真实tokenVersion并补loginType，形成仅测试修正e2909bd。最终版本e2909bd同命令完整重跑已完成，退出0，BUILD SUCCESSFUL，用时15分56秒。实际JUnit XML为158类1825通过、0失败、0错误、0跳过，其中本接口13类107项、既有145类1718项全部运行。MySQL8.4、Redis8.10.2与MinIO必要集成测试实跑，跳过项为无。期间及完成后仅修改文档。容器关闭阶段仍有既有连接池关闭连接告警，以构建退出及当轮XML核对结果，不将告警当通过证据。

新增测试逐类数量如下，失败、错误和跳过均为0。

| 测试类 | 通过 |
| --- | ---: |
| PasswordResetConfirmationConcurrencyIntegrationTest | 9 |
| PasswordResetConfirmationFailureIntegrationTest | 12 |
| PasswordResetConfirmationIntegrationTest | 17 |
| PasswordResetConfirmationStoreIntegrationTest | 17 |
| PasswordResetConfirmationTransactionIntegrationTest | 8 |
| PasswordResetConfirmationControllerTest | 3 |
| PasswordResetConfirmationRequestReaderTest | 11 |
| PasswordResetConfirmationRulesTest | 2 |
| PasswordResetConfirmationStoreTest | 3 |
| PasswordResetCodeVerifierTest | 3 |
| PasswordResetConfirmationServiceTest | 15 |
| PasswordResetConfirmationSnapshotReaderTest | 3 |
| PasswordResetConfirmationTransactionServiceTest | 4 |


```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

之前的定向命令如下，首轮13类96项全部通过。补齐真实认证及故障边界后同范围再次BUILD SUCCESSFUL（2分25秒）；随后请求/协调/Verifier三个类最后29项通过（9秒）。完整基准以本节最终全量为准，不汇总不同定向运行当作一次完整结果。

```powershell
cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordResetConfirmation*Test" --tests "org.example.music.user.service.PasswordResetCodeVerifierTest" --console=plain
```

测试驱动阶段先观察缺实现编译失败，再逐任务实施。首次HTTP/真实链路16项中2项缓存头断言失败，原因是旧同密码handler采用原安全链复合缓存头；新入口统一设置no-store后重新验证通过，未改旧handler。后续协调单测一项Mockito重设桩触发旧异常，改用doReturn重设后通过。上述均已修复，不将中间红灯当作最终结果。

## 默认项、执行细节与残余风险

- 单Redis、Redis7以上、可信时钟和全实例认证版本校验等部署前提见部署说明。无新增业务默认决策。
- HTTP入口先设置Cache-Control为no-store，保证既有同密码异常和通用500同样禁止缓存，既有handler未修改。复用PasswordResetDiagnostics清理异常链后再交给通用日志处理。
- 新真实链路夹具独立定义。常规路径走真实POST申请、Processor、Redis、MySQL与JWT链，SMTP可控替换；spy仅控制真实操作先后和故障。计数压力夹具清发码冷却/IP窗口以避免分钟等待，原窗口固定与自然到期由真实Redis专项测试验证。短deadline边界通过真实生产者协议构造合法历史准入，并以有界定时闩锁等待，未修改生产时钟或窗口。
- 账户窗口可能被持有真实challengeId的人消耗。重发不清额，当前原900秒窗口剩余时间内统一400，原TTL后自然恢复，反复攻击可在后续窗口再次触发，受发码邮箱60秒冷却及每日10次约束。无人工删键放行。
- Redis消费和MySQL提交是分离的操作。成功消费后数据库失败可能需要重新申请，提交确认丢失时503可能已改密。客户端不能凭503或重试400认定首次未提交。

## curl

安全准备包含三个字段的本地文件并设置PASSWORD_RESET_BODY_FILE，文件不入Git，示例无真实凭据。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-resets' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_BODY_FILE}"
```

## 审核反馈复核与处置

本次首次提交独立实现审核，尚无实现审核反馈，不涉及。以下沿用已accepted设计审核第1/3轮的非阻断意见，不擅自声明审核方关闭。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断 | 成立，原发码使用PASSWORD_RESET_REQUEST_*，确认采用CONFIRMATION可减少混淆 | 认可，仅命名易用性 | 采纳确认前缀，当前未发布确认接口，无旧客户端兼容问题 | 新handler/OpenAPI/API均用PASSWORD_RESET_CONFIRMATION_RATE_LIMITED/UNAVAILABLE，API提供两个接口code对照 | Controller及真实OpenAPI定向已通过，最终全量1825通过/0失败/0错误/0跳过；处置交独立审核核实 |
| NOTE-002 非阻断 | 成立，有效申请标识可消耗跨申请账户额度，合法用户受剩余窗口影响 | 认可，残余可用性风险，无认证绕过 | 采纳部署记录，按原窗口自然恢复，保持已确认额度 | 部署文档说明绑定复核前扣额、五错与账户计数区别、重发不清额、反复攻击可再次触发 | 真实Redis自然过期、不续窗与跨申请统一400定向已通过，最终全量1825通过/0失败/0错误/0跳过；不以单窗900秒声称永久不可重复 |

## 完整变更文件

以下范围相对10e38d7，包括本次新增文档及untracked文件。

- docs/api/user/confirm-password-reset.md
- docs/deployment/password-reset-confirmations.md
- docs/reviews/2026-11-05-password-reset-confirmation-review.md
- docs/superpowers/plans/2026-11-05-password-reset-confirmation-plan.md
- docs/superpowers/specs/2026-11-05-password-reset-confirmation-design.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/main/java/org/example/music/config/SecurityConfig.java
- src/main/java/org/example/music/user/controller/PasswordResetConfirmationController.java
- src/main/java/org/example/music/user/controller/PasswordResetConfirmationRequestReader.java
- src/main/java/org/example/music/user/domain/PasswordResetChallengeSnapshot.java
- src/main/java/org/example/music/user/domain/PasswordResetConfirmationRules.java
- src/main/java/org/example/music/user/domain/PasswordResetPreparedSnapshot.java
- src/main/java/org/example/music/user/dto/PasswordResetConfirmationRequest.java
- src/main/java/org/example/music/user/exception/PasswordResetConfirmationRateLimitedException.java
- src/main/java/org/example/music/user/exception/PasswordResetConfirmationUnavailableException.java
- src/main/java/org/example/music/user/exception/PasswordResetConfirmationUnsupportedMediaTypeException.java
- src/main/java/org/example/music/user/exception/PasswordResetConfirmationValidationException.java
- src/main/java/org/example/music/user/exception/PasswordResetVerificationFailedException.java
- src/main/java/org/example/music/user/repository/PasswordResetConfirmationStore.java
- src/main/java/org/example/music/user/repository/UserRepository.java
- src/main/java/org/example/music/user/service/PasswordResetCodeVerifier.java
- src/main/java/org/example/music/user/service/PasswordResetConfirmationService.java
- src/main/java/org/example/music/user/service/PasswordResetConfirmationSnapshotReader.java
- src/main/java/org/example/music/user/service/PasswordResetConfirmationTransactionService.java
- src/main/resources/redis/password-reset-confirmation/admit-account.lua
- src/main/resources/redis/password-reset-confirmation/admit-ip.lua
- src/main/resources/redis/password-reset-confirmation/common.lua
- src/main/resources/redis/password-reset-confirmation/consume.lua
- src/main/resources/redis/password-reset-confirmation/fail.lua
- src/main/resources/redis/password-reset-confirmation/read.lua
- src/test/java/org/example/music/user/PasswordResetConfirmationConcurrencyIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetConfirmationFailureIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetConfirmationIntegrationSupport.java
- src/test/java/org/example/music/user/PasswordResetConfirmationIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetConfirmationStoreIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetConfirmationTransactionIntegrationTest.java
- src/test/java/org/example/music/user/controller/PasswordResetConfirmationControllerTest.java
- src/test/java/org/example/music/user/controller/PasswordResetConfirmationRequestReaderTest.java
- src/test/java/org/example/music/user/domain/PasswordResetConfirmationRulesTest.java
- src/test/java/org/example/music/user/repository/PasswordResetConfirmationStoreTest.java
- src/test/java/org/example/music/user/service/PasswordResetCodeVerifierTest.java
- src/test/java/org/example/music/user/service/PasswordResetConfirmationServiceTest.java
- src/test/java/org/example/music/user/service/PasswordResetConfirmationSnapshotReaderTest.java
- src/test/java/org/example/music/user/service/PasswordResetConfirmationTransactionServiceTest.java
