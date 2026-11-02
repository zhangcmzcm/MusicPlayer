# U03 修改本人密码首轮审核材料

日期：2026-11-02。状态：Claude 独立代码审核第 1/3 轮 accepted，零阻断、零非阻断；回执及复核记录见文末。

## 本次接口

`PUT /api/v1/users/me/password`。仅此接口；不新增其他接口，不修改注册、登录、认证主体、JWT 签发实现或过滤器。

## 需求与权限

- 需要有效 Bearer JWT；USER、ADMIN 登录身份均可改本人，Integer userId 仅取 AuthenticatedUserPrincipal。查询/头部伪造 ID 不改变目标；JSON 未知 ID 字段400。
- 原密码必填、非全空白、1—128 个 Java String 字符，兼容历史短密码和非 ASCII，不加72字节限制。
- 新密码非全空格、8—72 个可打印 ASCII（U+0020—U+007E），不能与当前密码等价；首尾空格保留，不 trim、规范化、截断或预哈希。
- 成功更新摘要、token_version+1 与 UTC 微秒更新时间，同一事务确认提交后返回204/no-store、空正文，不发新Token。当前及其他登录的旧Token后续版本检查均撤销，客户端重新登录。

## 声称实现的行为

1. Jackson 3 请求局部严格绑定，仅接受 oldPassword/newPassword 两个字符串，各一次，顺序不限；未知字段、重复/转义重复键、缺字段、错误类型、非对象、多个根值均400。Bean Validation 在Service前执行，不改全局JSON规则。
2. 独立 Redis Lua，key为 music:auth:password-change:user:<认证ID>；首次有效格式请求起固定900秒最多5次，任何BCrypt前计数。超限429、Retry-After向上取整，超限/允许/登录/成功均不重置已有窗口。
3. 原密码错、新密码等价、CAS冲突、DB失败不退额度；Redis异常、损坏类型/非规范计数/缺TTL/异常TTL/未知回包均503，不继续密码运算或写入，不删除记录放行。
4. 顺序为限流→密码投影→matches(old)→matches(new)→hash(new)→短写事务CAS。BCrypt与Redis均在数据库写事务之外；不比较随机盐摘要来判定密码相同。
5. CAS条件是用户ID＋二进制旧摘要＋预期版本；SQL只赋值 password_token、token_version+1、updated_at=UTC_TIMESTAMP(6)，guard禁止版本溢出。0行同事务exists查询区分409冲突/401账户消失，不自动重试。
6. 数据库读写和事务启动/提交故障503；提交确认故障可能已生效，不声称失败一定没改。意外非存储哈希异常保持500，不泛化改成参数或数据库故障。
7. 六个新异常接入既有GlobalExceptionHandler；code稳定，type为下划线urn。DTO/内部快照toString脱敏，请求字段只写；存储故障日志保留traceId、异常类型及脱敏堆栈，不透传底层SQL/Redis消息、cause或suppressed。
8. 真实改密验证既有认证加固：旧验证、旧Ticket无法升级版本；最终复核后的竞态允许签出旧版本Token，但实际受保护链拒绝。不承诺中止已通过认证的业务请求、已开始传输，不禁用邮箱验证码登录。

## 基线与提交

- 基线：main @ e5b5c96，开始时工作区干净；设计为 docs/superpowers/specs/2026-11-01-change-password-design.md，含原密码兼容修正与aa45d12的ASCII新密码规则，用户本轮要求全部沿用。
- 计划：550a158，docs/superpowers/plans/2026-11-02-change-password-plan.md，用户确认后编码。
- 实现：e94942d（严格DTO/快照）、fbac5a0（独立Lua限流）、af39dc5（密码校验/CAS/事务）、c3ad118（HTTP/错误/API文档）、eaf2c14（真实撤销/并发/回归）。
- 前置登录加固5e8ceba和注册修复29d3f3e/37c294f均已独立审核accepted，不重复实施。

## 全部变更文件（含新增）

生产代码（14个）：

- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java（修改，仅本接口六映射和局部脱敏诊断）
- src/main/java/org/example/music/user/repository/UserRepository.java（修改，仅密码投影与CAS）
- src/main/java/org/example/music/user/controller/CurrentUserPasswordController.java（新增）
- src/main/java/org/example/music/user/domain/UserPasswordSnapshot.java（新增）
- src/main/java/org/example/music/user/dto/ChangePasswordRequest.java（新增）
- src/main/java/org/example/music/user/dto/ChangePasswordRequestDeserializer.java（新增）
- src/main/java/org/example/music/user/service/CurrentUserPasswordService.java（新增）
- src/main/java/org/example/music/user/service/PasswordChangeRateLimiter.java（新增）
- src/main/java/org/example/music/user/exception/OldPasswordIncorrectException.java（新增）
- src/main/java/org/example/music/user/exception/NewPasswordSameAsOldException.java（新增）
- src/main/java/org/example/music/user/exception/PasswordChangeConflictException.java（新增）
- src/main/java/org/example/music/user/exception/PasswordChangeRateLimitedException.java（新增）
- src/main/java/org/example/music/user/exception/PasswordChangeRateLimitUnavailableException.java（新增）
- src/main/java/org/example/music/user/exception/PasswordChangeStoreUnavailableException.java（新增）

测试（5个新增）：

- src/test/java/org/example/music/user/dto/ChangePasswordRequestTest.java
- src/test/java/org/example/music/user/service/PasswordChangeRateLimiterTest.java
- src/test/java/org/example/music/user/service/CurrentUserPasswordServiceTest.java
- src/test/java/org/example/music/user/controller/CurrentUserPasswordControllerTest.java
- src/test/java/org/example/music/user/PasswordChangeIntegrationTest.java

文档（3个）：

- docs/api/user/change-password.md（新增）
- docs/superpowers/plans/2026-11-02-change-password-plan.md（计划及进度）
- docs/reviews/2026-11-02-change-password-review.md（新增，本材料）

共22个文件。没有未声明的业务改动；最终提交前按git核对新增及未跟踪文件。

## 数据库、配置与文档

- 数据库迁移：无；V1—V8不变，不改ddl-auto。
- 无新增依赖、配置、Docker Compose或SecurityConfig变更；不修改PasswordHasher、登录加固代码、全局ObjectMapper或已有接口测试。
- API文档：docs/api/user/change-password.md。OpenAPI描述认证、严格字段、204、400/401/409/429/503/500、限流与撤销，密码字段WRITE_ONLY。
- 错误格式沿用ProblemDetail及现有禁止缓存/安全头；成功显式no-store，错误允许既有组合Cache-Control含no-store。通过X-Trace-Id关联日志，不虚构响应体traceId字段。

## 实际验证

定向真实依赖命令：

```powershell
cmd /c gradlew.bat test --tests "org.example.music.user.PasswordChangeIntegrationTest" --tests "org.example.music.user.AuthVersionBindingIntegrationTest" --tests "org.example.music.user.UserRegistrationIntegrationTest"
```

最终定向运行 BUILD SUCCESSFUL，1分44秒；改密集成51、既有认证加固46、注册集成12，共109通过、0失败、0错误、0跳过。MySQL8.4、Redis8.10.2及真实BCrypt/JWT实际执行；SMTP仅捕获验证码邮件，不替代真实Redis。

完整基准命令：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

完整结果：BUILD SUCCESSFUL，8分31秒；925通过、0失败、0错误、0跳过，86个测试类，汇总自本次cleanTest生成的build/test-results/test/TEST-*.xml。跳过项：无。虽然本任务不修改storage/track，本次启用既有MinIO完整回归。

本任务五个新增类共123项：ChangePasswordRequestTest 22、PasswordChangeRateLimiterTest 13、CurrentUserPasswordServiceTest 15、CurrentUserPasswordControllerTest 22、PasswordChangeIntegrationTest 51，均0失败/错误/跳过。其余既有802项为回归，未删改既有用例。

基准验证后仅补充API追踪头的准确说明、计划进度和本审核材料，没有更改运行时代码或测试，未将文档收尾另记为一次测试运行。git diff --check通过。

验证证据边界：

- 新类型尚不存在时的首次测试为预期编译失败，未把它冒充业务断言失败；测试实现期间的JUnit泛型/异常声明问题已修正，最终测试必须全部执行。
- Controller里注入authentication仅证明HTTP契约。真实JWT签名、声明、版本和权限由PasswordChangeIntegrationTest走实际过滤链证明。
- 真实MySQL事务故障验证摘要和版本一起回滚；事务启动/提交确认故障由Service单元测试注入，未声称模拟了真实“数据库已提交但网络确认丢失”。文档保留503提交结果不确定性。
- 真实12请求并发中恰5个执行旧密码BCrypt，其余429；真正同快照双改密恰一个204/另一个409，使用闩锁/栅栏和超时清理，不sleep。
- 跨当前/多设备/不同登录角色旧Token全部被后续鉴权拒绝；历史短密码和非ASCII旧密码可改；新密码创建规则拒绝Unicode与控制字符；新获取邮箱LOGIN验证码仍可登录。
- 版本上限、摘要仅大小写变化、仅版本变化、仅ID变化分别验证；无效格式不消耗配额，已消费额度在后续故障/冲突时保留。

## curl示例

PASSWORD_CHANGE_BODY_FILE由调用者安全准备，内容为两个密码字段。只使用环境变量，不在URL或文档中放真实凭据，文件不进入Git。

```sh
curl --request PUT 'https://music.example.com/api/v1/users/me/password' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_CHANGE_BODY_FILE}"
```

```powershell
curl.exe --request PUT 'https://music.example.com/api/v1/users/me/password' `
  --header "Authorization: Bearer $env:ACCESS_TOKEN" `
  --header 'Content-Type: application/json' `
  --data-binary "@$env:PASSWORD_CHANGE_BODY_FILE"
```

## 未决问题、默认项与部署条件

无待定业务规则，无新增403或本人资源404分支。旧Token重复请求401是撤销语义，不保证重复204。不引入密码历史、会话豁免、自动换Token或分布式事务。

上线仍须满足docs/deployment/auth-version-binding.md：全部登录实例升级、旧登录请求排空；回滚旧登录代码须停止依赖该保证的改密上线。代码审核不证明部署已完成，本次不操作生产环境。

## 审核反馈复核与处置

首次提交：不涉及。等待Claude独立只读审核；收到 accepted 前不宣称接口完成、不开始下一接口。

## 第 1 轮审核回执与复核

用户提供审核报告：accepted；审核基线 main @ af357c7，22个文件与申报一致，工作区干净。独立执行 `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`，BUILD SUCCESSFUL，8分17秒，925通过、0失败、0错误、0跳过；86类，其中本任务123项、既有802项。报告确认XML为本次生成，必要MySQL/Redis/MinIO均实跑。

复核结果：报告的提交范围、实现行为与本地源码及提交记录一致；无阻断项或非阻断项需要逐条修复，不涉及修改方向争议。上一任务O15仍为独立管理员初始化事项，不并入本接口。无需第二轮阻断项复核。

U03代码任务已完成。部署仍须满足全实例登录加固升级和旧登录请求排空条件，未把代码accepted等同于部署完成。用户已授权确认无误后push；本次收尾仅登记审核、计划和任务书，不修改运行时代码或测试，不重复运行完整回归。
