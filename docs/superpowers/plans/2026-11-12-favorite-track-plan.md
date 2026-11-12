# F01 收藏歌曲实现计划

> 执行者遵循AGENTS.md，由Codex在当前会话逐任务实现，Claude负责独立只读审核。每步使用复选框记录实际完成情况。

**目标** 为当前登录用户提供 `PUT /api/v1/users/me/favorites/{trackId}`，首次与重复收藏均返回204，支持已确认的角色权限、并发幂等及删除级联。

**架构** 新增favorite模块，Controller负责HTTP契约，外层Service负责授权与存储故障翻译，事务Service负责锁定歌曲、验证可见性及原子写入。Repository使用EntityManager原生SQL，V9创建联合主键和级联外键，成功响应发生在事务提交之后。

**技术栈** Java 21、Spring Boot 4.1.1、Spring MVC/Validation/Security、JPA、Flyway、MySQL 8.4、JUnit 5、MockMvc、Testcontainers MySQL/Redis/MinIO，沿用build.gradle现有依赖。

**设计依据** [F01设计](../specs/2026-11-11-favorite-track-design.md)，用户于2026-11-12批准。计划编写基线为main @ 08c78bb，工作目录为 `D:\code\Java-code\projects\music`。

独立设计审核第1/3轮为accepted，零阻断项。NOTE-001与NOTE-002的复核及处置见[设计审核记录](../../reviews/2026-11-12-favorite-track-design-review.md)，两项均纳入本计划。

## 全局约束

- 本次接口为 `PUT /api/v1/users/me/favorites/{trackId}`。
- 无请求体，首次与重复收藏均返回204，保留首次收藏时间。
- USER只能收藏PUBLISHED，ADMIN可收藏三种现存状态，角色取当前loginRole。
- 收藏归属取Integer userId，歌曲使用正整数Long，沿用既有ID类型。
- 首版不设每用户收藏数量上限，暂不增加收藏专用限流。
- 归档保留关系，歌曲与用户记录删除通过数据库外键级联清理。
- 现有迁移为V1至V8，新增V9前再次核对版本号，ddl-auto保持none。
- 错误使用ProblemDetail、稳定code及urn:problem:music:*，业务响应使用no-store。
- API文档、设计、实施计划和git commit使用中文。
- 保留任务书未提交修改及PROJECT_STATE.md，提交时逐文件列明范围。
- 验收执行设置MINIO_INTEGRATION_TESTS=true后的 `.\gradlew.bat check`，必要容器用例需实际运行。

## 审查重点

1. 已收藏歌曲归档后，USER重试仍返回404且保留原关系，由任务2和任务3验证。
2. 管理员切换USER/ADMIN登录角色时，同一userId保持一条记录且权限即时按loginRole判断，由任务2和任务3验证。
3. UTC时间纳秒截断及非UTC JVM时区下的数据库时间一致性，由任务1和任务2验证。
4. 提交成功后客户端收到故障再重试，首次审计字段保持原值，由任务2和任务3验证。
5. 歌曲锁竞争超时、并发用户删除或外键故障均回滚并返回503，后续请求可重新执行，由任务2和任务3验证。

## 文件与接口结构

路径均相对项目根目录。

| 动作 | 文件 | 职责 |
| --- | --- | --- |
| 新建 | src/main/resources/db/migration/V9__create_user_favorites.sql | 表、联合主键、索引及外键 |
| 新建 | src/main/java/org/example/music/favorite/repository/FavoriteRepository.java | 歌曲锁定读与幂等插入 |
| 新建 | src/main/java/org/example/music/favorite/service/FavoriteTrackTransactionService.java | 事务内权限与原子持久化 |
| 新建 | src/main/java/org/example/music/favorite/service/FavoriteTrackService.java | 方法级认证、事务调用及错误翻译 |
| 新建 | src/main/java/org/example/music/favorite/exception/FavoriteStoreUnavailableException.java | 收藏存储故障 |
| 新建 | src/main/java/org/example/music/favorite/controller/FavoriteTrackController.java | PUT、路径校验、204、OpenAPI |
| 修改 | src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java | 收藏503映射 |
| 新建 | src/test/java/org/example/music/favorite/FavoriteRepositoryIntegrationTest.java | 真实MySQL迁移、SQL、审计与级联 |
| 新建 | src/test/java/org/example/music/favorite/FavoriteIntegrationSupport.java | MySQL/Redis测试容器、隔离数据及JWT公共测试准备 |
| 新建 | src/test/java/org/example/music/favorite/service/FavoriteTrackTransactionServiceTest.java | 状态权限、时间及调用顺序 |
| 新建 | src/test/java/org/example/music/favorite/service/FavoriteTrackServiceTest.java | 外层认证和故障翻译 |
| 新建 | src/test/java/org/example/music/favorite/service/FavoriteTrackProxyTest.java | 实际Spring方法安全与事务代理验证 |
| 新建 | src/test/java/org/example/music/favorite/controller/FavoriteTrackControllerTest.java | HTTP契约与OpenAPI |
| 新建 | src/test/java/org/example/music/favorite/FavoriteTrackIntegrationTest.java | 真实认证链及收藏入口 |
| 新建 | src/test/java/org/example/music/favorite/FavoriteTrackConcurrencyIntegrationTest.java | 锁、重复请求、归档及删除并发 |
| 修改 | src/test/java/org/example/music/track/TrackDeleteIntegrationTest.java | 通过完整删除入口核对收藏级联 |
| 新建 | docs/api/favorite/favorite-track.md | 中文API文档 |
| 新建 | docs/reviews/2026-11-12-favorite-track-review-request.md | 首次送审材料 |

接口没有成功响应正文，Controller直接使用 `ResponseEntity<Void>`。

## 任务1 收藏持久化与数据库约束

涉及迁移、FavoriteRepository与FavoriteRepositoryIntegrationTest。

**输入接口** EntityManager、Spring事务及现有users、tracks结构。

**输出接口** `Optional<TrackStatus> FavoriteRepository.lockTrackStatus(Long trackId)`，`void FavoriteRepository.addIfAbsent(Integer userId, Long trackId, LocalDateTime createdAt)`。Repository通过构造器注入EntityManager，由调用者提供事务。

- [x] 新增真实MySQL 8.4测试，沿用TrackListQueryIntegrationTest的容器接入方式，在事务内执行Repository。先编写 `should_keep_first_audit_fields_when_favorited_again`，两次使用不同createdAt调用addIfAbsent，断言count为1，四个审计字段均为首次值。
- [x] 执行 `.\gradlew.bat test --tests "org.example.music.favorite.FavoriteRepositoryIntegrationTest" --console=plain`，记录新类缺失导致的编译失败，或新表缺失导致的运行失败。
- [x] 新建V9，六列均非空，联合主键(user_id,track_id)，显式track_id索引。外键集合固定为user_id指向users.id和track_id指向tracks.id两条，均ON DELETE CASCADE。created_by与updated_by为普通INT审计列，首次值均取当前userId。引擎、字符集和排序规则沿用V2，此为NOTE-001的实施决定。
- [x] 实现lockTrackStatus，使用参数化主键 `SELECT status FROM tracks WHERE id = :trackId FOR UPDATE`，读取最新状态投影，空结果返回Optional.empty。
- [x] 实现addIfAbsent，参数化插入六列，created_at与updated_at相同，created_by与updated_by均为userId，使用重复键原值更新。忽略驱动对无变化更新的影响行数差异，以SQL正常完成判断成功。
- [x] 增加 `should_cascade_only_favorites_for_deleted_track` 和 `should_cascade_favorites_for_deleted_user`。使用真实DELETE与隔离数据断言目标关系清除、其他关系保留，删用户的测试用户只关联收藏。
- [x] 增加迁移验证，查询information_schema与flyway_schema_history，断言V9成功、六列类型与非空、联合主键顺序、track_id索引。查询user_favorites的全部外键，断言数量恰为2，约束列集合为user_id和track_id且DELETE_RULE均为CASCADE。结合只关联收藏的用户删除测试验证NOTE-001。通过真实并发插入验证唯一性。
- [x] 增加 `should_preserve_utc_microseconds_under_non_utc_default_timezone`，使用受控非UTC JVM默认时区，验证UTC本地时间写入文本值正确，finally恢复默认时区。测试避免与其他时区用例并行。
- [x] 重跑任务1命令，要求0失败、0错误、0跳过，记录实际数量后提交迁移、Repository和对应测试，提交信息为 `feat: 建立歌曲收藏关系及幂等存储`。

## 任务2 收藏事务、权限及故障翻译

涉及两个Service、异常及两个Service测试类。

**输入接口** 任务1的两个Repository方法，AuthenticatedUserPrincipal(Integer userId, UserRole loginRole)，现有UTC Bean `authenticationClock`。

**输出接口** `void FavoriteTrackTransactionService.favorite(Long trackId, AuthenticatedUserPrincipal actor)`，`void FavoriteTrackService.favorite(Long trackId, AuthenticatedUserPrincipal actor)`，`FavoriteStoreUnavailableException(Throwable cause)`。

- [x] 在事务Service测试中先编写 `should_recheck_visibility_before_duplicate_insert`。Repository返回ARCHIVED，USER调用抛TrackNotFoundException，验证addIfAbsent从未调用。为ADMIN三状态、USER PUBLISHED及不存在状态补齐参数化测试。
- [x] 执行 `.\gradlew.bat test --tests "org.example.music.favorite.service.*" --console=plain`，记录缺少新Service的失败。
- [x] 实现事务Service，public favorite使用@Transactional，按锁定读、可见性校验、写入顺序执行。UTC Clock使用Qualifier定位authenticationClock，取Instant并截断到微秒后转换UTC LocalDateTime，传给addIfAbsent。
- [x] 单元测试用固定Instant `2026-11-12T00:00:00.123456789Z`，断言Repository接收 `2026-11-12T00:00:00.123456`，验证userId只来自actor。
- [x] 实现外层Service，public favorite使用 `@PreAuthorize("isAuthenticated()")`，通过注入的事务Service调用，捕获DataAccessException与TransactionException并包装FavoriteStoreUnavailableException，保留cause供服务端日志定位。
- [x] 先编写外层 `should_translate_transaction_commit_failure`，由被代理调用模拟TransactionSystemException，断言包装异常cause保留。覆盖CannotCreateTransactionException、DataAccessResourceFailureException、锁故障、完整性约束故障，验证TrackNotFoundException原样传播。
- [x] 使用独立小型Spring测试上下文验证事务代理实际生效。测试PlatformTransactionManager在提交前抛异常与先成功提交再抛异常两条路径，断言外层转换为503对应异常，且成功返回必须发生于提交之后。采用真实MySQL的回滚与结果重试在任务3补齐。
- [x] 增加未认证Service代理调用的拒绝测试，断言事务Service未调用。USER与ADMIN经认证均能进入业务处理。
- [x] 重跑任务2命令，要求全部新单元及代理测试通过，按实际数量记录后提交，提交信息为 `feat: 实现收藏权限校验与事务处理`。

## 任务3 HTTP入口与端到端并发验证

涉及Controller、GlobalExceptionHandler、新增HTTP和集成测试、既有TrackDeleteIntegrationTest。

**输入接口** 任务2外层Service及异常，既有SecurityConfig、TrackNotFoundException、全局400处理、JwtTokenService.issue(UserAuthorizationSnapshot, UserRole, LoginType, Instant)。

**输出接口** `ResponseEntity<Void> FavoriteTrackController.favorite(Long trackId, AuthenticatedUserPrincipal actor)`，映射 `PUT /api/v1/users/me/favorites/{trackId}`。

- [x] 在WebMvcTest中先编写 `should_return_no_content_after_favorite`，认证主体为userId=7、USER，PUT歌曲42，断言204、空正文、Cache-Control=no-store，验证Service收到(42L, actor)。
- [x] 执行 `.\gradlew.bat test --tests "org.example.music.favorite.controller.*" --console=plain`，记录Controller缺失的编译失败。
- [x] 实现Controller，路径变量使用@Positive Long，主体使用@AuthenticationPrincipal，不绑定请求体。添加Tag、Operation、Schema与参数描述，204、400、401、404、500、503的ApiResponse与现有认证响应约定一致。
- [x] 在GlobalExceptionHandler新增FavoriteStoreUnavailableException映射，返回503、FAVORITE_STORE_UNAVAILABLE、urn:problem:music:favorite_store_unavailable及no-store。title为“收藏存储不可用”，detail为“歌曲收藏暂时无法完成”，日志包含traceId、exceptionType及throwable。
- [x] 补齐Controller参数化测试，ID为0、-1、abc、1.5、9223372036854775808时返回400且Service无交互，1与9223372036854775807合法绑定。未认证非法ID仍先返回401。
- [x] 按NOTE-002在真实SpringBootTest路由上下文增加 `should_document_actual_routing_for_missing_id_and_trailing_slash`，请求PUT /api/v1/users/me/favorites、/api/v1/users/me/favorites/、/api/v1/users/me/favorites/42/，分别携带有效认证与省略认证。先观测实际状态、媒体类型、错误正文和处理器，再固定真实结果为回归断言，验证收藏Service无调用、数据库无新增。未认证沿现有401链处理，已认证的路由错误与业务404 TRACK_NOT_FOUND分别写明触发路径。该步骤完成前NOTE-002保持待验证。
- [x] 覆盖404、503及500的ProblemDetail，验证无SQL等底层错误泄漏。添加未知查询参数、伪造用户/角色请求头与附带JSON的请求，验证Service始终接收同一认证主体。
- [x] 建立FavoriteTrackIntegrationTest，沿用TrackListIntegrationTest的MySQL 8.4和Redis 7.4动态属性及真实JWT签发方式，测试用户与歌曲使用真实Repository种入。成功与正常并发路径使用真实持久化。
- [x] 真实HTTP覆盖USER PUBLISHED、USER两种隐藏状态404、ADMIN三状态、ADMIN以USER登录、同一账户切换登录角色、不同用户分别收藏、未知歌曲。查库断言所有审计字段、唯一关系和其他用户记录。
- [x] 真实认证链覆盖未登录、错误签名、过期、token version失效、账户消失和登录角色失效，沿用原有401契约。缺失或错误必要声明沿用INVALID_TOKEN处理。
- [x] 首次收藏后通过现有归档Service归档，断言USER重复PUT返回404且原收藏保留，ADMIN对该歌曲PUT成功。同一管理员跨角色收藏已发布歌曲只产生一条记录。
- [x] 建立FavoriteTrackConcurrencyIntegrationTest，使用ExecutorService、CountDownLatch与有时限的Future.get调度独立连接事务。使用数据库锁等待状态或事务同步点确认竞争实际发生，结束时finally释放锁与线程资源。
- [x] 覆盖同一用户同一歌曲并发PUT，全部正常完成为204，最终只有一条记录且审计值稳定。覆盖不同用户同歌和不同歌曲并行操作。
- [x] 在可控事务内先锁歌并完成收藏，再驱动既有归档Service等待，释放锁后验证归档成功且关系保留。反向控制归档先提交，验证USER收藏404、ADMIN成功。
- [x] 在数据库事务控制删除先提交、收藏先提交两种顺序，验证404或204及最终无悬空关系。并发删除用户时验证外键阻止悬空写入，外层返回存储503，后续请求按既有认证规则失效。
- [x] 给测试连接设置会话级innodb_lock_wait_timeout=1，使用另一连接持有歌曲锁，验证收藏503及无写入。释放后重试成功。测试退出时回收连接，避免会话设置污染其他用例。
- [x] 在事务持久化后定点注入提交前故障，断言真实数据库回滚。另行模拟实际提交后对调用方抛出TransactionSystemException，重试PUT后断言一条记录及首次时间不变。
- [x] 在TrackDeleteIntegrationTest新增 `should_remove_favorites_when_archived_track_is_deleted`。使用已配置的真实MinIO上传媒体、收藏归档歌曲，经现有DELETE入口删除后验证歌曲、媒体清理与收藏级联，另一个歌曲的收藏保持正确。
- [x] 执行 `$env:MINIO_INTEGRATION_TESTS = "true"` 后运行 `.\gradlew.bat test --tests "org.example.music.favorite.*" --tests "org.example.music.track.TrackDeleteIntegrationTest" --console=plain`，所有新增HTTP与真实容器测试通过后提交，提交信息为 `feat: 提供本人收藏歌曲接口并验证并发清理`。

## 任务4 API文档、完整验证与送审材料

涉及API文档、审核请求和交接进度。

**输入接口** 已完成的单一PUT及真实测试报告。

**输出成果** 可执行中文API文档、完整check记录、Claude首次审核材料。

- [x] 编写docs/api/favorite/favorite-track.md，包含Method、完整Path、用途、认证/角色/本人归属、Long正整数范围、无请求体、无业务查询参数、204空响应、所有错误code、重复请求、归档与级联规则、并发顺序、无数量上限、首版专用限流决策及curl。
- [x] 按任务3实测结果补充缺歌曲ID与尾斜杠请求的状态码、媒体类型及错误响应，列明完整触发路径和认证前置条件，并将对应测试名及结果写入NOTE-002处置记录。
- [x] 对照Controller与实际MockMvc结果核对OpenAPI及文档，验证curl环境变量为ACCESS_TOKEN，示例不含真实凭据。使用真实认证GET /v3/api-docs，检查收藏路由、PUT操作与204空响应定义。
- [x] 执行git diff --check，核对迁移版本与差异文件，验证已执行V1至V8内容保持基线版本。按设计逐条核对测试映射及新增测试是否实际执行。
- [x] 运行完整验证。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

- [x] 从build/test-results/test/TEST-*.xml汇总tests、failures、errors、skipped，通过数为tests减去其余三项。失败或跳过时逐项记录类名、方法、原因，诊断并修复可恢复故障后重跑完整check。
- [x] 编写首次审核请求，接口为PUT /api/v1/users/me/favorites/{trackId}，基线包括设计08c78bb与实现提交，列出含untracked的全部文件，明确原有任务书修改、交接文件与本次实现的关系。
- [x] 审核材料包含需求权限、声称行为、API文档、V9迁移、curl、实际命令及通过/失败/错误/跳过数量。首次实现审核同时附设计审核来源和NOTE-001/NOTE-002逐项处置，保持原编号与原非阻断分级，列明问题、分级和方向判断、证据、实际处置及验证结果。未实际执行的验证如实登记。
- [x] 更新PROJECT_STATE.md实际进度并提交本任务API文档和审核材料，提交信息为 `docs: 补齐收藏歌曲接口文档与验证材料`。F01状态在独立审核accepted后更新为已完成。
- [x] 按AGENTS.md交由Claude独立只读审核，同一任务最多3轮。反馈沿用原编号，先核实问题、分级与方向，再修复或举证请求复核。escalated时整理待决事项交人类裁定。

## 计划自检与执行方式

已将设计中的HTTP、权限、迁移、幂等、审计时间、级联、并发、故障、OpenAPI与完整check映射至任务1至任务4，复核了Service和Repository签名一致性。

本项目已指定Codex唯一实现、Claude独立只读审核。用户已审阅计划并确认实施。全部任务已完成，自验与独立复跑均为179套件2097通过、0失败、0错误、0跳过，MySQL/Redis/MinIO实际运行。独立审核accepted，NOTE-001/002关闭，NOTE-003/004已逐项复核并记录不采纳或排期理由，详见 `docs/reviews/2026-11-12-favorite-track-review.md`。
