# 修改个人资料接口首轮代码审核请求

本次接口：
- `PATCH /api/v1/users/me`（U02），仅此一个接口，代码审核第 1 轮。

需求与权限：
- 有效 Bearer JWT；USER、ADMIN 登录身份均可操作本人。
- 用户 ID 只来自 AuthenticatedUserPrincipal；JSON 不允许 userId、id、角色等字段，自定义身份头和查询参数不改变操作对象。
- 本次仅支持 username；邮箱、密码、角色、tokenVersion 及其他审计字段不由此接口直接修改。

声称实现的行为：
- 请求局部 Jackson 3 反序列化器仅接受一个字符串 username；拒绝缺体、非对象、null、重复/转义重复键、未知字段、类型强制和多个根值。不改全局 ObjectMapper。
- Bean Validation 要求 3—20 位 ASCII 英文字母、数字或下划线；保留大小写，不 trim，不自动转小写。OpenAPI 请求 Schema 明确 additionalProperties=false。
- native UPDATE 只按主体 ID 更新 username 和数据库 UTC_TIMESTAMP(6) 的 updated_at，二进制比较保证大小写变化允许更新、完全相同不更新时间。
- 同一写事务中执行 UPDATE、白名单投影与响应组装；投影失败时回滚未提交修改。UPDATE 返回 0 后仍读取投影，区分无变化与账户消失，不直接将 0 行视为 401。
- 仅定向 UPDATE 故障链中确认 MySQL 1062/23000 时复用 409 USERNAME_ALREADY_EXISTS，detail 通用为“用户名已占用”。其他完整性、投影、事务和提交确认故障均不误报重名。
- 写存储故障使用新 503 USER_PROFILE_UPDATE_UNAVAILABLE，保留 traceId、exceptionType 和 throwable；账户消失复用 401 TOKEN_REVOKED；业务 type 为下划线 urn。
- 200 返回 CurrentUserResponse 六字段白名单和 no-store；空邮箱、空创建时间保留 null，创建时间 UTC 微秒保真，不返回 Entity、密码摘要、tokenVersion 等内部字段。
- 唯一索引兜底不同账户竞争；同一用户并发改名按数据库执行顺序生效，不新增版本冲突机制或幂等键。
- 改名不撤销既有 Token；新名称可登录，旧的不同名称不再指向本人；仅大小写变更仍可按原拼写登录，邮箱登录不受影响。
- 不新增专用限流或冷却期，也不假定认证/部署层已经提供改名频率控制；本接口无新增 429 分支。

变更文件（含送审前 untracked）：

实现提交 b1a826c 共 13 文件；新增文件在暂存前均为 untracked，已全部列出。

| 类型 | 文件 |
| --- | --- |
| 修改 | src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java |
| 修改 | src/main/java/org/example/music/user/repository/UserRepository.java |
| 新增 | src/main/java/org/example/music/user/controller/CurrentUserUpdateController.java |
| 新增 | src/main/java/org/example/music/user/dto/UpdateCurrentUserRequest.java |
| 新增 | src/main/java/org/example/music/user/dto/UpdateCurrentUserRequestDeserializer.java |
| 新增 | src/main/java/org/example/music/user/exception/UserProfileUpdateUnavailableException.java |
| 新增 | src/main/java/org/example/music/user/service/CurrentUserUpdateService.java |
| 新增 | src/test/java/org/example/music/user/CurrentUserUpdateIntegrationTest.java |
| 新增 | src/test/java/org/example/music/user/controller/CurrentUserUpdateControllerTest.java |
| 新增 | src/test/java/org/example/music/user/dto/UpdateCurrentUserRequestDeserializerTest.java |
| 新增 | src/test/java/org/example/music/user/service/CurrentUserUpdateServiceTest.java |
| 新增 | docs/api/user/update-current-user.md |
| 新增 | docs/superpowers/plans/2026-11-01-update-current-user-plan.md |
| 新增审核材料 | docs/reviews/2026-11-01-update-current-user-review.md（本文件，单独文档提交） |

任务开始前已有 `.gitignore` 用户未提交修改；未改动、未暂存、未提交，不属于本次审核范围。未提前登记 U02 accepted 或开始其他接口。

数据库迁移：
- 无。V1—V8 不变，无 Entity/数据库结构、依赖、SecurityConfig 或全局 JSON 配置修改。

API 文档：
- [修改个人资料 API](../api/user/update-current-user.md)。
- [已确认设计](../superpowers/specs/2026-11-01-update-current-user-design.md)：80b441f，限流决策补充 e89b9eb。
- [实施计划与结果](../superpowers/plans/2026-11-01-update-current-user-plan.md)。

测试：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check
```

- 最终结果：BUILD SUCCESSFUL（7m 36s）；78 个测试类，658 通过 / 0 失败 / 0 错误 / 0 跳过。cleanTest 强制真实执行，未将 UP-TO-DATE 当成测试重跑。
- 数量根据 build/test-results/test/TEST-*.xml 汇总；HTML 报告位于 build/reports/tests/test/index.html。
- 跳过的测试：无。
- 本接口新增 107 项全部通过：UpdateCurrentUserRequestDeserializerTest 19、CurrentUserUpdateServiceTest 16、CurrentUserUpdateControllerTest 50、CurrentUserUpdateIntegrationTest 22。
- 实际定向命令：`cmd /c gradlew.bat test --tests "org.example.music.user.CurrentUserUpdateIntegrationTest" --tests "org.example.music.user.controller.CurrentUserUpdateControllerTest" --tests "org.example.music.user.service.CurrentUserUpdateServiceTest" --tests "org.example.music.user.dto.UpdateCurrentUserRequestDeserializerTest"`，BUILD SUCCESSFUL（53s），107/0/0。其后增加的 Schema additionalProperties=false 已由最终完整 check 编译与回归验证。
- MVC 用真实消息转换器测试原始 JSON、Bean Validation 和统一错误，覆盖缺体、空对象、null、未知字段、重复键、数字/布尔值/对象/数组、空白、长度 2/3/20/21、首尾空格与末尾换行/制表符，非法请求不调用 Service。注入主体仅证明 HTTP 契约，不代替真实 JWT 验证。
- 集成测试使用真实 Testcontainers MySQL 8.4、Redis 8.10.2，真实 JwtTokenService 与实际过滤链；正常资料更新、唯一性竞争和登录均不以 mock 替代真实外部服务。
- 并发测试的 Repository spy 仅设置栅栏/闩锁控制执行顺序，随后通过 spy 默认 Answer 转发真实 Spring Data 代理，执行生产 native UPDATE；未用假返回值或另写 SQL 替代待测更新。线程均使用超时及 finally 清理，无 Thread.sleep 随机等待。
- 两个账户同时争抢同一新名称，验证一项 200、一项 409、数据库唯一记录；同一账户并发验证最终后写生效、安全字段不变。已有占用者大小写变体冲突及本人仅大小写修改均走真实 MySQL。
- 在真实事务中仅对故障分支注入投影异常/空结果，验证 UPDATE 后 503/401 与 username、updated_at 整行回滚。正常查询不由 mock 替代。
- Service 单测覆盖 1062/23000 的严格分类、nextException 链、其他完整性/死锁/超时、投影伪造重复键、事务启动/提交确认失败及组装异常回滚。
- 对比账户整行证明仅 username 与实际变化时的 updated_at 可改变；无变化重复请求整行不变。UTC 时间通过 SQL 文本与数据库时钟边界验证，避免 JVM 时区写读抵消造成假通过。
- 新名、旧名、大小写和邮箱登录使用现有 HTTP 登录接口、真实 PasswordHasher 与 Redis 限流路径；验证原 JWT 仍能读取新的本人资料，已有撤销机制保持有效。
- git diff --check 已通过。

基线：
- 实现基线：main @ e89b9eb；U01 已完成基线为 8b69edb。
- 实现提交：b1a826c，使用 `git diff e89b9eb..b1a826c` 核对 13 文件实现范围。
- 本审核请求是实现之后单独的文档提交，不含业务变更；未推送。

curl 示例（调用环境预先设置自己的 ACCESS_TOKEN）：

```sh
curl --request PATCH 'http://localhost:8080/api/v1/users/me' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data '{"username":"alice_new"}'
```

未决问题 / 默认项：
- 无待确认的业务规则；用户名格式、大小写、同值无变化、额外查询参数忽略、严格 JSON 字段边界与无专用限流均按已确认设计执行。
- 不承诺跨阶段认证/资料原子快照；提交确认连接中断返回 503 时可能已经生效，客户端可 GET 确认或重提目标值。此限制在 API 文档中明确，不将 503 伪装为修改一定未生效。
- 当前状态：代码、文档及本地验证完成，待 Claude 独立只读审核；尚无 accepted 结论，不推进下一接口。
- 请按 CLAUDE.md 独立核对范围和验证。needs_revision 由 Codex 修阻断项；最多三轮，escalated 时等待人类决策。

## 第 1 轮独立审核结论

2026-11-01，用户转交 Claude 独立审核结论：accepted，无阻断项。审核基线 main @ 364a912；独立启用 MINIO_INTEGRATION_TESTS=true 执行 `.\gradlew.bat cleanTest check`，BUILD SUCCESSFUL（7m 19s），78 个测试类、658 通过 / 0 失败 / 0 跳过。新增四类 107 项全部通过，与实现方结果一致。

唯一非阻断建议为将 GET/PATCH 两处 createdAt 文本到 UTC Instant 的转换提取为共享映射，避免后续约定漂移。该建议登记到任务书后续优化，本轮不改已接受的业务代码，无需重跑代码测试。

U02 实现提交 b1a826c，首轮审核材料 364a912，完成状态登记于 docs/tasks/backend-roadmap.md。用户已授权推送所有待推送内容，包括此前排除的 .gitignore 增加 TEACHER.md 忽略规则；该忽略规则单独提交，不属于接口实现变更。上文“待审核/未推送”为首次送审状态，以本节结论为准。
