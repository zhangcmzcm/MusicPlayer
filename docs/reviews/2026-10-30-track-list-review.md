# 分页歌曲列表接口首轮代码审核请求

本次接口：`GET /api/v1/tracks`。代码审核第 1 轮；设计和实现计划已获用户确认，本任务尚无 Claude 代码审核结论。

## 需求与权限

需要有效登录。ADMIN 登录角色缺省查询全部状态，支持单一 DRAFT/PUBLISHED/ARCHIVED 筛选；其他用户含上传者本人缺省及显式 PUBLISHED 仅查询已发布歌曲，显式隐藏状态 403 FORBIDDEN。管理员以 USER 角色登录降权。全部参数有效后才判断状态筛选权限，未认证先经认证链。

## 声称实现的行为

- page=0、size=20、sort=id,desc；size 1—100、long 乘积 page×size<=10000；未知/空/重复/非法参数 400 VALIDATION_FAILED。
- 排序白名单 id/title/artistName/createdAt，方向严格小写，非 ID 排序补同向 id；排序和状态均受控，原始输入不拼接 SQL。
- PageResponse 明确返回 items/page/size/totalElements/totalPages/hasNext，计数 long；可空专辑、时长、封面明确为 null，空集合/越界页 200 且总数准确。
- 数据库按授权状态过滤内容和 count，同一 RR 只读事务完成；独立查询 Bean 使事务启动及数据库故障转换 503。
- 同一条内容 SQL 从覆盖索引分页取得 ID，再回表读本页投影；不取 Entity 或 ID3。count 独立一条，本页去重封面批量一条，最多三条业务 SQL；HTTP 的既有认证查询额外一条。
- 批量资产仅校验本页引用：无引用为 null；缺记录/非 COVER/非法本地元信息使本页 500，不访问 MinIO。对象实际是否存在留给封面接口检查。
- 成功/业务错误 no-store；不影响封面接口 private, no-cache。GlobalExceptionHandler 只增加本列表参数异常映射，错误码复用现有规范。
- 实现 OpenAPI、中文 API 文档、更新 T02 待审核与 T05 基础状态能力重叠说明。

## 变更文件（含 untracked）

- docs/superpowers/specs/2026-10-30-track-list-design.md
- docs/tasks/backend-roadmap.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/main/java/org/example/music/track/repository/MediaAssetRepository.java
- docs/api/track/list-tracks.md
- docs/reviews/2026-10-30-track-list-query-plans.md
- docs/superpowers/plans/2026-10-30-track-list-plan.md
- src/main/java/org/example/music/common/dto/PageResponse.java
- src/main/java/org/example/music/track/controller/TrackListController.java
- src/main/java/org/example/music/track/dto/TrackListItemResponse.java
- src/main/java/org/example/music/track/exception/TrackListValidationException.java
- src/main/java/org/example/music/track/repository/TrackListCoverRow.java
- src/main/java/org/example/music/track/repository/TrackListRepository.java
- src/main/java/org/example/music/track/repository/TrackListRow.java
- src/main/java/org/example/music/track/service/TrackListParameters.java
- src/main/java/org/example/music/track/service/TrackListQueryService.java
- src/main/java/org/example/music/track/service/TrackListService.java
- src/main/java/org/example/music/track/service/TrackListSort.java
- src/main/java/org/example/music/track/web/TrackListErrorAdvice.java
- src/main/resources/db/migration/V8__add_track_list_indexes.sql
- src/test/java/org/example/music/track/TrackListIntegrationTest.java
- src/test/java/org/example/music/track/TrackListQueryIntegrationTest.java
- src/test/java/org/example/music/track/controller/TrackListControllerTest.java
- src/test/java/org/example/music/track/service/TrackListParametersTest.java
- src/test/java/org/example/music/track/service/TrackListQueryServiceTest.java
- src/test/java/org/example/music/track/service/TrackListServiceTest.java
- docs/reviews/2026-10-30-track-list-review.md

## 迁移与性能证据

新增：src/main/resources/db/migration/V8__add_track_list_indexes.sql。

索引：created_at、(status,title)、(status,artist_name)、(status,created_at)。保留既有索引和 V1—V7，不变更 ddl-auto。

真实 MySQL 12000 行、三状态各 4000，记录 V7 原查询与 V8 索引/派生表查询前后 EXPLAIN，并用 EXPLAIN ANALYZE 证明本页回表与排序行数。具体资料：docs/reviews/2026-10-30-track-list-query-plans.md；可重跑 TrackListQueryIntegrationTest 生成 build/reports/track-list-query-plans.md。

实现计划原提议 Criteria 内容查询；基于真实深分页执行计划调整为白名单原生派生表查询，count 保留 Criteria，不变更接口契约或查询次数上限。四个索引有存储、写入及部署建索引成本；没有使用 FORCE INDEX 或虚构恒定计数成本。

## API 文档、基线与依赖

- API：docs/api/track/list-tracks.md。
- 设计：docs/superpowers/specs/2026-10-30-track-list-design.md。
- 计划：docs/superpowers/plans/2026-10-30-track-list-plan.md。
- 基线：main @ 93685fb / working tree。
- 新依赖：无。配置/安全规则：未修改。代码尚未提交或推送。

## 实际测试

最终命令：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check
```

BUILD SUCCESSFUL（6m 12s）；通过 417 / 失败 0 / 跳过 0。统计来自最终 build/test-results/test/TEST-*.xml，使用 cleanTest 强制重跑。列表新增 89 项；必要 MySQL/Redis/MinIO 容器测试均实际执行。跳过项：无。

| 类 | 通过 | 失败 | 跳过 |
| --- | --- | --- | --- |
| org.example.music.storage.S3AudioStorageIntegrationTest | 5 | 0 | 0 |
| org.example.music.storage.SongStorageMigrationIntegrationTest | 2 | 0 | 0 |
| org.example.music.track.controller.TrackListControllerTest | 25 | 0 | 0 |
| org.example.music.track.MediaImportIntegrationTest | 1 | 0 | 0 |
| org.example.music.track.service.TrackListParametersTest | 24 | 0 | 0 |
| org.example.music.track.service.TrackListQueryServiceTest | 14 | 0 | 0 |
| org.example.music.track.service.TrackListServiceTest | 5 | 0 | 0 |
| org.example.music.track.TrackCoverIntegrationTest | 18 | 0 | 0 |
| org.example.music.track.TrackListIntegrationTest | 20 | 0 | 0 |
| org.example.music.track.TrackListQueryIntegrationTest | 1 | 0 | 0 |

覆盖：
- 参数边界、重复/未知/空值、默认值、溢出、page×size=10000 与超限。
- 真实 JWT、ADMIN 与 USER 登录、上传者本人无额外权限、Token 撤销、状态过滤后内容和计数。
- 四排序双方向、多页相同字段值与 ID 次排序、空页/越界页真实计数、Long.MAX_VALUE 页数计算。
- size=1/20/100 业务 Service 恒三条查询，HTTP 恒四条（其中用户认证一条）；无封面批次少一条。SQL Inspector 确认无 ID3，AudioStorage spy 确认无调用，不以 mock 替代 MySQL 查询。
- 同页独立封面、共享封面去重和各自 URL、元信息异常、隐藏状态/其他页损坏不影响当前页，重复读取 version=0、资产计数不变。
- 独立数据库连接在内容查询与 count 之间提交状态更新，本请求仍按 RR 快照返回 40，后续请求返回 39。
- V7→V8 迁移保留原有 12000 行及 4000 条已发布数据，真实执行计划与实际行数分析。

首个参数测试因实现类型缺失失败，实施后通过；之后专项测试与最终全量检查均通过。构建结束时旧 Spring 上下文连接池可能输出已关闭连接告警，最终 XML 无失败或跳过；未在本任务修改无关测试生命周期。

运行 git diff --check；生产新增 List 代码扫描无 TODO/FIXME/readAllBytes/System.out/printStackTrace。没有新增上传、播放或搜索接口。

## curl 示例

POSIX shell；ACCESS_TOKEN 来自已有登录流程，草稿筛选需要 ADMIN 登录角色：

```sh
curl --get 'http://localhost:8080/api/v1/tracks' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'page=0' --data-urlencode 'size=20' --data-urlencode 'sort=id,desc'

curl --get 'http://localhost:8080/api/v1/tracks' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --data-urlencode 'status=DRAFT' --data-urlencode 'sort=title,asc'
```

## 默认项与限制

- 未引入关键词、游标、缓存、专用限流或独立管理列表；T05 后续仅为额外管理字段/筛选另行设计。
- 精确总数仍需统计全部匹配记录；深分页仍扫描跳过的索引项，跨请求并发修改仍可移动页内容。
- 图片对象存在性不在列表验证；资产元信息有效但对象缺失仍返回 URL。
- JWT 解码失败保持既有 401/WWW-Authenticate 例外；认证状态仍读 MySQL，O01/O06/O07/O08 未顺手处理。
- 当前为可交 Claude 独立重跑的首轮代码审核材料，不代表代码已 accepted。

## 首轮代码审核结论

Verdict：accepted；无阻断项，可提交推送。

Claude 独立执行 `MINIO_INTEGRATION_TESTS=true ./gradlew cleanTest check`，BUILD SUCCESSFUL（6m 8s），417 通过 / 0 失败 / 0 跳过，逐类结果与本请求一致；MySQL、Redis、MinIO 真实执行。

两个非阻断项留待后续：尾斜杠错误响应的路由缓存匹配；若 T04 复用排序 SQL 片段，再评估提取受控构造器。本次不更改已审核业务代码。用户已授权推送，T02 登记已完成，提交号见任务书。

上文未提交/待审核描述为首轮请求时的历史状态，以本节最终结论为准。工作区额外的 .gitignore 改动不属于审核范围，不纳入本次提交。
