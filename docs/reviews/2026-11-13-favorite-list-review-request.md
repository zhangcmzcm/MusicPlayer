# F03 本人收藏列表首次独立实现审核请求

完整自验已通过，现提交首次独立实现审核，轮次为1/3。送审快照为本请求所在提交，审核调用时另记录完整HEAD。

## 本次接口与基线

- `GET /api/v1/users/me/favorites`，仅一个新增接口。
- 实现基线 `main @ 8d52f35`，该提交已包含用户批准的设计。参数、存储、权限事务提交为 24d37b7、e167d70、e19bd90。
- [设计](../superpowers/specs/2026-11-12-favorite-list-design.md)、[计划](../superpowers/plans/2026-11-12-favorite-list-plan.md)、[API](../api/favorite/list-favorites.md)。

## 需求、权限与声称行为

- 有效 JWT，仅本人收藏，Integer 用户 ID 与角色来自 AuthenticatedUserPrincipal；ADMIN 也不能指定别人。
- USER 含上传者仅 PUBLISHED，ADMIN 默认全部状态，可单状态筛选；管理员以 USER 登录按 USER。USER 显式隐藏状态为403。
- page=0、size=20，1—100，page×size≤10000；仅 page/size/sort/status，未知、重复、空白、溢出及非法格式400。认证先于参数，参数先于状态权限。
- 五种单排序 id/title/artistName/createdAt/favoritedAt，默认 favoritedAt,desc，非ID追加同方向歌曲ID。createdAt仍是歌曲创建时间。
- 200分页平铺歌曲项加 UTC 微秒 favoritedAt，显式null字段、精确本人可见范围计数、合法越界空页和no-store。重复收藏保留时间，取消再收藏创建新时间。
- 内容、count、去重本页封面在一个REPEATABLE_READ短只读事务中。普通快照读取，不持有收藏写操作的歌曲排他锁；跨请求无快照保证。
- 每页最多三条业务SQL，认证另计；无封面或空页省略资产查询，不访问MinIO，不读ID3或Entity。
- 本页封面引用非法整页500，其他页/隐藏歌曲坏资产不影响；数据库元信息合法而对象缺失仍返回URL，后续封面接口独立处理。
- 数据与事务开始/结束故障503 FAVORITE_STORE_UNAVAILABLE，复用既有文案；认证状态故障沿用现有契约。无缓存、专用限流或新依赖。

## 迁移与执行计划

新增 `src/main/resources/db/migration/V10__add_favorite_list_index.sql`，仅增加 `(user_id,created_at,track_id)` 索引。V1—V9未修改。真实V9数据升级V10检查了行、审计字段、索引列序及两条级联外键。

真实MySQL 8.4计划产物为 `build/reports/favorite-list-query-plans.md`，测试用本人12000条、其他用户6000条、稀疏用户1条收藏，五字段双向、有无状态、offset=0/10000、count和代表性EXPLAIN ANALYZE。

初版普通JOIN计划显示可能先扫曲库；现使用STRAIGHT_JOIN由本人收藏索引驱动，再按歌曲主键关联。默认favoritedAt使用V10索引，稀疏样本只读本人1条候选；其他字段仍可能对本人候选排序，精确count仍与收藏量相关，不承诺固定延迟。

## 依赖改动与兼容说明

F03新增集合GET后，F01/F02缺少ID的精确集合路径由方法不匹配异常处理，原先为静态资源回退。对外仍是既有500 INTERNAL_ERROR，未认证仍401，写Service未调用且数据库不变。仅更新两项测试的内部处理器断言为明确HttpRequestMethodNotSupportedException断言，以及两份API文档的说明；尾斜杠仍保留静态处理器断言。未删失败断言或弱化外部契约。

## 已执行验证与当前状态

- 参数先失败后实现、Repository先失败后实现、Service先失败后实现、Controller先失败后实现，阶段命令与期望记录在计划中。编译失败记录不计作测试通过。
- 新增真实HTTP/认证/封面与并发快照专项：2套件31通过、0失败、0错误、0跳过。
- 收藏模块及歌曲列表扩大回归：28套件346项，344通过、2失败、0错误、0跳过，日志 `build/favorite-list-scoped-check.log`；两项失败为上述缺少ID内部路由断言。
- 修复后F01/F02真实集成复跑：2套件41通过、0失败、0错误、0跳过，日志 `build/favorite-list-routing-regression.log`，耗时1分6秒。
- 完整验证命令为 `$env:MINIO_INTEGRATION_TESTS = 'true'; .\gradlew.bat cleanTest check --console=plain`，日志 `build/favorite-list-full-check.log`；退出码0，BUILD SUCCESSFUL，耗时20分16秒。XML汇总194套件2293通过、0失败、0错误、0跳过，其中新增9套件124项。MySQL、Redis、MinIO必要测试均实际执行。

独立审核请重新执行同一完整命令，按XML汇总套件、通过、失败、错误、跳过。必要容器不能跳过，不能仅凭本请求记录判accepted。

## 变更范围

以下清单包含相对基线的已提交、未提交及untracked文件。送审前再次核对工作树与提交范围。审核完成后另增同目录2026-11-13-favorite-list-review.md保存原文与处置。

- `docs/api/favorite/favorite-track.md`
- `docs/api/favorite/list-favorites.md`
- `docs/api/favorite/unfavorite-track.md`
- `docs/reviews/2026-11-13-favorite-list-review-request.md`
- `docs/superpowers/plans/2026-11-12-favorite-list-plan.md`
- `docs/superpowers/specs/2026-11-12-favorite-list-design.md`
- `docs/tasks/backend-roadmap.md`
- `PROJECT_STATE.md`
- `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`
- `src/main/java/org/example/music/favorite/controller/FavoriteListController.java`
- `src/main/java/org/example/music/favorite/dto/FavoriteListItemResponse.java`
- `src/main/java/org/example/music/favorite/exception/FavoriteListValidationException.java`
- `src/main/java/org/example/music/favorite/repository/FavoriteListRepository.java`
- `src/main/java/org/example/music/favorite/repository/FavoriteListRow.java`
- `src/main/java/org/example/music/favorite/service/FavoriteListParameters.java`
- `src/main/java/org/example/music/favorite/service/FavoriteListQueryService.java`
- `src/main/java/org/example/music/favorite/service/FavoriteListService.java`
- `src/main/java/org/example/music/favorite/service/FavoriteListSort.java`
- `src/main/java/org/example/music/favorite/web/FavoriteListErrorAdvice.java`
- `src/main/resources/db/migration/V10__add_favorite_list_index.sql`
- `src/test/java/org/example/music/favorite/controller/FavoriteListControllerTest.java`
- `src/test/java/org/example/music/favorite/FavoriteListIntegrationTest.java`
- `src/test/java/org/example/music/favorite/FavoriteListQueryPlanIntegrationTest.java`
- `src/test/java/org/example/music/favorite/FavoriteListRepositoryIntegrationTest.java`
- `src/test/java/org/example/music/favorite/FavoriteListSnapshotIntegrationTest.java`
- `src/test/java/org/example/music/favorite/FavoriteTrackIntegrationTest.java`
- `src/test/java/org/example/music/favorite/service/FavoriteListParametersTest.java`
- `src/test/java/org/example/music/favorite/service/FavoriteListProxyTest.java`
- `src/test/java/org/example/music/favorite/service/FavoriteListQueryServiceTest.java`
- `src/test/java/org/example/music/favorite/service/FavoriteListServiceTest.java`
- `src/test/java/org/example/music/favorite/UnfavoriteTrackIntegrationTest.java`

## curl

```sh
curl --get 'http://localhost:8080/api/v1/users/me/favorites' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'page=0' --data-urlencode 'size=20' \
  --data-urlencode 'sort=favoritedAt,desc'
```

## 未决事项与反馈复核

- 无未决关键业务。保留既有未匹配路由全局500行为，独立治理不纳入本次。
- 首次实现审核，反馈复核与处置不涉及；后续保留原编号及轮次，按AGENTS第3.1节逐项处理，不自行撤销阻断项。
- 独立审核沿用本机Claude Code默认模型，并记录实际modelUsage、session_id、退出码、is_error及permission_denials。审核原文与Codex处置分开保存。
