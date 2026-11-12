# F02 取消收藏歌曲实现计划

> 执行者遵循AGENTS.md，由Codex在当前会话逐任务实现，Claude Code CLI负责独立只读审核。复选框按实际执行结果更新。

**目标** 实现 `DELETE /api/v1/users/me/favorites/{trackId}`，允许登录用户取消本人任意状态歌曲的收藏，关系或歌曲缺失时幂等返回204。

**架构** 在现有FavoriteTrackController增加DELETE入口，复用FavoriteRepository的歌曲锁定读及现有收藏异常映射。新增外层UnfavoriteTrackService与事务Service，先锁定歌曲，再按用户与歌曲联合主键删除，成功响应发生在事务提交之后。

**技术栈** Java 21、Spring Boot 4.1.1、Spring MVC/Validation/Security、JPA、MySQL 8.4、JUnit 5、MockMvc、Testcontainers MySQL/Redis/MinIO，沿用当前build.gradle依赖。

**设计依据** [F02书面设计](../specs/2026-11-12-unfavorite-track-design.md)，用户于2026-11-12批准。计划基线为 `main @ 825f04e`，工作目录为 `D:\code\Java-code\projects\music`。

## 全局约束

- 当前接口为 `DELETE /api/v1/users/me/favorites/{trackId}`，用户标识使用Integer，歌曲标识使用Long。
- 认证用户仅操作本人收藏，USER与ADMIN均允许DRAFT、PUBLISHED、ARCHIVED；身份取自AuthenticatedUserPrincipal。
- 成功统一204、空正文、Cache-Control no-store，重复取消、未收藏、歌曲不存在或已删除均适用。
- 路径ID为1至9223372036854775807的整数，非法值使用400 VALIDATION_FAILED。
- 请求体和未知查询参数沿用F01忽略规则，伪造身份输入无业务影响。
- 复用歌曲排他行锁，收藏与取消都提交成功时，后取得歌曲锁的操作决定最终状态。
- 取消物理删除本人收藏，重新收藏取新收藏时刻的UTC微秒时间及当前用户审计值。
- 数据结构基于V9，数据库迁移为无，现有V1至V9保持当前基线内容。
- 数据访问与事务故障复用503 FAVORITE_STORE_UNAVAILABLE、现有ProblemDetail格式及固定文案。
- 首版暂不增加取消收藏专用限流，依赖沿用现有构建声明。
- 中文API文档、实施计划、审核记录和git提交说明与项目现有约定一致。
- 完整验证设置MINIO_INTEGRATION_TESTS=true，必要MySQL、Redis和MinIO测试需实际执行。

## 审查重点

1. 管理员以USER登录且持有草稿或归档收藏时，取消仍为204且归属本人，由任务2、任务3验证。
2. 歌曲缺失或DELETE影响0行仍需完成事务，事务提交故障应返回503，由任务2的真实代理与任务3的集成测试验证。
3. 并发请求只改变当前用户与歌曲的关系，其他用户的完整审计字段保持原值，由任务1、任务3验证。
4. 已删除记录在提交确认丢失后重试保持缺失，再次收藏创建新时间记录，由任务3验证。
5. 现有Controller构造器增加依赖后，F01的WebMvcTest仍可启动，PUT的状态权限、204和OpenAPI契约保持正确，由任务3验证。

## 文件与接口结构

路径均相对项目根目录。

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 修改 | src/main/java/org/example/music/favorite/repository/FavoriteRepository.java | 新增本人收藏条件DELETE |
| 新建 | src/main/java/org/example/music/favorite/service/UnfavoriteTrackTransactionService.java | 锁定读、缺失成功及事务内删除 |
| 新建 | src/main/java/org/example/music/favorite/service/UnfavoriteTrackService.java | 方法级认证及事务故障转换 |
| 修改 | src/main/java/org/example/music/favorite/controller/FavoriteTrackController.java | 新增DELETE入口和OpenAPI |
| 修改 | src/test/java/org/example/music/favorite/FavoriteRepositoryIntegrationTest.java | 条件删除、幂等及隔离验证 |
| 新建 | src/test/java/org/example/music/favorite/service/UnfavoriteTrackTransactionServiceTest.java | 状态矩阵、缺失与调用顺序 |
| 新建 | src/test/java/org/example/music/favorite/service/UnfavoriteTrackServiceTest.java | 外层调用及异常转换 |
| 新建 | src/test/java/org/example/music/favorite/service/UnfavoriteTrackProxyTest.java | 实际Spring安全和事务代理 |
| 新建 | src/test/java/org/example/music/favorite/controller/UnfavoriteTrackControllerTest.java | DELETE的HTTP契约 |
| 修改 | src/test/java/org/example/music/favorite/controller/FavoriteTrackControllerTest.java | 补充新依赖，保留PUT断言 |
| 新建 | src/test/java/org/example/music/favorite/UnfavoriteTrackIntegrationTest.java | 真实认证、幂等及事务故障 |
| 新建 | src/test/java/org/example/music/favorite/UnfavoriteTrackConcurrencyIntegrationTest.java | 真实锁竞争和提交顺序 |
| 修改 | src/test/java/org/example/music/track/TrackDeleteIntegrationTest.java | 完整歌曲删除后取消收藏仍为204 |
| 新建 | docs/api/favorite/unfavorite-track.md | 中文API文档及curl |
| 新建 | docs/reviews/2026-11-12-unfavorite-track-review-request.md | 审核材料与验证证据 |
| 新建 | docs/reviews/2026-11-12-unfavorite-track-review.md | 实际审核原文、来源及反馈处置 |
| 更新 | 当前F02设计、计划、docs/tasks/backend-roadmap.md、PROJECT_STATE.md | 实际进度、验证、审核与完成登记 |

测试复用已有FavoriteIntegrationSupport的MySQL/Redis容器、数据准备、JWT、JdbcTemplate和事务方法。具体输入为该类现有userId、adminId、publishedId、draftId、archivedId、bearer及transaction方法。

## 任务1 本人收藏条件删除

**文件** 修改FavoriteRepository和FavoriteRepositoryIntegrationTest。

**输入接口** 现有EntityManager、V9联合主键及测试准备。

**输出接口** `int FavoriteRepository.deleteByUserAndTrack(Integer userId, Long trackId)`，返回实际删除行数；现有lockTrackStatus和addIfAbsent继续按原签名调用。

- [x] 先编写 `should_delete_only_current_users_favorite`，种入本人、另一用户同歌及本人另一首歌的三条记录，保存后两条记录的完整快照。通过真实事务删除目标，断言影响1行、其他两条快照相同。

```java
int deleted = transaction().execute(status -> favorites.deleteByUserAndTrack(userId, publishedId));
assertEquals(1, deleted);
```

- [x] 执行 `.\gradlew.bat test --tests "org.example.music.favorite.FavoriteRepositoryIntegrationTest" --console=plain`，记录新增方法缺失导致的失败。
- [x] 实现参数化原生SQL `delete from user_favorites where user_id=:userId and track_id=:trackId`，两个参数逐一绑定，由调用者提供事务，返回executeUpdate结果。
- [x] 增加 `should_return_zero_when_favorite_is_missing_or_deleted_again`，首次删除为1，重复删除及不存在组合为0。
- [x] 增加 `should_restore_deleted_row_and_audit_fields_on_rollback`，事务中删除后制造异常，事务结束后完整行与删除前快照相同。正常删除与回滚均使用真实MySQL。
- [x] 重跑任务1命令，所有用例通过且必要容器测试0跳过后，提交本任务两个文件，中文提交说明为 `feat: 增加本人收藏条件删除存储`。

## 任务2 取消事务、认证及故障转换

**文件** 新增两个Service与三个对应Service/代理测试类。

**输入接口** 任务1新增的deleteByUserAndTrack，以及现有 `Optional<TrackStatus> lockTrackStatus(Long trackId)`、AuthenticatedUserPrincipal与FavoriteStoreUnavailableException。

**输出接口** `void UnfavoriteTrackTransactionService.unfavorite(Long trackId, AuthenticatedUserPrincipal actor)`，`void UnfavoriteTrackService.unfavorite(Long trackId, AuthenticatedUserPrincipal actor)`。

- [x] 先编写事务Service参数化测试 `should_delete_own_favorite_for_every_status_and_login_role`，覆盖三种TrackStatus与两种UserRole组合。Repository mock命名为favorites，actor.userId固定7、trackId固定42L，调用事务Service后断言先锁定再删除。

```java
var order = inOrder(favorites);
order.verify(favorites).lockTrackStatus(42L);
order.verify(favorites).deleteByUserAndTrack(7, 42L);
```

- [x] 执行 `.\gradlew.bat test --tests "org.example.music.favorite.service.UnfavoriteTrack*" --console=plain`，记录新Service缺失的失败。
- [x] 实现事务Service，构造器注入FavoriteRepository，public unfavorite使用@Transactional。歌曲锁定读为空时正常返回；有歌曲时调用本人条件删除，0行和1行均正常返回。
- [x] 增加 `should_succeed_when_track_is_missing`，Optional.empty时断言删除方法无调用。增加删除返回0或1两种场景，均正常完成。测试actor.loginRole为USER时DRAFT、ARCHIVED也调用本人删除。
- [x] 实现外层Service，构造器注入事务Service，入口使用 `@PreAuthorize("isAuthenticated()")`。通过注入的Spring代理调用事务方法，捕获DataAccessException和TransactionException并包装既有FavoriteStoreUnavailableException。
- [x] 外层单测分别模拟读取/删除数据故障、CannotAcquireLockException、CannotCreateTransactionException及TransactionSystemException，断言返回的包装异常保留原cause，成功调用正确转交(trackId,actor)。
- [x] 参照现有FavoriteTrackProxyTest建立独立轻量Spring上下文，启用方法安全与事务代理，以真实Service和mock事务管理器验证认证先于事务、锁定先于删除、提交先于外层返回。
- [x] 代理测试增加 `should_translate_commit_failure_even_when_track_or_favorite_is_missing`，覆盖缺歌曲、删除0行及删除1行三条路径的提交故障，均转换存储异常。事务启动失败时Repository无交互，未认证时事务管理器也无交互。
- [x] 重跑任务2命令，通过后提交本任务Service及测试，中文提交说明为 `feat: 实现取消收藏权限与事务处理`。

## 任务3 DELETE入口、真实认证与并发

**文件** 修改FavoriteTrackController、原PUT的Controller测试和歌曲删除集成测试，新增取消的Controller、集成及并发测试。

**输入接口** 任务2外层Service，既有FavoriteIntegrationSupport、JwtTokenService、JwtEncoder、SecurityConfig和全局异常映射。

**输出接口** `ResponseEntity<Void> FavoriteTrackController.unfavorite(Long trackId, AuthenticatedUserPrincipal actor)`，映射DELETE /api/v1/users/me/favorites/{trackId}。

- [x] 先编写 `should_return_empty_204_after_unfavorite` 的WebMvcTest。当前用户7、歌曲42，执行DELETE并从MvcResult取得response，断言204、空正文、Cache-Control=no-store，验证只调用取消Service的unfavorite(42L,actor)。

```java
assertEquals(204, response.getStatus());
assertEquals("", response.getContentAsString());
assertEquals("no-store", response.getHeader("Cache-Control"));
```

- [x] 执行 `.\gradlew.bat test --tests "org.example.music.favorite.controller.*" --console=plain`，记录新增DELETE尚缺失导致的失败。
- [x] 在现有Controller构造器新增UnfavoriteTrackService注入，新增@DeleteMapping及@Positive Long路径参数，使用@AuthenticationPrincipal绑定主体。调用完成后返回204和no-store，补齐Tag关联、Operation、参数Schema及204/400/401/500/503的ApiResponse。
- [x] 在原FavoriteTrackControllerTest补充取消Service的MockitoBean，新取消Controller测试同时提供收藏与取消两个Service的MockitoBean。重跑原PUT断言，核对新增依赖后的MVC上下文正常启动。
- [x] Controller测试覆盖1、Long.MAX_VALUE合法值及0、-1、abc、1.5、9223372036854775808非法值。非法值400 VALIDATION_FAILED且取消Service无调用，未认证非法ID先返回401。
- [x] 503断言固定code、type、detail和no-store，500断言固定错误详情。伪造userId/role查询参数、身份头及JSON正文只传入原认证主体，收藏Service无调用。
- [x] 建立UnfavoriteTrackIntegrationTest，继承现有测试准备，正常路径使用真实Service与Repository。参数化USER/ADMIN和三种歌曲状态，断言本人行删除、其他用户与歌曲完整快照保留。
- [x] 使用现有F01 PUT收藏PUBLISHED后再归档的完整流程，验证USER仍可DELETE。管理员先以ADMIN收藏DRAFT或ARCHIVED，再以USER登录取消，仍只删除本人关系。
- [x] 首次取消、重复取消、本人未收藏及Long.MAX_VALUE对应歌曲不存在均为204。已删除歌曲的场景使用真实数据库DELETE及既有级联，随后调用取消接口并断言204。
- [x] 通过真实JWT验证无认证、错误签名、过期、缺少必要声明、token version失效、角色失效和账户不存在，认证拒绝时取消Repository无调用。有效JWT复用bearer，非法声明使用JwtEncoder并遵循当前测试issuer/audience。
- [x] 增加 `should_create_new_favorite_time_after_unfavorite_and_refavorite`。用Repository写入固定UTC首次时间t1，DELETE后断言关系缺失，再经F01 PUT重建，断言新created_at处于该请求前后UTC采样区间且与t1不同，updated_at等于created_at，审计用户均为本人。
- [x] 真实事务中调用条件DELETE后定点抛出数据访问异常，HTTP为503，原收藏及四个审计字段回滚保留。再模拟实际提交后事务管理器抛出确认丢失异常，HTTP为503而关系已缺失，恢复后重试为204。故障注入在finally恢复，正常请求使用真实持久化。
- [x] 对DELETE /api/v1/users/me/favorites、/api/v1/users/me/favorites/、/api/v1/users/me/favorites/42/，分别验证有认证与无认证的实际路由状态、媒体类型、错误正文及处理器，再固定实测结果为断言。断言收藏与取消Service无调用、收藏表无修改，将具体结果交任务4写入API文档。
- [x] 使用真实认证GET /v3/api-docs，断言同一资源路径的PUT和DELETE均存在，DELETE没有requestBody，204没有content，参数正数Long范围和五种响应码正确，PUT既有定义仍准确。
- [x] 建立UnfavoriteTrackConcurrencyIntegrationTest。参照已有F01并发测试的独立连接、事务同步点与performance_schema.data_lock_waits观察方式，使用有界Future等待和finally回收资源，执行真实锁竞争。
- [x] 同用户同歌并发12次DELETE，所有正常结果204，最终无本人记录。不同用户同歌并发各自取消，断言未参与操作的用户及另一首歌的快照保持原值。
- [x] 驱动收藏先提交、取消后提交，最终缺失；反向驱动取消先提交、收藏后提交，最终存在新记录。工作线程使用真实HTTP入口或现有事务Service，锁等待观察证明竞争已发生。
- [x] 驱动归档与取消的两个提交顺序，以及歌曲删除与取消的两个提交顺序，均按设计断言取消204和最终关系缺失。数据库删除竞争使用真实条件DELETE，完整媒体删除另由下项覆盖。
- [x] 实际连接会话设置innodb_lock_wait_timeout=1，另一事务持有歌曲锁，取消返回503且原收藏保留；释放后重试204。恢复原会话超时值，验证其他歌曲可在该歌曲被锁期间完成取消。
- [x] 在既有TrackDeleteIntegrationTest的收藏级联场景中，完整歌曲DELETE和真实MinIO媒体清理断言通过后，增加对同一歌曲的取消DELETE，断言204、收藏仍缺失、另一歌曲收藏保留。
- [x] 设置MINIO_INTEGRATION_TESTS=true后执行 `.\gradlew.bat test --tests "org.example.music.favorite.*" --tests "org.example.music.track.TrackDeleteIntegrationTest" --console=plain`。必要容器测试全部执行且通过后提交本任务，中文提交说明为 `feat: 提供本人取消收藏接口并验证并发语义`。

## 任务4 API文档、完整验证与自动独立审核

**文件** 新增API与审核材料，按实际结果更新F02设计、计划、任务书及交接记录。

**输入成果** 已实现的单一DELETE、专项测试及当前Git差异。

**输出成果** 中文API文档、完整check证据、Claude Code原始审核结果及逐项反馈处置。

- [x] 编写docs/api/favorite/unfavorite-track.md，包含Method、完整路径、认证与本人归属、全状态允许、无请求体、忽略额外输入、ID校验、204及响应头示例、错误code、再次收藏时间、并发顺序、首版限流决策和可执行curl。
- [x] 将任务3实测的缺ID与尾斜杠DELETE行为逐路径写入API文档，标明认证条件、状态码和媒体类型，对照OpenAPI与正常匹配接口的204语义核对。
- [x] 执行git diff --check，验证新增文件均属披露范围，V1至V9迁移、构建依赖及F01正常行为与设计范围一致。
- [x] 执行完整验证，等待进程完成并检查退出码。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

- [x] 汇总build/test-results/test/TEST-*.xml中的tests、failures、errors、skipped，通过数为tests减去其余三项。必要容器用例实际运行后记录真实总数，逐项列出任何失败或跳过及原因，修复可恢复故障并重跑必要验证。
- [x] 准备docs/reviews/2026-11-12-unfavorite-track-review-request.md，含唯一接口、需求权限、声称行为、含untracked的全部文件、API文档、数据库迁移为无、实际命令与数量、Git基线、curl、默认项和风险，首次F02反馈复核为不涉及。
- [ ] 按本机AGENTS.md第8节第7步自动调用Claude Code CLI，在项目根目录令其先读取本次请求、设计与CLAUDE.md，核对差异，独立复跑带MinIO的完整check，再逐项只读审查。工具集合Read/Glob/Grep/Bash，禁用Write/Edit/NotebookEdit，Bash按只读审查与验证职责使用。
- [ ] 第1轮输出分别保存至build/reports/unfavorite-review-round1.json和unfavorite-review-round1.stderr.log。使用--output-format json，持续监控到进程退出，核对JSON、is_error、result、permission_denials及必要测试证据，记录实际session_id、modelUsage和轮次。模型沿用本机配置。
- [ ] 将审核result原文与来源保存到docs/reviews/2026-11-12-unfavorite-track-review.md，Codex复核与原文分别记录。needs_revision逐项核实问题、分级和修改方向，修复确认成立的阻断项或举证请求复核，再执行必要验证并更新材料。
- [ ] 复审通过--resume接续原session_id，使用各轮独立的JSON与日志文件，保留原问题编号及轮次。同一任务最多3轮，escalated时停止修改并提交人类决策事项。
- [ ] accepted后记录所有非阻断项的处置，将F02移入任务书完成区，附实际提交、接口、审核和测试证据；更新PROJECT_STATE.md的F02进度并保留F01既有记录。按任务范围提交API文档、审核记录与完成登记，中文说明为 `docs: 登记取消收藏接口验证与独立审核结果`。

## 计划自检与执行方式

已将设计的HTTP契约、全状态本人取消、0行幂等、事务提交边界、重新收藏时间、并发顺序、真实容器、OpenAPI、中文文档和自动审核映射到四项任务。Repository、Service及Controller的方法名和参数顺序已统一。

执行方式沿用用户确定的Codex唯一实现、Claude Code独立只读审核。用户已审阅计划并授权实现，当前已完成代码、API文档及完整验证，185套件2169通过、0失败、0错误、0跳过，随后自动提交独立实现审核。
