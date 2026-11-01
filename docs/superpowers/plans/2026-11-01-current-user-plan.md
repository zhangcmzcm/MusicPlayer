# 获取当前用户信息接口实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.
>
> 本项目执行方式以 AGENTS.md 和用户约定为准：Codex 是唯一代码实现方，Claude 独立只读审核。无需派发实现子代理；上面的通用技能入口若不可用，不声称已调用。

**Goal:** 实现且仅实现 `GET /api/v1/users/me`，按认证主体返回本人资料及两种角色，不暴露内部字段。

**Architecture:** 保留现有认证过滤链和 AuthenticatedUserPrincipal。新增资料查询投影及只读 Service，Controller 接收主体并返回独立 DTO；统一异常处理只增加本接口必要映射。

**Tech Stack:** Java 21、现有 Gradle Wrapper、Spring MVC/Security/Data JPA、MySQL 8.4、JUnit 5、MockMvc、Testcontainers、springdoc；不新增依赖。

**Spec:** [已确认设计](../specs/2026-11-01-current-user-design.md)，修正提交 `53217e5`。

## 全局约束

- 单接口 `GET /api/v1/users/me`，不实现其他账户或歌曲功能。
- ID 保持 Integer；身份仅取自 AuthenticatedUserPrincipal，未知查询参数忽略。
- 字段仅 id、username、email、userRole、loginRole、createdAt；email/createdAt 显式允许 null，时间 UTC ISO-8601。
- 不新增或修改迁移，不改变认证主体、登录响应、安全放行规则、缓存或限流。
- 错误 type 由现有 code.toLowerCase() 生成，使用下划线；不修订共享 JWT 签名/过期响应契约。
- 不提交既有 `.gitignore` 修改；API、计划、提交说明使用中文。
- 最终运行 `.\gradlew.bat check`，必要集成测试跳过不得视为通过。

## 审查重点

1. WebMvcTest 注入主体会绕过真实转换链：任务 3 使用真实签名 JWT 验证实际主体类型及角色。
2. 空邮箱和历史空创建时间可能被序列化器省略：任务 2 断言键存在且值为 null。
3. 伪造重复 userId、loginRole 参数可能影响身份：任务 2、3 断言始终返回主体本人及已验证角色。
4. 事务启动或提交异常可能越过 Service 内部 catch：任务 1 验证整个只读事务的数据访问故障均转为资料存储错误。
5. 错误 urn 或缓存头可能与成功契约混淆：任务 2 分别验证两类业务异常的精确 type、状态及 no-store。

## 文件边界

路径均相对仓库根 `D:/code/Java-code/projects/music`。

| 文件 | 职责 |
| --- | --- |
| src/main/java/org/example/music/user/domain/UserProfileSnapshot.java | 仅持久化白名单字段的 record 投影 |
| src/main/java/org/example/music/user/dto/CurrentUserResponse.java | 六字段响应 record 与 Schema |
| src/main/java/org/example/music/user/repository/UserRepository.java | 新增投影查询 |
| src/main/java/org/example/music/user/service/CurrentUserService.java | 按主体 ID 只读查询、UTC 转换、异常转换 |
| src/main/java/org/example/music/user/exception/CurrentUserRevokedException.java | 本人账户消失 |
| src/main/java/org/example/music/user/exception/UserProfileStoreUnavailableException.java | 资料查询事务故障 |
| src/main/java/org/example/music/user/controller/CurrentUserController.java | GET /me、认证主体、HTTP 与 OpenAPI |
| src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java | 两个异常映射 |
| src/test/java/org/example/music/user/service/CurrentUserServiceTest.java | 查询与故障语义 |
| src/test/java/org/example/music/user/controller/CurrentUserControllerTest.java | JSON 和错误 HTTP 契约 |
| src/test/java/org/example/music/user/CurrentUserIntegrationTest.java | 真实 MySQL、JWT、安全链和无写入验证 |
| docs/api/user/get-current-user.md | 中文接口文档 |

## 任务 1：资料读取与事务边界

**文件：** 投影、DTO、Repository、Service、两个异常及 CurrentUserServiceTest，见文件边界表。

**接口：**
- 消费：`AuthenticatedUserPrincipal(Integer userId, UserRole loginRole)`。
- 产出：`UserProfileSnapshot(Integer id, String username, String email, UserRole userRole, String createdAt)`；createdAt 是数据库 UTC DATETIME 的文本值，Service 转为 Instant。真实 MySQL 测试发现既有 JDBC Calendar/JVM 时区组合会对 LocalDateTime 投影产生 8 小时偏移，因此查询使用 cast(createdAt as String)，不调整全局 Entity 或时间配置。
- 产出：`UserRepository.findProfileSnapshot(Integer userId): Optional<UserProfileSnapshot>`。
- 产出：`CurrentUserResponse(Integer id, String username, String email, UserRole userRole, UserRole loginRole, Instant createdAt)`。
- 产出：`CurrentUserService.getCurrentUser(AuthenticatedUserPrincipal actor): CurrentUserResponse`。

- [x] 编写失败测试：主体 ID=42 时只调用 findProfileSnapshot(42)；ADMIN 账户以 USER 登录得到 userRole=ADMIN、loginRole=USER；2026-10-21T12:34:56 转为 2026-10-21T12:34:56Z；空值保持 null；空 Optional 抛 CurrentUserRevokedException；查询故障抛 UserProfileStoreUnavailableException。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.service.CurrentUserServiceTest"`，确认因缺少实现而失败。
- [x] 创建 record、异常和 Repository JPQL 构造投影查询，仅选择五个持久化字段。
- [x] 实现 getCurrentUser：使用注入的 PlatformTransactionManager 构造只读 TransactionTemplate，try/catch 包围 execute 的完整调用，将 DataAccessException 和 TransactionException 转成资料存储异常，保持账户消失异常类型。只读事务不覆盖认证查询。
- [x] 增加事务启动故障及完成阶段故障测试，验证同样转为资料存储异常；重复执行不会调用任何保存或最后登录时间更新方法。
- [x] 重跑上述测试，全部通过后检查只加载白名单、无无关数据修改。
- [x] 本任务验证通过，归入最终单接口实现提交。

## 任务 2：HTTP、错误映射和 API 文档

**文件：** CurrentUserController、GlobalExceptionHandler、CurrentUserControllerTest、get-current-user.md；完善 DTO Schema。

**接口：** 消费任务 1 的 getCurrentUser；产出 `GET /api/v1/users/me`，返回 ResponseEntity<CurrentUserResponse>，成功缓存头 no-store。

- [x] 编写失败契约测试：200 精确六键；email/createdAt 的键存在且为 null；主体 ID 被原样交给 Service；未知及重复 userId/loginRole 参数被忽略；未认证 401；普通 USER 主体访问成功。
- [x] 编写两类业务错误测试：CurrentUserRevokedException → 401/TOKEN_REVOKED/urn:problem:music:token_revoked；UserProfileStoreUnavailableException → 503/USER_PROFILE_STORE_UNAVAILABLE/urn:problem:music:user_profile_store_unavailable；application/problem+json，错误保留 no-store。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.controller.CurrentUserControllerTest"` 确认失败。
- [x] 实现 Controller 的 `getCurrentUser(@AuthenticationPrincipal AuthenticatedUserPrincipal actor)`；不接受 ID 或角色参数，无管理员专属限制。按现有响应处理设置 no-store。
- [x] GlobalExceptionHandler 只新增两个处理方法，使用现有 problem 构造方式，不改其他异常映射。错误 no-store 若已有安全链提供则复用，避免重复新建通用装饰器。
- [x] 补充 Tag、Operation、ApiResponse、Schema、认证主体隐藏参数注解；响应状态声明 200/401/503/500，与 JWT 现状说明一致。
- [x] 写中文 API 文档：请求、Bearer 认证、无业务参数、未知参数忽略、两角色来源、六字段与显式 null、响应示例、错误表、并发非原子边界和环境变量 curl。JWT 签名/过期响应不宣称已统一 ProblemDetail。
- [x] 重跑任务 1 和 2 定向测试，检查无密码摘要、tokenVersion、Token 等非白名单字段。
- [x] 本任务验证通过，归入最终单接口实现提交。

## 任务 3：真实链路验证及单接口送审

**文件：** CurrentUserIntegrationTest；发现属于本接口的缺陷时修正上述文件。

**接口：** 复用 UserRoleAuthorizationIntegrationTest 的 SpringBootTest、AutoConfigureMockMvc、Testcontainers MySQL 8.4 配置和 JwtTokenService 发令牌方式。以 Authorization 请求头触发实际 SecurityConfig 与 TokenVersionAuthenticationFilter，不用 authentication(...) 替代集成链路。

- [x] 编写真实 MySQL 测试：普通用户、ADMIN 登录管理员、USER 登录管理员、两个不同用户，分别断言资料归属和两角色语义。
- [x] 编写空邮箱/created_at 记录测试，通过 JDBC 保存合法历史空值并读取；断言 null 键存在、JSON 精确六键。非空时间断言 UTC Z 格式。
- [x] 编写伪造及重复 userId/loginRole 查询参数测试，用 A 的有效 Token 请求 B 的 ID，仍返回 A，绝不返回 B。
- [x] 编写无认证、非法 subject、过期、错误签名、不存在用户、版本撤销和角色降权回归；真实 JWT 经过实际过滤链，按对应既有契约断言状态与 code。角色更新通过已有服务或既有接口执行，不新增业务入口。
- [x] 编写重复 GET 前后数据库快照对比，断言 username/email/userRole/tokenVersion/createdAt/updatedAt/lastLoginAt 均未改变；响应头为 no-store。
- [x] 运行 `.\gradlew.bat test --tests "org.example.music.user.CurrentUserIntegrationTest"`；Docker 不可用或用例跳过时记录阻塞，不能判作通过。按失败原因修复本接口并重跑。
- [x] 运行 `.\gradlew.bat check`；为避免已有 MinIO 用例环境门禁跳过，可先设置 `$env:MINIO_INTEGRATION_TESTS = "true"`。若任务未实际执行而显示 UP-TO-DATE，使用 `.\gradlew.bat cleanTest check` 强制验证；如实记录实际命令。
- [x] 从 build/test-results/test/TEST-*.xml 汇总通过/失败/跳过；逐项列出跳过测试及原因，必要集成验证未执行不得宣称完成。
- [x] 运行 `git diff --check`、`git status --short`，检查修改范围、文档与实现一致、不涉及已执行迁移及既有 .gitignore 修改。
- [x] 仅提交本接口文件，统一提交说明 `feat: 实现当前用户信息查询接口`；记录实现提交和基线，不提交凭据或临时输出。
- [x] 按 AGENTS.md 第 14 节准备审核材料：唯一 Method/Path、需求与权限、行为、全部变更文件含 untracked、迁移无、API 文档路径、实际 check 命令及数量、基线、curl 和未决问题。交由 Claude 独立只读审核；needs_revision 修阻断项，escalated 停下等待人类决策。

## 计划自查与执行门禁

已逐项覆盖设计的请求与角色语义、字段白名单、空值、事务与并发边界、两类异常、OpenAPI、API 文档和真实安全链验证。三个任务使用一致的类型及方法名，五项审查重点均有对应验证步骤，不引入其他接口或重构。

用户于 2026-11-01 授权开始实现。实现和文档已完成；最终启用 MINIO_INTEGRATION_TESTS=true 执行 .\gradlew.bat cleanTest check，551 通过、0 失败、0 跳过，共 74 类，耗时 7 分 4 秒。本接口 32 项测试（Service 6、Controller 5、真实 JWT/MySQL 集成 21）全部通过。提交采用单接口原子提交，原有 .gitignore 修改排除。审核材料见 docs/reviews/2026-11-01-current-user-review.md，等待 Claude 独立审核结论，未宣称 accepted。
