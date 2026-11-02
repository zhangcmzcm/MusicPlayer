# 注册密码边界修复实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 执行方式沿用项目约定：Codex 唯一实现，Claude 独立只读审核，不派发实现子代理。通用执行子技能若本地不存在，不声称已调用；按本计划原生执行。书面计划已获用户确认；实现与自验完成，待 Claude 独立审核。

**Goal:** 修复 `POST /api/v1/auth/register` 密码校验与 BCrypt 上限不符的问题，创建密码仅接受非全空格的 8—72 个 ASCII 可打印字符。

**Architecture:** 在现有 UserRegisterRequest 上组合 Bean Validation 约束，经既有 @Valid 入口和统一异常处理返回 400。注册事务、PasswordHasher 与登录实现沿用现状，通过 MVC 契约测试与真实 MySQL/Redis/BCrypt HTTP 集成证明边界和副作用。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Bean Validation、springdoc、JUnit 5、MockMvc、Testcontainers MySQL 8.4/既有 Redis 7.4 测试容器、BCrypt、Gradle Wrapper；不新增依赖。

**Spec:** [注册密码修复设计](../specs/2026-11-02-registration-password-design.md)，提交 aa45d12，用户已确认书面设计。实施前基线 main @ aa45d12。

## 全局约束

- 仅修改 POST /api/v1/auth/register 的密码创建边界：U+0020—U+007E、长度 8—72、非全空格；不增加字母/数字/符号组合要求。
- 合法首尾空格原样哈希，不 trim、不规范化、不截断、不预哈希。
- 非法密码返回 400 VALIDATION_FAILED，type 为 urn:problem:music:validation_failed；不回显密码，不调用注册 Service。
- 保持公开注册、201、USER 角色、响应 DTO、邮箱配对与验证码事务语义、JSON 绑定和限流现状。
- 无迁移、新配置、新错误码、新依赖，不改 BCrypt、登录、管理员初始化或实现改密；V1—V8 不动。
- API 文档、提交及计划说明使用中文；基准命令 `.\gradlew.bat check`，真实 MySQL/Redis 测试跳过不得当作通过。

## 审核关注点

1. 全空格长度合法仍须拒绝；首尾空格须保留：任务 1 契约和任务 2 摘要验证覆盖。
2. JSON 转义控制字符或尾部换行不能绕过字符范围：任务 1 覆盖解码后的输入。
3. 恰好 72 UTF-8 字节的中文仍不符合 ASCII 政策：任务 1 拒绝 24 个普通汉字。
4. 非法密码不得触发有效验证码的验证或消费：任务 1 证明 Service 未调用，任务 2 比较 Redis 状态。
5. 已有非 ASCII 密码不因本次规则被禁止登录：任务 2 用真实已有摘要验证登录路径，禁止把创建约束加到登录 DTO/Hasher。

## 文件与职责

| 操作 | 文件 | 职责 |
| --- | --- | --- |
| 修改 | src/main/java/org/example/music/user/dto/UserRegisterRequest.java | password 长度、字符校验及 OpenAPI |
| 修改 | src/test/java/org/example/music/user/controller/AuthControllerTest.java | 请求校验、错误契约、Service 调用边界 |
| 修改 | src/test/java/org/example/music/user/UserRegistrationIntegrationTest.java | 增加 MockMvc HTTP 用例，复用现有真实容器与数据清理 |
| 修改 | docs/api/auth/register-user.md | 创建规则、错误场景、兼容说明 |
| 新增 | docs/reviews/2026-11-02-registration-password-review.md | 验证完成后填写独立审核材料 |

本计划文件随执行更新进度；既有改密设计已在 aa45d12 同步新密码规则，无须再次修改。无新增生产组件，不修改 Controller、Service、Repository 或 GlobalExceptionHandler。

### 任务 1：落实请求边界与文档

**接口：** 输入 `UserRegisterRequest(String username, String password, String email, String code)`；输出仍为原 UserRegisterResponse 或既有 ProblemDetail。校验通过后调用 `UserRegistrationService.register(UserRegisterRequest)`，失败时不调用。

- [x] 在 AuthControllerTest 添加参数化 `should_reject_registration_password_outside_ascii_policy`：输入缺失/null/空串/8 个空格/7、73、128 个 ASCII 字符，以及中文、emoji、组合 emoji、é、全角符号、NBSP、DEL、NUL、TAB、CR、LF、合法串尾部 LF、24 个普通汉字；断言 400、application/problem+json、code、type、errors.password，verifyNoInteractions(userRegistrationService)。请求用 JSON 序列化构造，缺失单独构造，控制字符经合法 JSON 转义发送。
- [x] 添加 `should_preserve_valid_registration_password`：8/71/72 字符、英文大小写/数字/全部可打印符号、含首尾空格且非全空格分别通过 201；捕获传入 Service 的请求并断言 password 与原字符串完全一致。不把密码相同字符的纯数字串误当成 JSON 数字。
- [x] 添加 `should_not_echo_rejected_password`：用唯一可识别的非法密码断言响应正文不含其原文或 JSON 编码形式；不依赖固定的多约束错误优先顺序。
- [x] 执行 `cmd /c gradlew.bat test --tests "org.example.music.user.controller.AuthControllerTest"`，记录修改前失败；预期 73 字符/中文等用例暴露校验缺失，不能因未 stub Service 导致的空响应或 500 就声称复现了 BCrypt 故障。
- [x] 修改 UserRegisterRequest.password：保留 @NotBlank，@Size 改为 min=8/max=72，新增 @Pattern，正则完整匹配 `[\x20-\x7E]+`（Java 字符串双反斜杠转义）；错误消息只描述范围。@Schema 更新 minLength=8/maxLength=72、相同 pattern 和中文描述。不增加自定义验证器或额外业务字段。
- [x] 更新注册 API 文档密码字段与 400 场景：说明可打印 ASCII 范围、全空格拒绝、保留首尾空格、不要求字符种类组合及旧密码登录兼容。示例仅用虚构值。
- [x] 重跑上述契约测试，要求全部通过；检查原登录与注册契约用例仍通过。
- [x] 提交 DTO、契约测试和 API 文档，中文提交信息 `fix: 对齐注册密码字符规则与BCrypt边界`。

### 任务 2：验证真实 HTTP 注册与持久化行为

**接口：** 复用任务 1 的 DTO 契约；在 UserRegistrationIntegrationTest 添加 @AutoConfigureMockMvc 并注入 MockMvc，保持已有 Service 测试。使用现有 `seedCode(String email, String code)`、UserRepository、StringRedisTemplate 和真实 `PasswordHasher.matches(String rawPassword, String encodedPassword)`。

- [x] 增加 `should_register_boundary_password_through_http`，分别发起 8/72 字符密码的公开 POST，断言 201、实际记录角色 USER、数据库摘要不同于明文且 matches 原密码，响应无 password/passwordToken/code。每例独立用户名并沿用每例清理。
- [x] 增加 `should_preserve_password_spaces_in_stored_hash`：以含首尾空格的合法密码走 HTTP 注册，验证原值匹配、去空格后的值不匹配。
- [x] 增加 `should_reject_invalid_password_without_touching_account_or_code`：先 seedCode 再保存 Redis hash 全字段及正 TTL；发送 73 字符 ASCII 或含非 ASCII 的密码（其他字段合法且验证码正确）；断言 400、数据库账户数不变、Redis hash 相同、TTL 仍为正且不增大。无需精确比较自然递减的毫秒 TTL，不 sleep。
- [x] 增加 `should_keep_existing_non_ascii_password_login_compatible`：直接用真实 BCrypt 为长度合法的非 ASCII 历史密码创建账户，HTTP 调用现有 USERNAME_PASSWORD 登录，断言 200 AUTHENTICATED；创建历史夹具不能通过已收紧的注册请求。保持现有真实登录所需依赖，不为此修改生产登录实现。
- [x] 运行 `cmd /c gradlew.bat test --tests "org.example.music.user.UserRegistrationIntegrationTest" --tests "org.example.music.user.controller.AuthControllerTest"`，要求真实 MySQL/Redis 容器用例全部执行通过；若首次失败，先判断是实现缺陷还是夹具问题，不能删除业务断言、换用 H2 或 mock 外部依赖。
- [x] 提交集成测试，中文提交信息 `test: 验证注册密码边界与验证码无副作用`。

### 任务 3：基准验证与单接口送审

**交付接口：** 所有生产变更只服务 POST /api/v1/auth/register；审核材料引用实际提交号和真实测试结果。

- [x] 检查 git diff --check、完整提交范围及 untracked；确认无无关格式化、迁移、敏感数据或第二接口实现。
- [x] 执行 `.\gradlew.bat check`。若相关测试被标记 UP-TO-DATE，使用 `.\gradlew.bat cleanTest check` 取得本次完整执行结果。统计 build/test-results/test/TEST-*.xml 的 tests/failures/errors/skipped，跳过逐项列明，必要 MySQL/Redis 集成未执行则不能宣称完成。不因本任务主动改动或扩大 storage/track 测试。
- [x] 编写审核材料，含 Method/Path、公开权限、ASCII/长度与无副作用行为、全部变更文件（含本计划和审核文档）、无迁移、API 文档路径、实际命令和通过/失败/跳过数量、基线与实现提交、虚构凭据 curl、无未决业务及首次反馈“不涉及”。
- [x] 更新本计划完成记录并以中文提交审核材料；交由用户提供 Claude 独立审核，收到 accepted 前不宣称修复完成或开始下一接口，不自动推送。

## 计划自审

设计各节已覆盖：任务 1 负责创建约束、错误与文档；任务 2 验证真实 HTTP、BCrypt、数据库与验证码副作用、历史兼容；任务 3 完成基准验证与单接口审核。没有新增接口、算法迁移或注册事务重构。执行方式沿用 Codex 实现、Claude 人工送审；用户已确认计划并授权执行。

## 执行与验证记录

- 计划确认后实施，主修复提交 29d3f3e，测试与 OpenAPI 补充提交 37c294f。
- 原 DTO 上运行新增契约测试：34 项中 15 项失败，暴露规则缺口；此阶段不声称复现真实 BCrypt 故障。
- Docker 最初未运行，集成测试被跳过；启动 Docker 后强制重跑，两类通过 47 / 失败 0 / 错误 0 / 跳过 0（54 秒）。
- 最终完整命令：设置 MINIO_INTEGRATION_TESTS=true 后执行 .\gradlew.bat cleanTest check；BUILD SUCCESSFUL，8 分 28 秒，XML 汇总 802 通过 / 0 失败 / 0 错误 / 0 跳过，81 类。
- OpenAPI pattern 显式起点和严格终点，避免 JSON Schema 搜索语义或尾部换行放宽字符描述；运行时仍用 Bean Validation 完整匹配。
- 本地未发现 executing-plans 子技能文件，按已约定的 Codex 原生执行方式完成，没有派发子代理或声称调用缺失技能。
- 首轮审核材料：docs/reviews/2026-11-02-registration-password-review.md。尚未获 Claude accepted；没有开始下一个接口，也未推送。