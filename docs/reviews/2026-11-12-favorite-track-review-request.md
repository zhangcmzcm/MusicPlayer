# F01 收藏歌曲实现审核请求

日期为2026-11-12。用户已确认设计与实施计划，当前提交独立实现审核。

## 本次接口

`PUT /api/v1/users/me/favorites/{trackId}`。

## 需求与权限

- 需要有效登录，为认证主体本人收藏歌曲。
- USER仅允许PUBLISHED，ADMIN允许全部现存状态，管理员以USER角色登录时按USER处理。
- 歌曲不存在或当前不可见统一404 TRACK_NOT_FOUND，每次请求重新核验当前状态。
- 首次与重复请求均返回204，保留首次收藏时间，同一用户与歌曲只保留一条记录。
- 归档保留收藏关系，歌曲及用户记录彻底删除时由数据库级联清理。
- 首版无每用户收藏数量上限，暂不增加收藏专用限流。

## 声称实现的行为

- Controller绑定正整数Long歌曲ID，身份仅取AuthenticatedUserPrincipal，未知查询参数、附带正文及自定义头均不能选择收藏用户或角色。
- Service方法级认证，事务Service按歌曲主键排他锁定当前状态、检查角色并原子幂等写入，外层在事务提交后返回成功。
- user_favorites联合主键为(user_id,track_id)，外键集合固定为user_id、track_id两条CASCADE，审计列取当前用户并保持首次值。
- UTC Clock生成微秒时间，采用UTC文本参数与MySQL CAST DATETIME(6)写入，避免Hibernate/JVM默认时区导致时间偏移。
- 存储、完整性约束、锁等待、死锁及事务开启/提交故障统一503 FAVORITE_STORE_UNAVAILABLE，响应屏蔽SQL和底层异常详情，保留服务端traceId日志。
- 同一歌曲收藏与归档、删除按数据库锁顺序生效，不同歌曲可并行。真实锁等待超时后返回503，释放锁后重试成功。
- 实际提交成功但调用方收到故障时，后续重试仍保留首次时间。通过真实提交后注入确认丢失验证该情形。
- 新增OpenAPI、中文API文档及完整入口测试，现有歌曲DELETE入口经真实MinIO验证收藏级联和媒体清理。

## 变更文件，包含untracked

实现范围自设计基线08c78bb起包含以下文件。

- src/main/resources/db/migration/V9__create_user_favorites.sql
- src/main/java/org/example/music/favorite/repository/FavoriteRepository.java
- src/main/java/org/example/music/favorite/service/FavoriteTrackTransactionService.java
- src/main/java/org/example/music/favorite/service/FavoriteTrackService.java
- src/main/java/org/example/music/favorite/exception/FavoriteStoreUnavailableException.java
- src/main/java/org/example/music/favorite/controller/FavoriteTrackController.java
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/test/java/org/example/music/favorite/FavoriteIntegrationSupport.java
- src/test/java/org/example/music/favorite/FavoriteRepositoryIntegrationTest.java
- src/test/java/org/example/music/favorite/service/FavoriteTrackTransactionServiceTest.java
- src/test/java/org/example/music/favorite/service/FavoriteTrackServiceTest.java
- src/test/java/org/example/music/favorite/service/FavoriteTrackProxyTest.java
- src/test/java/org/example/music/favorite/controller/FavoriteTrackControllerTest.java
- src/test/java/org/example/music/favorite/FavoriteTrackIntegrationTest.java
- src/test/java/org/example/music/favorite/FavoriteTrackConcurrencyIntegrationTest.java
- src/test/java/org/example/music/track/TrackDeleteIntegrationTest.java
- docs/api/favorite/favorite-track.md
- docs/superpowers/specs/2026-11-11-favorite-track-design.md
- docs/superpowers/plans/2026-11-12-favorite-track-plan.md
- docs/reviews/2026-11-12-favorite-track-design-review.md
- docs/reviews/2026-11-12-favorite-track-review-request.md
- PROJECT_STATE.md，交接文件，开始任务时即为untracked，本轮增量登记实际设计、实现和验证进度。
- docs/tasks/backend-roadmap.md，任务开始前已有的用户任务书整理，包含模糊搜索及完成区重组，保留既有修改。其规划内容已披露，当前实现只涉及F01。

同接口测试公共准备集中在FavoriteIntegrationSupport，事务代理验证位于FavoriteTrackProxyTest，两者已登记至实施计划。GlobalExceptionHandler只增加收藏存储503映射，TrackDeleteIntegrationTest只增加收藏级联场景及该测试所需的DataSource注入。

## 数据库迁移与API文档

- 迁移为 `src/main/resources/db/migration/V9__create_user_favorites.sql`，V1至V8保持设计基线内容。
- API文档为 [收藏歌曲](../api/favorite/favorite-track.md)。
- 设计为 [F01设计](../superpowers/specs/2026-11-11-favorite-track-design.md)。
- 计划为 [F01计划](../superpowers/plans/2026-11-12-favorite-track-plan.md)。

## 测试

实际完整命令如下，PowerShell退出码0，BUILD SUCCESSFUL，耗时18分17秒。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

XML报告汇总179套件，2097通过、0失败、0错误、0跳过。跳过项为无。MySQL 8.4、Redis 7.4及MinIO均实际执行。

| 新增测试套件 | 通过 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| FavoriteRepositoryIntegrationTest | 6 | 0 | 0 | 0 |
| FavoriteTrackTransactionServiceTest | 7 | 0 | 0 | 0 |
| FavoriteTrackServiceTest | 7 | 0 | 0 | 0 |
| FavoriteTrackProxyTest | 4 | 0 | 0 | 0 |
| FavoriteTrackControllerTest | 13 | 0 | 0 | 0 |
| FavoriteTrackIntegrationTest | 16 | 0 | 0 | 0 |
| FavoriteTrackConcurrencyIntegrationTest | 9 | 0 | 0 | 0 |

以上新增62项，既有TrackDeleteIntegrationTest增加1项，基线2034项加63项等于2097项。完整输出位于本机build/favorite-full-check.log，XML位于build/test-results/test，HTML报告位于build/reports/tests/test/index.html。

实施过程中先运行缺类的Repository、Service和Controller测试，均出现预期编译失败。真实时区回归曾检出两个时间断言失败，已修正为UTC文本CAST写入后重跑通过。专项命令亦通过。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat test --tests "org.example.music.favorite.*" --tests "org.example.music.track.TrackDeleteIntegrationTest" --console=plain
```

git diff --check通过，已核对既有迁移差异。

## Git基线

- 设计提交为main @ 08c78bb。
- 持久化提交为cb38211。
- 权限与事务提交为b90125f。
- HTTP与并发提交为845c5a0。
- 审核范围为08c78bb之后的F01实现与本文列出的文档、交接状态；文档提交可通过当前git log核对。
- 先前设计审核第1/3轮accepted，零阻断项。本次为首次实现审核，保留既有任务与意见编号，审核轮次请按CLAUDE.md及既有记录登记。

## curl示例

ACCESS_TOKEN由调用者已有登录流程获得，123为可访问的歌曲ID。

```sh
curl --request PUT 'http://localhost:8080/api/v1/users/me/favorites/123' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```

成功为204空正文，Cache-Control为no-store。

## 未决问题与默认项

- 书面设计确认的角色、幂等、额度、限流、归档及删除规则均已实现。
- 路径缺ID及尾斜杠沿用当前全局路由行为，有效认证后三种测试路径为500 INTERNAL_ERROR，未认证为401。实测无收藏业务调用或写入，API文档已逐路径写明。
- 采用已确认的歌曲行排他锁，同一热门歌曲的不同用户写入也会短暂串行。
- 审核accepted及F01完成登记待独立实现审核结果。

## 审核反馈复核与处置

首次实现审核反馈为不涉及。既有设计审核两项非阻断意见沿用原编号，逐项回应如下。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001，非阻断 | 成立，设计对审计列外键有歧义，V2的RESTRICT惯例与本次用户删除级联规则需明确取舍 | 认可非阻断，属实施澄清 | 认可明确两条CASCADE外键并验证真实删除 | V9审计列为普通INT，user_id与track_id为唯一两条外键，审计值由服务端写入本人ID | FavoriteRepositoryIntegrationTest核验information_schema外键集合、真实用户删除与其他用户关系保留，6项全部通过，待独立核验 |
| NOTE-002，非阻断 | 成立，缺路径段的实际HTTP行为原先缺少证据 | 认可非阻断，属API文档完整性 | 认可真实路由测试后记录具体结果 | 三种路径及两种认证情况已断言，并验证ResourceHttpRequestHandler及业务无调用、无数据库新增 | FavoriteTrackIntegrationTest中该参数化用例3项通过，有效认证为500 INTERNAL_ERROR、未认证401，API文档已记录，待独立核验 |

设计审核记录见 [原设计结论与复核](2026-11-12-favorite-track-design-review.md)。
