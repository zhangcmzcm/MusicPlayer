# 登录认证版本绑定加固首轮代码审核请求

本次接口 / 任务：
- 现有 `POST /api/v1/auth/login` 的认证版本绑定加固，代码审核第 1 轮；不新增接口，不实现 U03 改密。

需求与权限：
- 登录仍公开，不要求已有 Bearer Token；账号密码、邮箱验证码或一次性管理员 Ticket 证明身份。
- 验证结果绑定同一账户记录的角色和 token version，后续只能复核相等，不得用新版本升级旧验证。
- 普通用户只签发 USER；管理员仍通过同一接口选择 USER/ADMIN，保留原有权限和限流规则。

声称实现的行为：
- LoginPrincipal 与 LoginTicketContext 显式增加 tokenVersion，没有默认版本为 0 的构造器或回填逻辑。
- 密码校验前、验证码消费前从同一用户记录固定验证结果；验证成功后仍使用该记录版本。
- 普通登录及管理员 Ticket 发布前均按主体 ID 查询当前状态，比较账户存在、角色及版本；不匹配统一 401 INVALID_CREDENTIALS，管理员不能绕过复核。
- Ticket 兑换复核不匹配仍为 401 LOGIN_TICKET_INVALID，角色选择不允许仍为原有 403。普通初始路径账户消失不再误报 Ticket 错误，API 文档已明确这个边缘码变化。
- Ticket 保留 32 字节 SecureRandom、SHA-256 后 Base64URL key、命名空间与 300 秒期限；一次 Lua 发布四字段与 TTL，未确认成功不返回凭据。
- 消费 Lua 检查 PTTL 和 Hash 类型，再读取并删除；无 TTL、过期、错误类型、重放、旧格式缺版本、缺/多字段、非法元数据一律拒绝。Java 要求精确四字段集合，不按位置读取、忽略多余字段或覆盖重复字段。
- tokenVersion 只接受 0 或无前导零的正十进制 ASCII 数字，再检查 long 范围；0 合法、缺失不等于 0，拒绝符号、前导零、非数字、小数和溢出。
- JWT 与 Ticket 仅使用已匹配且不可变的快照。最后复核后撤销可能仍返回旧版本结果，但不能产生新版本有效 Token；受保护接口继续实际检查数据库版本。
- 已消费 Ticket/验证码不因后续失败恢复；Redis/状态故障沿用已有 503，不新增错误码，不记录密码、验证码、Ticket 或 Token。
- 不修改公开请求/成功 DTO、JWT 声明格式、共享认证主体、密码算法、迁移、依赖、认证缓存或其他账户接口。

变更文件（含送审前 untracked）：

实现提交 5e8ceba 共 15 文件，新增文件提交前均为 untracked，已全部列出：

| 类型 | 文件 |
| --- | --- |
| 修改 | src/main/java/org/example/music/user/controller/AuthController.java（仅登录注解描述） |
| 修改 | src/main/java/org/example/music/user/domain/LoginPrincipal.java |
| 修改 | src/main/java/org/example/music/user/domain/LoginTicketContext.java |
| 修改 | src/main/java/org/example/music/user/service/PasswordLoginService.java |
| 修改 | src/main/java/org/example/music/user/service/EmailCodeLoginService.java |
| 修改 | src/main/java/org/example/music/user/service/LoginTicketService.java |
| 修改 | src/main/java/org/example/music/user/service/UserLoginService.java |
| 修改 | src/test/java/org/example/music/user/service/EmailCodeLoginServiceTest.java |
| 修改 | src/test/java/org/example/music/user/service/UserLoginServiceTest.java |
| 新增 | src/test/java/org/example/music/user/service/PasswordLoginServiceTest.java |
| 新增 | src/test/java/org/example/music/user/service/LoginTicketServiceTest.java |
| 新增 | src/test/java/org/example/music/user/AuthVersionBindingIntegrationTest.java |
| 修改 | docs/api/auth/login.md |
| 新增 | docs/deployment/auth-version-binding.md |
| 新增 | docs/superpowers/plans/2026-11-01-auth-version-binding-plan.md |
| 新增审核材料 | docs/reviews/2026-11-01-auth-version-binding-review.md（本文件，独立文档提交） |

任务开始时工作区干净；既有 UserLoginIntegrationTest 与 AuthControllerTest 未修改，作为回归实际执行。本次没有其他未列明的源码修改。

数据库迁移：
- 无。V1—V8 不变；沿用 users.token_version，未新增表、字段或配置项。

API / 部署文档：
- [登录 API](../api/auth/login.md)。
- [部署与回滚边界](../deployment/auth-version-binding.md)。
- [已确认设计](../superpowers/specs/2026-11-01-auth-version-binding-design.md)，17b5a9a。
- [计划与实施记录](../superpowers/plans/2026-11-01-auth-version-binding-plan.md)。

测试：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check
```

- 最终结果：BUILD SUCCESSFUL（8m 11s）；81 类，767 通过 / 0 失败 / 0 错误 / 0 跳过，cleanTest 强制实际运行，未将 UP-TO-DATE 视为重跑。
- 数量根据 build/test-results/test/TEST-*.xml 汇总；HTML 报告位于 build/reports/tests/test/index.html。跳过的测试：无。
- 本任务五类共 117 项：PasswordLoginServiceTest 6、EmailCodeLoginServiceTest 5、LoginTicketServiceTest 33、UserLoginServiceTest 27、AuthVersionBindingIntegrationTest 46；其中新增 109，既有 8 项回归。
- 独立定向实际命令：`cmd /c gradlew.bat test --tests "org.example.music.user.AuthVersionBindingIntegrationTest" --tests "org.example.music.user.UserLoginIntegrationTest"`，BUILD SUCCESSFUL（1m 8s），51/0/0（新增真实集成 46、既有登录集成 5）。
- 此前还实际运行四个 Service 测试及 AuthControllerTest 的定向命令，结果通过；最终完整 check 对所有当前代码重新执行。
- 真实集成使用 MySQL 8.4、Redis 8.10.2、真实 BCrypt 和 JWT 编码/解码，正常登录、Lua 发布/消费、版本复核及 GET 受保护认证均未以 mock 替代。SMTP 仅捕获测试验证码，不模拟验证码 Redis。
- 窗口一：真实 BCrypt 校验完成后或验证码消费后，用闩锁暂停并 SQL 递增版本，再进入复核，验证初始 401，无 JWT/Ticket 升级；覆盖 USER/ADMIN、用户名/邮箱密码。
- 窗口二：最后复核后、实际 JWT 编码或 Ticket 发布前暂停并撤销；真实签出的 JWT 仍为旧版本，经实际安全链 GET 得 TOKEN_REVOKED；真实 Ticket 保留旧版本，兑换失败。未用“登录必定当场 401”掩盖竞态。
- 验证后角色变化，包括仅改角色而不变版本的故障状态，均拒绝；账户在初始验证后消失得到 INVALID_CREDENTIALS。
- 真实 Redis 构造旧三字段 Ticket、无 TTL、过期、错误类型、缺/多字段及非法版本；断言拒绝且记录删除。版本 0、非零与 Long.MAX_VALUE 均通过合法往返。
- 并发兑换同一 Ticket 恰一成功；消费后状态服务失败不恢复 Ticket/验证码。Ticket Redis 故障用定点注入验证无 fallback 签发，正常流程均走真实服务。
- 所有时序钩子均转发真实实现，使用 CountDownLatch/CyclicBarrier/Future 超时及 finally 清理，无 Thread.sleep；不通过 mock JWT 或假数据库结果替代安全证明。
- git diff --check 通过。

基线：
- 实现基线：main @ 17b5a9a（设计确认），U03 原密码兼容性补充为 b2c71e4。
- 实现提交：5e8ceba；使用 `git diff 17b5a9a..5e8ceba` 核对 15 文件实现范围。
- 本审核请求是之后独立的文档提交；未推送，未宣称部署完成。

curl 示例：

调用者预先准备安全的 LOGIN_BODY_FILE，内容按登录 API 文档选择账号密码、验证码或角色选择字段，文件不入 Git，不在示例写入真实凭据。

```sh
curl --request POST 'http://localhost:8080/api/v1/auth/login' \
  --header 'Content-Type: application/json' \
  --data-binary "@${LOGIN_BODY_FILE}"
```

未决问题 / 默认项 / 部署限制：
- 无待确认的业务规则；严格四字段、规范版本、PTTL、错误归类均按设计及审阅提醒执行。
- 必须停止/切走全部旧登录实例并排空遗留旧请求，才能宣告环境中加固生效；混合部署中的旧代码仍可能忽略版本。没有清空 Redis 或兼容旧无版本 Ticket。
- 回滚旧登录代码会重开风险，不得继续宣称满足 U03 前置条件。代码 accepted 与实际全实例部署完成是不同事项，文档已明确。
- 当前仅完成本地实现与验证，待 Claude 独立只读审核。U03 改密和注册 72 字节问题均未实施，也不提前解除前置门禁。
- needs_revision 由 Codex 修复阻断项，最多三轮；escalated 等待人类决策。accepted 后记录实际加固提交，再回到改密设计核对代码基线。
