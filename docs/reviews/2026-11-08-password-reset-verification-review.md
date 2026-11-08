# 找回密码独立验证接口实现审核材料

日期为2026-11-08。本轮唯一POST /api/v1/users/password-reset-verifications，当前准备第1/3轮独立实现审核。设计1675fae第1/3轮书面审核accepted，计划07e19e3已由用户确认开始，代码与测试冻结be2f091。设计审核通过与本地验证分别记录，当前尚无独立实现accepted。

## 需求与权限

公开，无需旧密码、登录或近期auth_time，USER/ADMIN同规则。目标取服务端ACTIVE申请绑定，主动他账户有效Bearer不改变目标，主动无效/过期/撤销Bearer按原真实认证链拒绝。请求恰challengeId/code，成功200恰resetTicket/expiresAt，响应no-store。

本轮交付三步重构的第二步。现有第三步组合接口继续使用原三字段契约，后续单独改造为resetTicket/newPassword。前端待两个接口均独立审核通过后整体切换。

## 声称实现的行为

- 严格UTF-8 JSON及16384/16385字节预算，43字符规范标识、六位ASCII验证码/前导零，未知字段含newPassword/role/userId/resetTicket均400，重复/转义重复/嵌套/多根/类型/编码拒绝，媒体类型415。
- 复用原confirmation命名空间IP固定60秒10次、账户固定900秒10次，与旧组合入口合计，格式后IP先准入、可信ACTIVE后账户准入，不退款/不续窗。仅IP429+原窗口Retry-After，账户统一400且无拒绝层或等待字段。
- 复用一次真/伪验证码比较矩阵。普通预证明失败一次伪比较且忽略结果，真实错码计错后失败，IP拒绝/存储503不补比较，正确码后的第二次绑定变化或兑换冲突不补伪比较。
- 两次只读最小账户投影复核，验证码BCrypt与Redis在事务外。第二步零密码投影、零用户更新、零版本递增、零登录Token签发，旧登录和角色选择票继续按原版本使用。
- 单Lua核验原12字段ACTIVE完整绑定、原300秒deadline、当前attempts<5并排除旧attempts精确值，原子消费验证码、签发最新一张300秒票并替换旧票。票仅保存SHA-256摘要，10字段schema=1及同用户指针，两个PEXPIRETIME相同。
- 旧票完整读取经Java验证后以不可变预期进入Lua重检。候选碰撞、结构损坏或已知半状态503且写前拒绝；旧指针被替换400，原验证码保留；已知旧双键同时到期允许继续。更高版本票保护使用十进制字符串比较，支持超过2^53及Long.MAX_VALUE。
- 无指针时按无当前票读取，无法凭用户ID枚举随机摘要孤立记录，保留原TTL；孤立记录本身不具当前指针资格。已有previous快照时可定位具体旧摘要，一键缺失仍503。未增加扫描、清额或删坏键放行。
- 新申请/发送邮件不影响已签发票，正确新兑换后才替换。新激活、第五错、迟到旧操作与并发兑换按归属保护，最多一次成功，其他用户及其他用途状态隔离。
- 只有明确兑换成功及回复验证通过才返回新票。未知结果503，可能未执行，也可能已消费原码并替换票；不自动重试/恢复/退款。成功丢响应及未知恢复采用重新申请，重复不重放/续期。
- 新边界将共享CONFIRMATION限流/不可用异常转为VERIFICATION错误族，旧接口错误族保留。API及部署文档提供当前/最终阶段的接口、Redis额度键前缀、错误码三方对照，最终第三步completion额度明确为后续交付。

## 基线、文件及工程变更

- 代码基线main @ da146b2。设计1675fae、计划07e19e3；实现提交07d069e、bf6d52c、a481a94、b42fed6、7fd3a62、be2f091，源代码与测试冻结be2f091，最终完整验证针对该版本。
- API为docs/api/user/verify-password-reset.md，部署为docs/deployment/password-reset-verifications.md，设计/计划为docs/superpowers/specs/2026-11-08-password-reset-verification-design.md及docs/superpowers/plans/2026-11-08-password-reset-verification-plan.md。
- 数据库迁移为无，V1至V8零变更，无依赖/应用属性/Compose/后台任务变更。UserRepository、SQL、生产者及旧confirmation脚本源均未修改。
- 共享生产文件仅SecurityConfig增加本POST公开路径、GlobalExceptionHandler追加两个异常映射。旧Store/Verifier/Reader与common Lua通过新接口复用，异常转换发生在新Service。

## 实际测试与完整基准

每个核心任务先写失败测试，观察缺实现编译红灯后实现；请求/模型、Redis读取、原子兑换、Service及HTTP各自定向验证通过。真实MySQL/Redis/JWT链主类首次编译发现Jackson properties()为Set而非Map，调整为属性名集合后主链57秒通过。容器未被mock成功替代，SMTP为受控sender。

首次完整新接口定向命令退出0、BUILD SUCCESSFUL，用时1分50秒，10类99通过/0失败/0错误/0跳过。补齐原始邮箱120字符边界及第二次实际只读事务完成故障后，同命令最终定向退出0、BUILD SUCCESSFUL，用时1分49秒，逐份XML为10类101通过/0失败/0错误/0跳过。

```powershell
cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordResetVerification*Test" --tests "org.example.music.user.*PasswordResetTicket*Test" --console=plain
```

代码冻结be2f091后完整基准已执行，退出码0、BUILD SUCCESSFUL，用时17分47秒。当轮JUnit XML逐份聚合168类1926通过/0失败/0错误/0跳过，其中新增10类101项、既有158类1825项全部运行。MySQL8.4、Redis8.10.2、MinIO必要集成测试实跑，跳过项为无。完整验证期间及完成后只有文档收尾。容器关闭阶段出现既有Hikari连接关闭告警，以构建退出与完整XML核对结果，不将告警计作通过证据。

新增测试逐类数量如下，失败/错误/跳过均为0。

| 测试类 | 通过 |
| --- | ---: |
| PasswordResetTicketExchangeIntegrationTest | 16 |
| PasswordResetTicketReadIntegrationTest | 16 |
| PasswordResetVerificationConcurrencyIntegrationTest | 9 |
| PasswordResetVerificationFailureIntegrationTest | 14 |
| PasswordResetVerificationIntegrationTest | 16 |
| PasswordResetVerificationControllerTest | 3 |
| PasswordResetVerificationRequestReaderTest | 8 |
| PasswordResetTicketRulesTest | 3 |
| PasswordResetTicketStoreTest | 4 |
| PasswordResetVerificationServiceTest | 12 |


```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

## 默认项、实现调整与边界

- 沿用已批准单Redis≥7、可信时钟、持久化及全实例认证版本校验前提。票schema=1固定10字段及Redis签发起300000毫秒，旧码原期限独立保留。
- 原始邮箱长度按既有120字符持久化/生产者边界验证，票时间采用整毫秒精度，无新增业务默认决策。
- 新HTTP入口设置no-store以统一覆盖复用旧400/415和通用500。旧异常映射本身不改动，内部异常在新Service边界转换。
- 读取测试采用规范真实Redis数据夹具，兑换测试通过真实旧生产者admit/enqueue/claim/prepare/activate创建ACTIVE。HTTP链路通过真实POST申请与Processor，spy只用于实际操作先后和故障注入。
- 混合入口账户额度压力测试仅在测试夹具清发码冷却/IP窗口以避免分钟等待；固定窗拒绝不续期/自然恢复由既有全量及新增真实Redis期限测试覆盖。近deadline夹具使用Redis TIME构造合法历史准入并有界等待，生产时钟与窗口算法未改动。
- 低版本迟到的Redis级整合用例显式恢复旧协议夹具模拟已验证旧证明延迟到达；普通HTTP绑定变化与第二次DB复核后变更另有真实链路验证，未将DB当前版本回退来假装实际查询成功。
- 第三步票消费/密码更新及真实回滚/提交丢确认属于后续接口，当前只固定数据契约。第二步最终DB复核与Redis兑换存在间隙，期间变化时票保存旧绑定，后续第三步必须再次复核。
- 无指针孤立记录定位边界及账户额度残余降级如设计/部署记录。原窗口自然恢复，反复攻击仍可再次触发；不提供人工删键清额。

## curl

安全准备只含challengeId/code的PASSWORD_RESET_VERIFICATION_BODY_FILE，文件不入Git，生产HTTPS。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-reset-verifications' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_VERIFICATION_BODY_FILE}"
```

## 审核反馈复核与处置

首次独立实现审核尚无反馈，不涉及。以下沿用本任务书面设计审核原编号和非阻断分级，实际处置交独立实现审核核实。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| 本任务设计NOTE-001 非阻断 | 成立，第二步confirmation内部额度键对应VERIFICATION公开错误族，最终第三步completion对应CONFIRMATION，按码名猜键会混淆 | 认可，运维/开发可读性建议，无正确性或安全阻断 | 采纳接口、键前缀、错误码三方对照，区分当前/最终阶段并记录共享事实 | API与部署均交付两张三方表，当前第二步/旧组合入口合计原预算，最终第三步标明后续交付 | HTTP/真实OpenAPI及新旧入口混合额度定向通过；最终完整1926通过/0失败/0错误/0跳过。已落实待复核，不自行声明审核关闭 |

上一任务password-resets实现NOTE-001（无账户400/损坏503文案）已由本任务第1/3轮设计审核核实da146b2修改并关闭，当前继续采用该分类。

## 完整变更文件

相对da146b2，包含本轮untracked及设计/计划文档，全部文件如下。

- docs/api/user/verify-password-reset.md
- docs/deployment/password-reset-verifications.md
- docs/reviews/2026-11-08-password-reset-verification-review.md
- docs/superpowers/plans/2026-11-08-password-reset-verification-plan.md
- docs/superpowers/specs/2026-11-08-password-reset-verification-design.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/main/java/org/example/music/config/SecurityConfig.java
- src/main/java/org/example/music/user/controller/PasswordResetVerificationController.java
- src/main/java/org/example/music/user/controller/PasswordResetVerificationRequestReader.java
- src/main/java/org/example/music/user/domain/PasswordResetTicketPeriod.java
- src/main/java/org/example/music/user/domain/PasswordResetTicketRules.java
- src/main/java/org/example/music/user/domain/PasswordResetTicketSnapshot.java
- src/main/java/org/example/music/user/dto/PasswordResetVerificationRequest.java
- src/main/java/org/example/music/user/dto/PasswordResetVerificationResponse.java
- src/main/java/org/example/music/user/exception/PasswordResetVerificationRateLimitedException.java
- src/main/java/org/example/music/user/exception/PasswordResetVerificationUnavailableException.java
- src/main/java/org/example/music/user/repository/PasswordResetTicketStore.java
- src/main/java/org/example/music/user/service/PasswordResetVerificationService.java
- src/main/resources/redis/password-reset-verification/common.lua
- src/main/resources/redis/password-reset-verification/exchange.lua
- src/main/resources/redis/password-reset-verification/read-current.lua
- src/test/java/org/example/music/user/PasswordResetTicketExchangeIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetTicketReadIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetVerificationConcurrencyIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetVerificationFailureIntegrationTest.java
- src/test/java/org/example/music/user/PasswordResetVerificationIntegrationSupport.java
- src/test/java/org/example/music/user/PasswordResetVerificationIntegrationTest.java
- src/test/java/org/example/music/user/controller/PasswordResetVerificationControllerTest.java
- src/test/java/org/example/music/user/controller/PasswordResetVerificationRequestReaderTest.java
- src/test/java/org/example/music/user/domain/PasswordResetTicketRulesTest.java
- src/test/java/org/example/music/user/repository/PasswordResetTicketStoreTest.java
- src/test/java/org/example/music/user/service/PasswordResetVerificationServiceTest.java
