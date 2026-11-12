# F02 取消收藏歌曲实现审核请求

日期为2026-11-12，用户已批准设计及实施计划，当前首次提交F02独立实现审核。

## 本次接口

`DELETE /api/v1/users/me/favorites/{trackId}`。

## 需求与权限

- 需要有效Bearer登录，操作用户仅取AuthenticatedUserPrincipal.userId。
- USER和ADMIN均可取消本人DRAFT、PUBLISHED、ARCHIVED歌曲收藏，管理员以USER登录同样适用。
- 首次取消、重复取消、本人未收藏、歌曲不存在或已删除均为204，空正文，Cache-Control为no-store。
- 路径歌曲ID为1至Long.MAX_VALUE的整数，非法值400 VALIDATION_FAILED。
- 无请求体，额外查询参数、正文及身份头均不参与业务或归属判断。
- 取消后再次收藏通过现有F01创建新记录，取该次收藏的UTC微秒时间与本人审计值。
- 首版沿用既有专用限流决策，暂不增加取消专用限流。

## 声称实现的行为

- 新增一个DELETE入口，复用现有Controller、Repository、歌曲排他行锁、V9表和收藏存储故障映射。
- 事务Service先锁歌曲，缺失时正常结束事务，存在时按认证用户ID与歌曲ID执行参数化条件DELETE。0行和1行均正常提交，外层Service返回后Controller构造204。
- 复用FavoriteStoreUnavailableException，将数据访问、锁等待与事务启动/提交故障转换为503 FAVORITE_STORE_UNAVAILABLE。
- 同一用户同一歌曲并发收藏和取消，两个事务都提交成功时，后取得歌曲锁的操作决定最终关系；取消与归档、彻底删除的两个提交顺序均符合设计。
- 同歌不同用户写入仍按歌曲锁串行，其他歌曲可独立处理。其他用户与歌曲的完整收藏审计字段保持原值。
- 提交前故障回滚恢复原收藏及审计字段，实际提交后确认丢失返回503，重试幂等204。缺歌曲和缺收藏两条路径同样覆盖提交故障。
- 保留F01 PUT契约与OpenAPI回归，现有真实MinIO歌曲删除测试追加删除后取消仍204的断言。

## 变更文件，含untracked

范围为设计基线825f04e之后的F02实现和已披露的设计/计划/验证进度更新。

- src/main/java/org/example/music/favorite/repository/FavoriteRepository.java
- src/main/java/org/example/music/favorite/service/UnfavoriteTrackTransactionService.java
- src/main/java/org/example/music/favorite/service/UnfavoriteTrackService.java
- src/main/java/org/example/music/favorite/controller/FavoriteTrackController.java
- src/test/java/org/example/music/favorite/FavoriteRepositoryIntegrationTest.java
- src/test/java/org/example/music/favorite/service/UnfavoriteTrackTransactionServiceTest.java
- src/test/java/org/example/music/favorite/service/UnfavoriteTrackServiceTest.java
- src/test/java/org/example/music/favorite/service/UnfavoriteTrackProxyTest.java
- src/test/java/org/example/music/favorite/controller/UnfavoriteTrackControllerTest.java
- src/test/java/org/example/music/favorite/controller/FavoriteTrackControllerTest.java
- src/test/java/org/example/music/favorite/UnfavoriteTrackIntegrationTest.java
- src/test/java/org/example/music/favorite/UnfavoriteTrackConcurrencyIntegrationTest.java
- src/test/java/org/example/music/track/TrackDeleteIntegrationTest.java
- docs/api/favorite/unfavorite-track.md
- docs/superpowers/specs/2026-11-12-unfavorite-track-design.md
- docs/superpowers/plans/2026-11-12-unfavorite-track-plan.md
- docs/reviews/2026-11-12-unfavorite-track-review-request.md
- docs/tasks/backend-roadmap.md，F02当前进度登记
- PROJECT_STATE.md，在文件开头增量登记F02当前状态，原F01历史区保留

Controller新增取消Service构造器依赖，因此原FavoriteTrackControllerTest补充对应MockitoBean。TrackDeleteIntegrationTest只在既有收藏级联场景末尾追加取消DELETE及其他收藏保留断言。正常持久化与并发使用真实数据库，spy故障注入仅用于异常验证。

## 数据库迁移与API文档

- 数据库迁移为无，V1至V9与设计基线一致，复用V9的(user_id,track_id)联合主键及两条CASCADE外键。
- [取消收藏API](../api/favorite/unfavorite-track.md)。
- [已确认设计](../superpowers/specs/2026-11-12-unfavorite-track-design.md)。
- [实施计划](../superpowers/plans/2026-11-12-unfavorite-track-plan.md)。

## 实际验证

完整命令如下，退出码0，BUILD SUCCESSFUL，耗时18分49秒。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

XML汇总185套件，2169通过、0失败、0错误、0跳过。跳过项为无。MySQL 8.4、Redis 7.4和MinIO实际执行。日志位于本机build/unfavorite-full-check.log，XML位于build/test-results/test，HTML位于build/reports/tests/test/index.html。

| 相关套件 | 通过 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| FavoriteRepositoryIntegrationTest | 9，其中新增3 | 0 | 0 | 0 |
| UnfavoriteTrackTransactionServiceTest | 9 | 0 | 0 | 0 |
| UnfavoriteTrackServiceTest | 7 | 0 | 0 | 0 |
| UnfavoriteTrackProxyTest | 6 | 0 | 0 | 0 |
| UnfavoriteTrackControllerTest | 12 | 0 | 0 | 0 |
| UnfavoriteTrackIntegrationTest | 25 | 0 | 0 | 0 |
| UnfavoriteTrackConcurrencyIntegrationTest | 10 | 0 | 0 | 0 |
| TrackDeleteIntegrationTest | 10，追加既有场景的取消断言 | 0 | 0 | 0 |

新增6套件69项，Repository增加3项，共新增72项，2097+72=2169。原F01的HTTP、Service、并发及存储用例全部随完整check运行。

专项验证使用同一MinIO环境变量，命令为 `.\gradlew.bat test --tests "org.example.music.favorite.*" --tests "org.example.music.track.TrackDeleteIntegrationTest" --console=plain`，14套件144通过、0失败、0错误、0跳过。

测试驱动阶段已观察缺少Repository方法、缺少Service类的预期编译失败。DELETE接入前Controller测试25项中10项失败；接入后PUT与DELETE的25项Controller测试全部通过。git diff --check通过，已核对迁移差异及送审代码快照。

## Git基线

- 设计基线为main @ 825f04e，业务代码与数据库基于F01已accepted版本。
- cbdeb5c为条件删除存储与真实MySQL测试。
- 6a6e036为取消事务、权限及代理测试。
- a6b9a86为DELETE入口、HTTP/认证/并发及MinIO回归。
- 本文列出的API、计划、设计确认、任务书及交接进度随后作为同接口文档提交，当前HEAD以git log核对。
- F02为首次实现审核，第1/3轮，首次反馈复核不涉及。F01既有非阻断项处置作为复用组件历史证据保留。

## curl

ACCESS_TOKEN由现有登录流程获得，123为合法歌曲ID，可对应已删除或未收藏歌曲。

```sh
curl --request DELETE 'http://localhost:8080/api/v1/users/me/favorites/123' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```

## 未决问题、默认项与风险

- 用户已确认的契约及并发规则均已实现，当前待独立实现审核。
- 缺ID及尾斜杠DELETE沿用现有全局500行为，未认证先401；真实路由测试确认业务无调用、表无修改，API文档已逐路径写明。全局路由治理为F01 NOTE-004的已登记后续独立事项。
- 同歌不同用户的写入会因歌曲排他锁串行，取舍已随设计确认。
- Awaitility由现有spring-boot-starter-test引入，F01 NOTE-003已记录本轮保留现有声明的依据，当前依赖配置与基线一致。

## 审核反馈复核与处置

首次F02提交为不涉及。后续反馈沿用原问题编号、分级和轮次，逐项记录问题事实、分级判断、修改方向、实际处置及验证。审核JSON、日志、原文及Codex复核记录按各轮保存，复审使用同一Claude Code session_id。
