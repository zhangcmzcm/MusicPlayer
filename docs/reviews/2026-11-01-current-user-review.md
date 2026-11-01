# 获取当前用户信息接口首轮代码审核请求

本次接口：
- `GET /api/v1/users/me`（U01），仅此一个接口；代码审核第 1 轮。

需求与权限：
- 有效 Bearer JWT；USER 与 ADMIN 登录身份均可读取本人。
- 本人 ID 仅来自 `AuthenticatedUserPrincipal.userId`；不接受前端用户 ID、角色或请求头替代认证身份。
- 使用既有签名、issuer、audience、有效期、token version 与登录角色校验。

声称实现的行为：
- 响应严格为 id、username、email、userRole、loginRole、createdAt 六字段。id 保持 Integer；email、createdAt 为空时显式返回 null。
- userRole 来自数据库，loginRole 来自已验证主体；管理员以 USER 登录时分别输出 ADMIN、USER，不提升当前权限。
- 专用投影不查询密码摘要或 tokenVersion；Controller 不接触 Entity。未知查询参数及其重复值忽略，不改变查询对象。
- createdAt 按数据库 UTC DATETIME 值输出 ISO-8601 Instant，保留微秒。使用查询中的 cast(createdAt as String) 避免既有 JDBC Calendar/JVM 时区组合对 LocalDateTime 投影产生 8 小时偏移；不改全局 Entity 映射或时间配置。
- Service 使用 TransactionTemplate 的只读事务，查询、事务启动和完成故障统一映射 503 USER_PROFILE_STORE_UNAVAILABLE。
- 认证通过后若资料查询发现账户已不存在，映射 401 TOKEN_REVOKED。认证与资料查询不是原子快照，不承诺并发角色变更能中止已进入业务层的请求。
- 200 设置 Cache-Control: no-store；错误沿用现有安全响应缓存头。业务 ProblemDetail 类型保留下划线，如 urn:problem:music:token_revoked 和 urn:problem:music:user_profile_store_unavailable。
- 不更新最后登录时间或其他账户字段，不新增缓存、限流、依赖、迁移或公开安全路径。JWT 签名/过期错误继续沿用既有资源服务器响应，文档没有宣称已统一其 ProblemDetail。

变更文件（含送审前 untracked）：

实现提交 `1ce8b67` 共 13 个文件；新增文件在暂存前均为 untracked，均列在下表。

| 类型 | 文件 |
| --- | --- |
| 修改 | src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java |
| 修改 | src/main/java/org/example/music/user/repository/UserRepository.java |
| 新增 | src/main/java/org/example/music/user/controller/CurrentUserController.java |
| 新增 | src/main/java/org/example/music/user/domain/UserProfileSnapshot.java |
| 新增 | src/main/java/org/example/music/user/dto/CurrentUserResponse.java |
| 新增 | src/main/java/org/example/music/user/exception/CurrentUserRevokedException.java |
| 新增 | src/main/java/org/example/music/user/exception/UserProfileStoreUnavailableException.java |
| 新增 | src/main/java/org/example/music/user/service/CurrentUserService.java |
| 新增 | src/test/java/org/example/music/user/CurrentUserIntegrationTest.java |
| 新增 | src/test/java/org/example/music/user/controller/CurrentUserControllerTest.java |
| 新增 | src/test/java/org/example/music/user/service/CurrentUserServiceTest.java |
| 新增 | docs/api/user/get-current-user.md |
| 新增 | docs/superpowers/plans/2026-11-01-current-user-plan.md |
| 新增审核材料 | docs/reviews/2026-11-01-current-user-review.md（本文件，单独文档提交） |

开始任务前已有 `.gitignore` 用户修改；未改动、未暂存、未提交，不属于本次审核范围。无其他未列入范围的源码改动。

数据库迁移：
- 无；既有 V1—V8 均未修改，继续由 Flyway 管理。

API 文档：
- [当前用户 API](../api/user/get-current-user.md)
- [已确认设计](../superpowers/specs/2026-11-01-current-user-design.md)，设计修正提交 `53217e5`。
- [实现计划及完成记录](../superpowers/plans/2026-11-01-current-user-plan.md)。

测试：
- 最终命令（Windows PowerShell）：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check
```

- 结果：BUILD SUCCESSFUL，耗时 7 分 4 秒；74 个类，551 通过 / 0 失败 / 0 错误 / 0 跳过。cleanTest 强制实际执行测试，没有把 UP-TO-DATE 当成测试运行。
- 数量由 `build/test-results/test/TEST-*.xml` 汇总；测试报告为 `build/reports/tests/test/index.html`。
- 跳过的测试：无。
- 本接口新增：CurrentUserServiceTest 6、CurrentUserControllerTest 5、CurrentUserIntegrationTest 21，共 32 通过 / 0 失败 / 0 跳过。
- 定向实际执行命令：`cmd /c gradlew.bat test --tests "org.example.music.user.controller.CurrentUserControllerTest" --tests "org.example.music.user.CurrentUserIntegrationTest" --tests "org.example.music.user.service.CurrentUserServiceTest"`，结果 BUILD SUCCESSFUL，32/0/0，40 秒；随后执行上述完整基准命令。
- MVC 契约测试注入主体，只证明 HTTP 边界；真实签名 JWT 的集成测试通过实际 SecurityConfig 与 TokenVersionAuthenticationFilter，验证主体类型、本人归属、两角色、未知及重复参数、伪造请求头/请求体、账户删除、角色变更及版本撤销、非法 subject、非法版本/角色、错误签名与过期。
- 集成测试真实使用 Testcontainers MySQL 8.4；正常资料查询和认证状态查询均走实际数据库。仅故障测试通过 Repository spy 定点注入失败或空结果，验证认证 503、认证后账户消失 401、资料 503；不使用 spy 替代正常查询集成验证。
- 时间测试使用 SQL 直接设置 UTC DATETIME，覆盖整秒、000001 和 123456 微秒；避免仅靠 ORM 写入与读取相互抵消偏移而误判通过。
- 重复 GET 前后比较完整 users 行，验证无写入副作用；事务启动与提交故障在 Service 单测覆盖。
- `git diff --check` 已通过。

基线：
- 设计确认基线：`main @ 53217e5`。
- 实现提交：`1ce8b67`，可用 `git diff 53217e5..1ce8b67` 检查 13 文件实现范围。
- 本审核请求为其后的独立文档提交，不包含业务行为变化。

curl 示例（调用环境预先设置自己的 ACCESS_TOKEN，不在文档中写入真实凭证）：

```powershell
curl.exe --request GET "http://localhost:8080/api/v1/users/me" --header "Authorization: Bearer $env:ACCESS_TOKEN"
```

未决问题 / 默认项：
- 无待确认的业务规则。未知查询参数忽略、完整本人邮箱、可空历史时间、两角色输出和 no-store 均按已确认设计执行。
- 时间处理仅确保本接口按数据库中保存的 UTC 值输出；不修改或修复其他模块及历史数据。
- 当前状态：实现与本地验证完成，提交本材料供 Claude 独立只读审核；尚未取得代码审核结论，不宣称 accepted。
- 请按 CLAUDE.md 独立核对范围并运行验证；若 needs_revision，由 Codex 修复阻断项，最多三轮；escalated 时等待人类决策。

## 第 1 轮独立审核结论

2026-11-01，用户转交 Claude 独立审核结论：accepted，无阻断项。审核基线 main @ dff862a；独立执行 `MINIO_INTEGRATION_TESTS=true .\gradlew.bat cleanTest check`，BUILD SUCCESSFUL（7m 26s），74 个测试类、551 通过 / 0 失败 / 0 跳过，与实现方验证数量一致。

唯一非阻断建议为 GlobalExceptionHandler.handleUserProfileStoreUnavailable 的 warning 末尾补传 exception，保留 cause 堆栈以诊断 503。已按建议补传，未改变 401/503 的 HTTP、code、type 或响应结构。该日志调整属于审核后的补充，不宣称已由 Claude 重新审核。

补充日志后，Codex 实际启用 `MINIO_INTEGRATION_TESTS=true` 执行 `.\gradlew.bat check`：BUILD SUCCESSFUL（7m 27s），XML 汇总 74 个类、551 通过 / 0 失败 / 0 错误 / 0 跳过。test 任务实际执行，未将缓存结果视为重新验证；无需再新增接口测试。

用户已授权提交并推送。U01 实现提交为 1ce8b67，完成状态登记于 docs/tasks/backend-roadmap.md。上文“待审核”为首次送审时状态，以本节结论为准；原有 .gitignore 用户修改继续排除。
