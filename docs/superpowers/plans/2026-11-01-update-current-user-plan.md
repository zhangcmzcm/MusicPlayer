# 修改个人资料接口实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.
>
> 执行方式按本项目 AGENTS.md 与已建立的协作约定：Codex 是唯一实现方，Claude 独立只读审核，不派发实现子代理。不声称已调用当前环境不存在的技能。

**Goal:** 仅实现 `PATCH /api/v1/users/me`，已登录本人严格提交 username 修改资料，邮箱和密码不在此接口修改。

**Architecture:** 新增请求 record 及 DTO 局部严格反序列化器；Controller 仅处理 HTTP、校验和认证主体。Service 通过短写事务执行定向 UPDATE、白名单投影与响应组装，复用 CurrentUserResponse；唯一性由真实 MySQL 索引保证。

**Tech Stack:** Java 21、现有 Gradle Wrapper、Spring MVC/Security/Data JPA/Validation、既有 Jackson 3、MySQL 8.4、Redis 集成测试、JUnit 5/MockMvc/Testcontainers/springdoc。不新增依赖。

**Spec:** [已确认设计](../specs/2026-11-01-update-current-user-design.md)，设计提交 `80b441f`，限流补充提交 `e89b9eb`。

## 全局约束

- 单接口 PATCH /api/v1/users/me；不改 GET、注册、登录及其他模块的契约。
- 仅接收 username：3—20 位 ASCII 字母、数字或下划线；保留大小写，不 trim、不转小写。
- 请求未知字段、重复键、非字符串、非对象或缺少字段均为 400 VALIDATION_FAILED；不修改全局 ObjectMapper。
- 身份仅来自 AuthenticatedUserPrincipal.userId；ID 保持 Integer，USER/ADMIN 登录身份均可修改本人。
- UPDATE 仅修改 username、实际变化时的 updated_at；时间由数据库 UTC_TIMESTAMP(6) 提供。完全相同的名字不更新时间，大小写变化允许更新。
- 他人用户名按现有 utf8mb4_0900_ai_ci 与 ix_users_username 唯一索引判断，不引入版本号或旧用户名别名。
- 不递增 tokenVersion，不修改邮箱、密码摘要、角色、创建时间、最后登录时间，不保存整份 Entity。
- 无迁移、无新依赖、无专用限流或改名冷却期，不新增 429 业务分支，不假定认证或部署层已提供改名限流。
- 所有文档与提交说明使用中文；不暂存用户既有 .gitignore 修改，不提交凭据和临时日志。
- 最终基准 `.\gradlew.bat check`，测试未执行时使用 cleanTest；必要集成测试跳过不能视为通过。

## 审查重点

1. JSON 键经转义后仍可能重复，树模型会覆盖前值：任务 1 用 token 流和出现次数判断，覆盖转义键和同值重复键。
2. 非法绑定可能绕过 Bean Validation 或变成 500：任务 1、3 用真实 MVC 请求断言统一 400，且更新 Service 不被调用。
3. 修改后投影失败可能在错误事务边界下留下写入：任务 4 在真实事务内注入投影故障，验证 username 与 updated_at 均回滚。
4. MySQL 受影响行数为 0 同时可能代表无变化和账户消失：任务 2、4 使用事务内投影区分，不按 0 直接返回 401。
5. 旧认证主体或并发改名可能覆盖安全列：任务 4 用真实 JWT、并发执行及整行差异验证只更新两列、既有 Token 仍有效。

## 文件边界

下列路径相对仓库根 `D:/code/Java-code/projects/music`；仅在实现这些职责所需范围内修改。

| 创建/修改 | 文件 | 职责 |
| --- | --- | --- |
| 创建 | src/main/java/org/example/music/user/dto/UpdateCurrentUserRequest.java | username record、字段约束、Schema、局部反序列化注解 |
| 创建 | src/main/java/org/example/music/user/dto/UpdateCurrentUserRequestDeserializer.java | 仅该 DTO 的严格 token 绑定 |
| 创建 | src/main/java/org/example/music/user/controller/CurrentUserUpdateController.java | PATCH /me、@Valid、主体与 HTTP/OpenAPI |
| 创建 | src/main/java/org/example/music/user/service/CurrentUserUpdateService.java | 写事务、专用 UPDATE 错误分类、投影与 DTO |
| 创建 | src/main/java/org/example/music/user/exception/UserProfileUpdateUnavailableException.java | 写接口专用存储异常 |
| 修改 | src/main/java/org/example/music/user/repository/UserRepository.java | 新增定向用户名 UPDATE |
| 修改 | src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java | 仅新增写接口 503 映射与含 throwable 的日志 |
| 创建 | src/test/java/org/example/music/user/dto/UpdateCurrentUserRequestDeserializerTest.java | 局部绑定的结构、重复键和类型测试 |
| 创建 | src/test/java/org/example/music/user/service/CurrentUserUpdateServiceTest.java | 事务、错误分类、角色与无变化语义 |
| 创建 | src/test/java/org/example/music/user/controller/CurrentUserUpdateControllerTest.java | HTTP、绑定失败、状态/JSON/缓存契约 |
| 创建 | src/test/java/org/example/music/user/CurrentUserUpdateIntegrationTest.java | 真实 MySQL、Redis、JWT、回滚与并发 |
| 创建 | docs/api/user/update-current-user.md | 中文 API 文档和 curl |
| 创建 | docs/reviews/2026-11-01-update-current-user-review.md | 实现后准备的单接口审核请求 |
| 修改 | 本计划文件 | 实现时记录完成步骤与实际结果 |

复用 CurrentUserResponse、UserProfileSnapshot、findProfileSnapshot、CurrentUserRevokedException、UserAlreadyExistsException。完成且 accepted 后再按既有方式登记 docs/tasks/backend-roadmap.md 的 U02；不提前宣称已完成。

## 任务 1：请求局部严格绑定

**文件：** 请求 DTO、反序列化器和 DeserializerTest，见文件边界。

**接口：**
- 产出 `UpdateCurrentUserRequest(String username)`，字段 @NotBlank、@Size(min=3,max=20)、@Pattern(regexp="^[A-Za-z0-9_]+$")，Schema 明确限制。
- DTO 用 `tools.jackson.databind.annotation.JsonDeserialize` 指向 `UpdateCurrentUserRequestDeserializer`。
- 反序列化器继承 `tools.jackson.databind.ValueDeserializer<UpdateCurrentUserRequest>`；实现 `deserialize(tools.jackson.core.JsonParser, tools.jackson.databind.DeserializationContext): UpdateCurrentUserRequest` 与 `getNullValue(DeserializationContext): UpdateCurrentUserRequest`。本地依赖字节码已核对 Jackson 3 的上述 API，不能混用 Jackson 2 的 JsonDeserializer。

- [x] 写失败测试：合法单字段对象保留 Alice_123；数字 123、true、null、[]、空对象、缺键、未知键、同值重复键和转义形式的重复 username 键均拒绝。反序列化错误不包含客户端传入的敏感值。
- [x] 使用 `tools.jackson.databind.json.JsonMapper` 构造测试用 mapper，运行 `.\gradlew.bat test --tests "org.example.music.user.dto.UpdateCurrentUserRequestDeserializerTest"`，先确认缺实现导致失败。
- [x] 实现局部 token 流绑定：必须对象、仅 username 属性出现一次、值必须 VALUE_STRING；拒绝缺键、额外属性、第二个根值和 null 根值。使用 DeserializationContext.reportInputMismatch 报告普通绑定错误，不启用全局严格解析、不先读为覆盖重复键的树模型。
- [x] 将字符串长度及允许字符规则交给入口 Bean Validation，不在绑定层规范化用户名；不要给其他 DTO 注册反序列化器。
- [x] 重跑上述测试全部通过；本任务验证通过，归入最终单接口原子提交。

## 任务 2：原子更新、错误分类与响应组装

**文件：** UserRepository、CurrentUserUpdateService、UserProfileUpdateUnavailableException、ServiceTest。

**接口：**
- 消费任务 1 的 UpdateCurrentUserRequest、既有 AuthenticatedUserPrincipal 和 findProfileSnapshot(Integer): Optional<UserProfileSnapshot>。
- 产出 `UserRepository.updateUsernameIfChanged(Integer userId, String username): int`。
- 产出 `CurrentUserUpdateService.updateCurrentUser(AuthenticatedUserPrincipal actor, UpdateCurrentUserRequest request): CurrentUserResponse`。
- Service 构造器消费 UserRepository、PlatformTransactionManager；新增异常构造器 `UserProfileUpdateUnavailableException(Throwable cause)`。

- [x] 编写失败测试：UPDATE 目标仅 actor.userId；在同一非只读事务中先 UPDATE 再投影，0 行且有投影成功，0 行且无投影抛 CurrentUserRevokedException。账户角色来自投影，登录角色来自主体；空邮箱与时间、UTC 微秒转换沿用 GET 语义。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.service.CurrentUserUpdateServiceTest"` 确认失败。
- [x] Repository 新增 @Modifying 参数化 native UPDATE，固定 SQL 只作用于两个允许列；不调用 findById、save、saveAndFlush、角色更新或最后登录更新。

```sql
UPDATE users
SET username = :username, updated_at = UTC_TIMESTAMP(6)
WHERE id = :userId
  AND CAST(username AS BINARY) <> CAST(:username AS BINARY)
```

- [x] Service 使用 TransactionTemplate 非只读事务包围 UPDATE、投影和 DTO 组装。不得预先读取资料建立旧快照，不在事务外调用 CurrentUserService；复用投影并按 GET 已验证的 UTC 文本转换方式组装 CurrentUserResponse。
- [x] 只在调用 updateUsernameIfChanged 的局部 catch 中检查异常 JDBC cause/nextException 链。仅 SQLException 的 vendorCode=1062 且 SQLState=23000 时，抛 `new UserAlreadyExistsException(USERNAME_ALREADY_EXISTS, "用户名已占用")`；不得把投影失败、提交失败或其他完整性故障归为重名。不在失败事务里继续读写，不暴露索引或占用者身份。
- [x] 在 TransactionTemplate.execute 外层将其余 DataAccessException、TransactionException 翻译为 UserProfileUpdateUnavailableException；保留 CurrentUserRevokedException 与用户名冲突的类型。不自动重试死锁或锁超时。
- [x] 补测试：正确的 1062/23000 链得到冲突，1062/其他 SQLState、其他 errorCode、无 JDBC cause 的完整性异常均为 503 异常；投影伪造 1062 仍为存储故障，不能误报用户名冲突。
- [x] 补事务启动/提交失败、投影失败及组装异常测试；验证事务调用完成前不返回 DTO，失败不返回假成功、不调用远程服务。
- [x] 重跑本任务与任务 1 的定向测试全部通过后，本任务验证通过，归入最终单接口原子提交。

## 任务 3：HTTP 入口、统一错误与 API 文档

**文件：** CurrentUserUpdateController、GlobalExceptionHandler、ControllerTest、update-current-user.md；完善请求 Schema。

**接口：** 消费任务 2 的 updateCurrentUser；产出 `PATCH /api/v1/users/me`、application/json，请求 @RequestBody @Valid UpdateCurrentUserRequest、主体 @AuthenticationPrincipal AuthenticatedUserPrincipal，返回 ResponseEntity<CurrentUserResponse>。

- [x] 写失败 MVC 测试，使用真实 HTTP 消息转换器读取原始 JSON。断言缺体、空对象、非对象、重复键、未知字段、null、错误类型、非法字符和长度 2/21 全为 400 VALIDATION_FAILED，且 Service 从未被调用。
- [x] 覆盖长度 3/20、仅数字与仅下划线的合法用户名；覆盖空白、首尾空格、制表符、换行、中文、非法符号、Username 拼写错误、额外字段即使为 null 也拒绝，禁止用户 ID、邮箱、密码和角色混入。
- [x] 使用现有 WebMvcTest 注入最终主体进行 HTTP 契约测试，明确这不能证明真实 JWT 过滤链；运行 `.\gradlew.bat test --tests "org.example.music.user.controller.CurrentUserUpdateControllerTest"` 先确认失败。
- [x] 实现 Controller：consumes=application/json，200 ResponseEntity.cacheControl(noStore)，@Valid，主体参数 OpenAPI 隐藏；无目标 ID 或版本参数。额外查询参数和自定义身份头忽略，只将认证主体交给 Service。
- [x] GlobalExceptionHandler 只新增 UserProfileUpdateUnavailableException 的 503 映射，code=USER_PROFILE_UPDATE_UNAVAILABLE，type=urn:problem:music:user_profile_update_unavailable，通用 detail="个人资料暂时无法更新，请稍后重试"。沿用 traceId、exceptionType，并把 exception 作为 SLF4J throwable 末参。
- [x] 验证局部绑定错误经 Spring MVC 转为 HttpMessageNotReadableException，复用已有 400；不得以新增宽泛 Jackson 异常全局处理改变其他接口。验证既有 401 TOKEN_REVOKED 和 409 USERNAME_ALREADY_EXISTS 映射可直接复用；detail 仅为“用户名已占用”。
- [x] 契约测试精确六键、显式 null、两角色、UTC、no-store；未认证 401、账户消失 401、重名 409、写存储 503 的 code/type/媒体类型及错误 no-store，底层异常内容不出现在响应中。
- [x] 补 Tag、Operation、ApiResponse、Schema；API 文档记录不撤销 Token、旧名称无别名保留、大小写匹配、同值无变化、并发后写覆盖、提交确认失败可能已生效、无独立限流，以及 JWT 签名/过期响应的既有例外。列出 200/400/401/409/503/500，媒体类型不支持沿用现有框架处理，不在本次重构全局错误。
- [x] 写 POSIX 与 PowerShell curl，仅用环境变量 ACCESS_TOKEN；无真实密码、验证码或 Token。文档与实现一致后重跑前三项任务的定向测试，本任务验证通过，归入最终单接口原子提交。

## 任务 4：真实认证、MySQL 并发与改名登录验证

**文件：** CurrentUserUpdateIntegrationTest；仅修复本接口范围内由测试发现的问题。

**接口与环境：** 参考 CurrentUserIntegrationTest 的 SpringBootTest、AutoConfigureMockMvc、真实 JwtTokenService/Bearer 头，以及 UserLoginIntegrationTest 的 MySQL 8.4/Redis 8.10.2 容器与 DynamicPropertySource。每例重置本容器用户及角色历史，隔离 Redis 认证/限流 key；用户密码通过真实 PasswordHasher 创建。不得用 authentication(...) 或 jwt() 后处理器代替真实签名 JWT 集成链路。

- [x] 编写真实 PATCH 成功用例：USER、ADMIN 以 USER、ADMIN 以 ADMIN 三种组合；验证六字段与字段来源，GET 读取更新值，原 Token 仍可用。
- [x] 用原始 SQL 固定 updated_at 后测试完全相同值重复请求，时间不变；Alice→alice 更新并保存大小写，其他账户占用 Alice 时改 alice 返回 409。读取 UTC 时间使用 SQL 文本，避免 ORM 时间写读相互抵消偏移造成误判。
- [x] 更新前后对比完整账户行，只允许 username、实际改变时的 updated_at 不同；保留 email/password_token/user_role/token_version/created_at/last_login_at，其他账户全行不变。created_at 的 null 与微秒响应保持 GET 契约。
- [x] 使用 A 的真实 Token 携带 B 的查询参数、身份头，仍只改 A；JSON 中包含 B 的 ID、email/password/role 等则 400，两账户都不变。未认证、失效版本、过期、错误签名、账户删除沿用既有安全链拒绝；角色变更后旧 Token 仍按既有规则撤销。
- [x] 两个真实账户、两个独立事务通过栅栏同时竞争同一个未占用用户名，断言一个 200、一个 409，最终仅一个账户占用该名字；无应用级互斥、无 Repository mock 替代数据库索引。
- [x] 同一账户执行两次受控并发修改：第一事务完成 UPDATE 后用测试钩子暂停，第二线程启动 UPDATE，第一提交后第二继续；断言第二成功写入最终生效、无 409 版本冲突、安全列不变。使用 CountDownLatch/Future 超时和 finally 释放钩子/线程，不依赖 Thread.sleep 碰运气或测试执行顺序。
- [x] 对真实 Repository 使用仅故障分支的 spy：UPDATE 真正执行后，让投影抛存储异常；请求 503，随后绕开故障查询验证 username 与 updated_at 回滚。另覆盖 UPDATE 后投影空结果对应 401。正常成功/并发流程必须使用真实查询。
- [x] 经真实 Redis 限流与现有登录链验证新用户名密码登录成功、旧的不同名称不再匹配本人、仅大小写改名仍能匹配、邮箱密码登录成功；使用真实 PasswordHasher，不修改登录接口或新增邮件发送流程。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.CurrentUserUpdateIntegrationTest"`，记录真实 MySQL/Redis 运行情况，跳过不计通过；修复本接口缺陷后重跑。
- [x] 运行 `$env:MINIO_INTEGRATION_TESTS = "true"` 后 `.\gradlew.bat cleanTest check`，强制全量真实容器回归，避免已有 MinIO 用例环境门禁跳过；不因此扩大到歌曲模块修改。
- [x] 从 build/test-results/test/TEST-*.xml 汇总真实通过/失败/错误/跳过数量，逐项列出跳过原因；确认新增测试及既有 U01/注册/登录契约未退化。
- [x] 运行 git diff --check、git status --short，核对全部变更含 untracked、无迁移/全局 JSON 改动，无 TODO、敏感日志或无关文件；本次采用单接口原子提交，提交说明为 `feat: 实现本人用户名修改接口`，不虚构独立任务提交。
- [x] 按 AGENTS.md 第 14 节准备 docs/reviews/2026-11-01-update-current-user-review.md：Method/Path、权限、声称行为、全部文件含 untracked、迁移无、API 路径、实际 check 命令和数量、基线/实现提交、curl、并发与提交不确定性、专用限流不新增决策。交由 Claude 独立只读审核，未取得 accepted 前不推进下一接口。

## 计划自查与执行门禁

已对照设计检查身份来源、严格字段绑定、用户名规则、大小写/唯一性/无变化语义、事务和提交不确定性、改名登录、错误码、日志、OpenAPI、真实 MySQL/Redis/JWT 与并发回滚验证。五项审查重点均对应具体任务，方法名、参数和响应类型一致，无额外接口或数据库结构改动。

用户于 2026-11-01 授权实施。代码、API 文档与 107 项新增测试已完成：严格绑定 19、Service 16、MVC 50、真实 MySQL/Redis/JWT 集成 22。实际启用 MINIO_INTEGRATION_TESTS=true 执行 .\gradlew.bat cleanTest check，78 个测试类、658 通过 / 0 失败 / 0 错误 / 0 跳过，耗时 7 分 36 秒。git diff --check 通过；不提交用户既有 .gitignore 修改，无迁移、依赖或全局 JSON 配置变更。审核材料见 docs/reviews/2026-11-01-update-current-user-review.md；当前等待 Claude 独立审核，未宣称 accepted，不推进下一接口。
