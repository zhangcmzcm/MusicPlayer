# 修改本人密码接口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 按项目已确认方式执行：Codex 唯一实现，Claude 独立只读审核，不派发实现子代理。缺失的执行子技能不声称已调用，按计划原生执行。本计划待用户审阅，尚未实施。

**Goal:** 实现 U03 `PUT /api/v1/users/me/password`，已登录本人验证原密码后设置新密码，提交时递增 token_version，撤销当前及其他设备的全部旧 Token。

**Architecture:** 请求局部严格 JSON 绑定和 Bean Validation；独立 Redis Lua 按主体 ID 限流；读取内部密码快照，在写事务外完成 BCrypt 校验和哈希，再通过 ID＋二进制旧摘要＋旧版本 CAS 短事务更新。提交确认后返回无响应体的 204/no-store，不签发新 Token。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Jackson 3 tools.jackson、Spring Validation/Security/Data JPA/Redis、MySQL 8.4、Redis 8.10.2 测试容器、JUnit 5/MockMvc/Testcontainers、既有 BCrypt/JWT/springdoc、Gradle Wrapper；不新增依赖。

**Spec:** [既有改密设计](../specs/2026-11-01-change-password-design.md)，包含 b2c71e4 的原密码兼容修正和 aa45d12 的 ASCII 新密码规则。用户本轮明确全部沿用并要求编写计划。旧设计中的“注册尚未修复”“先实施认证加固”是历史状态，本计划按已完成代码核实，不重复开展前置任务。

## 全局约束与基线

- 实施前基线 main @ e5b5c96，工作区干净，与 origin/main 同步。认证加固 5e8ceba 已 accepted；注册修复 29d3f3e、37c294f 已 accepted，最新独立完整回归 802/0/0/0、81 类。
- 唯一接口 PUT /api/v1/users/me/password。USER/ADMIN 均可改本人，Integer ID 只取 AuthenticatedUserPrincipal.userId；请求不得指定目标用户、角色、邮箱、tokenVersion 或确认密码。
- oldPassword 非 null、非全空白、1—128 Java String 字符，不新增 ASCII 或 72 字节限制。newPassword 非全空格、8—72 个 U+0020—U+007E 可打印 ASCII 字符，与当前密码不等价；原值处理，不 trim、规范化、截断、预哈希。
- Redis 按 userId 从首次有效格式请求起固定 900 秒，最多 5 次。任何 BCrypt 前消费额度；无效 JSON/字段不计数；原密码错、等价、CAS 冲突和存储失败不退额度；登录、换 Token、成功不重置。
- 仅更新 password_token、token_version+1、updated_at=UTC_TIMESTAMP(6)；同一事务提交，版本不能溢出，不自动重试或覆盖新快照。
- 成功 204、无响应体、Cache-Control:no-store、无新 Token。所有旧 Token 后续版本校验返回 TOKEN_REVOKED；不承诺中止已通过认证的请求或已开始传输。
- 部署前必须满足 docs/deployment/auth-version-binding.md 的全实例升级、旧登录请求排空条件；代码审核通过不代表环境已满足条件。
- 无迁移、无新配置/依赖、不修改 V1—V8、注册/登录实现、共享认证主体或过滤器，不实现 O15 管理员启动密码任务，不改全局 ObjectMapper。
- API、设计、计划、提交及审核材料中文；check 为基准，必要 MySQL/Redis 集成测试不得跳过后记为通过。

## 审核关注点

1. 登录后撤销竞态：旧验证和旧 Ticket 不得升级版本，最终复核后可能签出旧版本 Token，但受保护请求必须拒绝；任务 5 用真实改密验证。
2. MySQL 默认排序规则可能把不同大小写摘要视为相等；CAS 必须二进制比较，并分别测试摘要、版本独立不匹配；任务 3/5。
3. Redis null 回包、错误 key 类型、非规范计数、无 TTL 和脚本结果不确定不能放行或自动清空；任务 2/5。
4. 原密码兼容与新密码创建是不同边界：短密码、历史非 ASCII 原密码仍可验证，ASCII 新密码限制不能套用原密码；任务 1/3/5。
5. 提交确认失败可能已经更新，503 不能伪造未提交；摘要与版本必须一起回滚或一起生效，日志不能透传敏感 SQL/绑定值；任务 3/4/5。

## 文件结构与职责

源码均位于 src/main/java/org/example/music，表内省略该前缀；测试均位于 src/test/java/org/example/music。

| 操作 | 路径 | 职责 |
| --- | --- | --- |
| 新增 | user/dto/ChangePasswordRequest.java | 两个只写 password 字段、约束、脱敏 toString |
| 新增 | user/dto/ChangePasswordRequestDeserializer.java | 请求局部严格 token 解析 |
| 新增 | user/domain/UserPasswordSnapshot.java | Integer id、String passwordHash、long tokenVersion；脱敏字符串表示 |
| 新增 | user/service/PasswordChangeRateLimiter.java | 独立 Redis 原子配额，失败关闭 |
| 新增 | user/service/CurrentUserPasswordService.java | 顺序校验、限流、BCrypt、CAS 与短事务、故障翻译 |
| 新增 | user/controller/CurrentUserPasswordController.java | PUT 入口、主体、204/no-store、OpenAPI |
| 新增 | user/exception/OldPasswordIncorrectException.java | 400 OLD_PASSWORD_INCORRECT |
| 新增 | user/exception/NewPasswordSameAsOldException.java | 400 NEW_PASSWORD_SAME_AS_OLD |
| 新增 | user/exception/PasswordChangeConflictException.java | 409 PASSWORD_CHANGE_CONFLICT |
| 新增 | user/exception/PasswordChangeRateLimitedException.java | 429 PASSWORD_CHANGE_RATE_LIMITED、retryAfterSeconds |
| 新增 | user/exception/PasswordChangeRateLimitUnavailableException.java | 503 PASSWORD_CHANGE_RATE_LIMIT_UNAVAILABLE |
| 新增 | user/exception/PasswordChangeStoreUnavailableException.java | 503 PASSWORD_CHANGE_STORE_UNAVAILABLE、版本上限 |
| 修改 | user/repository/UserRepository.java | 密码投影与 CAS，复用 existsById |
| 修改 | common/exception/GlobalExceptionHandler.java | 六个新异常的映射，仅影响本接口 |
| 新增测试 | user/dto/ChangePasswordRequestTest.java | 严格解析、字段校验、敏感字符串脱敏 |
| 新增测试 | user/service/PasswordChangeRateLimiterTest.java | 脚本回包、Redis 异常翻译 |
| 新增测试 | user/service/CurrentUserPasswordServiceTest.java | 调用顺序、事务故障、冲突和版本上限 |
| 新增测试 | user/controller/CurrentUserPasswordControllerTest.java | HTTP 契约、no-store、错误、Retry-After |
| 新增测试 | user/PasswordChangeIntegrationTest.java | 真实 MySQL/Redis/BCrypt/JWT、Lua、CAS、撤销、并发 |
| 新增文档 | docs/api/user/change-password.md | 请求、响应、错误、限流、撤销、事务和双平台 curl |
| 新增材料 | docs/reviews/2026-11-02-change-password-review.md | 首轮独立审核材料，验证后填写 |

本计划随执行更新。不为复用严格绑定、时间映射或限流顺手重构既有接口。

### 任务 1：严格请求与内部密码快照

**文件：** 新增 ChangePasswordRequest、ChangePasswordRequestDeserializer、UserPasswordSnapshot 及 ChangePasswordRequestTest（完整目录见表）。

**接口：** `ChangePasswordRequest(String oldPassword, String newPassword)`；`UserPasswordSnapshot(Integer id, String passwordHash, long tokenVersion)`。前者仅用于 Controller→Service，后者只在 Repository→Service 内部使用，均覆盖 `String toString()` 隐藏明文/摘要。反序列化器继承既有 Jackson 3 ValueDeserializer<ChangePasswordRequest>。

- [ ] 先写 `should_accept_exactly_two_string_password_fields_in_any_order`、`should_reject_invalid_password_document`：解析顺序不限；拒绝缺体/空对象/null、未知或缺字段、重复/转义重复字段、数值/布尔/数组/对象字段、非对象根值、多根值。错误不包含输入。缺体由 MVC 测试补证。
- [ ] 写 `should_validate_old_and_new_password_with_different_policies`：old 1/7/8/128 合法、129/null/全空白非法；new 7/8/71/72/73、全空格、首尾空格、中文/emoji/NBSP/DEL/控制字符、JSON 转义字符；合法原值不变。写 `should_redact_password_objects`，两个字符串表示不含明文或摘要。
- [ ] 执行 `cmd /c gradlew.bat test --tests "org.example.music.user.dto.ChangePasswordRequestTest"`，确认新增行为尚未提供而失败；新增类型前的编译失败与行为失败分别如实记录。
- [ ] 实现局部 token 流解析：两个字段各出现一次且为字符串，结束后无额外根值；getNullValue 同样拒绝。复用 Bean Validation 的 @NotBlank/@Size，newPassword 增加完整 ASCII @Pattern；不改全局解析器。OpenAPI old/new 字段为 WRITE_ONLY、password 格式，additionalProperties=false；新密码 schema 严格全串 ASCII。
- [ ] 重跑测试全部通过；中文提交 `feat: 定义改密严格请求与内部快照`。

### 任务 2：独立 Redis 原子限流

**文件：** 新增 PasswordChangeRateLimiter、两个限流异常、PasswordChangeRateLimiterTest；创建 PasswordChangeIntegrationTest 的真实 MySQL 8.4/Redis 8.10.2 测试夹具和限流用例。

**接口：** `void PasswordChangeRateLimiter.consumeOrThrow(Integer userId)`；超限抛 `PasswordChangeRateLimitedException(long retryAfterSeconds)`，故障抛 `PasswordChangeRateLimitUnavailableException(Throwable cause)`。不传密码、Token 或客户端 ID，不返回“未知也允许”的决策。构造器注入 StringRedisTemplate。

- [ ] 单元测试 `should_fail_closed_on_unknown_or_failed_script_result`：null、空/错长度/错类型列表、非法状态码或 retry 值、DataAccessException 均为503异常，绝不当作允许；超限异常保存正秒数。
- [ ] 真实 Redis 测试 `should_allow_five_attempts_then_limit_without_extending_window`、`should_isolate_users_and_preserve_existing_ttl`：前5次通过，第6次拒绝；PTTL 只自然减少。`should_fail_closed_on_corrupt_counter` 覆盖 list/hash 类型、0/负数/6/前导零/Unicode 数字/非数字/过大计数、无 TTL、超900秒 TTL；不 DEL 修复放行。过期窗口测试以测试端 PEXPIRE 为0模拟实际过期，不 sleep。
- [ ] 真实并发 `should_admit_at_most_five_concurrent_attempts`：闩锁起跑，多线程同一 userId 恰5个获得许可，其余429；有超时与 finally 清理。
- [ ] 运行对应新类测试确认失败，再实现一个 Lua：key 固定 `music:auth:password-change:user:<id>`；不存在时 SET 1 PX 900000；已有必须是 string、规范计数1—5、0<PTTL<=900000。小于5则 INCR，保留 TTL；5则拒绝且不写。返回二元素整数列表 `{status,retrySeconds}`，status=0允许/1超限/2损坏；允许/损坏第二值0，超限为 ceil(PTTL/1000)、1—900。错误类型检查在 GET 前，所有状态检查在修改前。
- [ ] Java 严格检查脚本结果结构及允许的取值，损坏/异常/不确定均503；不重试、不重置、不补偿。保持 Lua 原子可见性，不声称 Redis 运行错误会自动回滚已执行写入。单元测试验证未知回包；真实测试验证 Lua 本身。
- [ ] 定向运行 `cmd /c gradlew.bat test --tests "org.example.music.user.service.PasswordChangeRateLimiterTest" --tests "org.example.music.user.PasswordChangeIntegrationTest"`，要求容器用例实跑；中文提交 `feat: 增加本人改密原子限流`。

### 任务 3：密码校验、CAS 与短事务

**文件：** 修改 UserRepository；新增 CurrentUserPasswordService、其余四个业务/存储异常、CurrentUserPasswordServiceTest；扩展 PasswordChangeIntegrationTest。

**接口：**

- `Optional<UserPasswordSnapshot> UserRepository.findPasswordSnapshot(Integer userId)`，JPQL 构造器投影，仅三字段。
- `int UserRepository.changePasswordIfSnapshotMatches(Integer userId, String previousHash, long expectedVersion, String newHash)`，@Modifying 原生 SQL；复用 `boolean existsById(Integer userId)`。
- `void CurrentUserPasswordService.changePassword(AuthenticatedUserPrincipal actor, ChangePasswordRequest request)`；构造器注入 UserRepository、PasswordHasher、PasswordChangeRateLimiter、PlatformTransactionManager；内部 TransactionTemplate 仅覆盖 CAS/存在性查询/提交。Service 不标注覆盖整方法的写事务。

- [ ] 写 `should_verify_old_password_before_testing_equivalence_or_hashing`：顺序为限流→快照→matches(old)→matches(new)→hash(new)→短事务 CAS；原密码错立即400且无新密码比较/哈希/更新，等价立即400且无哈希/更新。不要 hash 后比较随机盐摘要。
- [ ] 写 `should_translate_only_storage_failures_to_store_unavailable`：快照/更新/存在性查询 DataAccessException、事务启动/提交 TransactionException→503；业务异常不误包装；未知哈希故障仍500，不把任意 RuntimeException 伪装数据库故障。限流失败发生在读取密码快照和任何 BCrypt 前。
- [ ] 写 `should_distinguish_missing_account_from_snapshot_conflict`：快照缺失401 TOKEN_REVOKED；CAS0且存在409、不存在401；不重试；返回行数异常不能当成功。`should_refuse_version_overflow`：Long.MAX_VALUE 拒绝更新为503，不回绕。`should_keep_redis_attempt_consumed_on_later_failure` 用真实 Redis 补证。
- [ ] 运行 Service 测试确认失败，再实现投影、异常和协调流程；版本上限在写更新前拒绝。事务 execute 返回且提交确认后才返回成功；捕获 DataAccessException/TransactionException 的边界覆盖读、启动、回调和提交。
- [ ] 实现原生 CAS：`UPDATE users SET password_token=:newHash, token_version=token_version+1, updated_at=UTC_TIMESTAMP(6) WHERE id=:userId AND CAST(password_token AS BINARY)=CAST(:previousHash AS BINARY) AND token_version=:expectedVersion AND token_version<9223372036854775807`。仅更新三列。版本上限由 Java 与 SQL 双重保护；同事务 CAS0 后 existsById 判别，不自动重读覆盖。
- [ ] MySQL 测试 `should_update_only_password_version_and_utc_timestamp`：整行比对三列变化，其他账户不变；SQL 读 UTC 文本而非只依赖 ORM 同时偏移的写读。分别证明 wrong ID/仅摘要大小写改变/仅版本改变不能更新。`should_roll_back_hash_and_version_together`：真实事务内强制故障验证两列回滚；Service 单元注入提交故障证明不返回成功，不将该单元测试声称为真实“已提交后丢确认”的网络复现。
- [ ] 重跑 Service 与集成类，全部通过；中文提交 `feat: 实现本人改密校验与版本条件更新`。

### 任务 4：HTTP、错误处理与 API 文档

**文件：** 新增 CurrentUserPasswordController、CurrentUserPasswordControllerTest、docs/api/user/change-password.md；修改 GlobalExceptionHandler。

**接口：** `ResponseEntity<Void> CurrentUserPasswordController.changePassword(@Valid @RequestBody ChangePasswordRequest request, @AuthenticationPrincipal AuthenticatedUserPrincipal actor)`；PUT `/api/v1/users/me/password`，consumes application/json，调用任务3 Service 后返回204/no-store。复用 CurrentUserRevokedException→401，不调整 SecurityConfig。

- [ ] 写 Controller 测试：成功204且正文为空/no-store；未认证401；注入主体只能传主体 ID；JSON 伪造 ID/未知字段/重复键/错误类型400且 Service 无调用。这里的 authentication(...) 只证明契约，不代替任务5真实过滤链。
- [ ] 写异常映射断言：OLD_PASSWORD_INCORRECT、NEW_PASSWORD_SAME_AS_OLD→400；PASSWORD_CHANGE_CONFLICT→409；PASSWORD_CHANGE_RATE_LIMITED→429且 Retry-After=异常中的正秒数；两个存储异常→503。断言 application/problem+json、下划线 type、code、no-store、无密码/摘要/SQL/Redis key；未知错误沿用500。
- [ ] 执行 `cmd /c gradlew.bat test --tests "org.example.music.user.controller.CurrentUserPasswordControllerTest"`，确认失败；再实现 Controller 与六个新映射。复用全局 response/problem 装饰，不更改其他接口映射。
- [ ] 故障日志含 traceId 和实际异常类型；不得直接把原 SQL/Redis 异常链交给 logger。在 GlobalExceptionHandler 内用私有 `Throwable sanitizedPasswordChangeFailure(Throwable failure)` 构造通用文案、保留源堆栈、不带原 message/cause/suppressed 的诊断异常，仅供本接口两个503映射使用，不扩大到全项目。测试注入含密码/摘要的异常消息并捕获日志，验证不泄漏。
- [ ] 编写中文 API 文档及 OpenAPI：Bearer、本人与两角色、字段与历史兼容、全部错误码、204无新Token/所有旧登录撤销、5次900秒、Retry-After、冲突不重试、提交503不保证未生效、已有请求非中途取消；说明 JWT 解析/过期沿用资源服务器现状，可能无 ProblemDetail。POSIX/PowerShell curl 用 ACCESS_TOKEN、PASSWORD_CHANGE_BODY_FILE 占位，不含真实秘密。
- [ ] 重跑 Controller/DTO/Service 定向测试通过；中文提交 `feat: 暴露本人改密接口与错误契约`。

### 任务 5：真实认证、撤销与并发验证

**文件：** 扩展 PasswordChangeIntegrationTest；不修改 AuthVersionBindingIntegrationTest 等已通过的前置测试来掩盖失败。

**接口：** @SpringBootTest＋@AutoConfigureMockMvc＋真实 MySQL/Redis/BCrypt；从现有登录流程或 JwtTokenService 生成真实签名 JWT，经过实际 TokenVersionAuthenticationFilter。故障/闩锁 spy 只作受控注入，正常路径依赖真实服务。

- [ ] 先添加以下测试并定向运行，失败时修复当前接口的真实缺陷，不跳过断言或把外部服务替换为 mock。
- [ ] `should_revoke_current_other_device_and_other_role_tokens_after_change`：USER、ADMIN 登录身份各成功改本人；成功前签出多设备/不同登录角色旧 Token，204后访问 GET /api/v1/users/me 均401 TOKEN_REVOKED；新密码登录取得 Token 可访问，旧密码新验证失败。重复原 Token 改密401，不要求第二次204。JSON伪造 ID400；查询/头部伪造不改变本人。
- [ ] `should_reject_invalid_authentication_before_password_change`：缺失、过期、错误签名、非法 subject/role/version 的真实 JWT 分别按现有链拒绝，改密 Redis key 和密码行不变；认证快照查询故障503 AUTH_STATE_STORE_UNAVAILABLE。JWT 解码错误不强求现有链尚未提供的 ProblemDetail 格式。
- [ ] `should_accept_compatible_old_password_and_reject_invalid_new_password_before_rate_limit`：真实短旧密码与非 ASCII 旧密码夹具能验证；1/7字符旧密码账户用现有可用认证方式或真实签名JWT进入，不放宽登录 DTO。超72字节但<=128字符的不匹配 old 输入，先验证 BCrypt 样例确实不匹配，再断言 OLD_PASSWORD_INCORRECT。new 的字符/长度/重复键等失败不建立改密 key、不执行密码运算。
- [ ] `should_preserve_counter_across_tokens_and_success`：同用户不同 Token 同桶，其他用户隔离；先4次错误后一次成功计数5，新密码重新登录再请求429，成功/重新登录不清空或延长窗口。Redis故障或损坏→503，无 BCrypt/密码写入；原密码错/相同/CAS冲突/DB故障后计数不补偿。
- [ ] `should_allow_only_one_cas_for_concurrent_password_changes`：闩锁使两请求读取同一快照并在事务外完成哈希，仅一204另一409，版本只加1；保证竞态落在认证之后，否则允许401不能替代409分支验证。对角色更新并发同样验证不覆盖版本/角色。账户在认证后消失→401；用受控hook而非 sleep。
- [ ] `should_prevent_old_verification_and_ticket_from_upgrading_after_real_change`：密码验证完成、Ticket签发/消费后分别暂停，真实改密递增版本，再恢复登录。复核前失配拒绝；最终复核后签出的旧版本 JWT 后续受保护访问401。复用前置5e8ceba语义，不新增登录版本回填或重试。
- [ ] `should_keep_email_code_login_available_after_change`：使用改密后新获取的真实LOGIN验证码，经既有发送/消费流程登录成功；SMTP仅捕获邮件验证码，不 mock Redis。证明改变密码不禁用该认证方式，不承诺未消费旧验证码被撤销。
- [ ] 所有并发使用 CountDownLatch/CyclicBarrier、Future 超时和 finally 释放/关闭；每例重建账户并清理自己测试容器内的相关 Redis key。测试数据、日志、夹具均不含真实凭据。
- [ ] 执行 `cmd /c gradlew.bat test --tests "org.example.music.user.PasswordChangeIntegrationTest" --tests "org.example.music.user.AuthVersionBindingIntegrationTest" --tests "org.example.music.user.UserRegistrationIntegrationTest"`，容器真实执行、零失败、必要用例零跳过；中文提交 `test: 验证改密撤销与并发一致性`。

### 任务 6：完整验证与独立审核交付

**文件：** 本计划进度与 docs/reviews/2026-11-02-change-password-review.md；本任务完成前不开始下一接口。

- [ ] 自查 git diff --check、相对 e5b5c96 的全部变更含 untracked；核对文件清单、单接口、无迁移/依赖/秘密/无关格式化，文档与实现完全一致。
- [ ] 执行 `.\gradlew.bat check`，若未真实执行测试则 cleanTest check；可设 MINIO_INTEGRATION_TESTS=true 做全量既有存储回归，但不得修改 storage/track。按本次 XML 汇总通过/失败/错误/跳过，列明所有跳过及原因，必要 MySQL/Redis 未运行不得声称完成。
- [ ] 审核材料包含唯一 Method/Path、认证/角色/本人归属、全部声称行为、完整文件清单、无迁移、API文档、实际测试命令与数量、实现提交和基线、curl、提交不确定性与部署前置条件；首次反馈“不涉及”。不预填通过数量或把旧报告当新执行。
- [ ] 更新计划执行记录并中文提交，交用户提供 Claude 独立审核；自验通过不等于 accepted。最多3轮，逐条核实反馈，未解决阻断不得宣称完成；推送以用户授权为准。

## 计划自审

任务1对应请求/兼容/保密，任务2对应独立Lua限流，任务3对应密码流程/CAS/事务，任务4对应HTTP/错误/文档，任务5对应真实认证撤销和并发，任务6对应构建与单接口审核。所有新类、方法、错误码与依赖来源明确；不重新实施已 accepted 前置工作，不以设计中的历史状态阻塞已授权计划。业务规则全部沿用既有设计，尚待用户确认本计划后编码。
