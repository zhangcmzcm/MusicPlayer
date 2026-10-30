# 歌曲封面接口代码审核请求

本次接口：`GET /api/v1/tracks/{trackId}/cover`，第 1 轮代码审核；设计审核已 accepted，不计入代码审核轮次。

## 需求与权限

需要有效登录。以 ADMIN 角色登录可读取所有状态，其他已登录用户含上传者本人只能读取 PUBLISHED。管理员以 USER 角色登录时降权。共享曲库不按上传者归属限制管理员读取。

先验证歌曲存在和可见性，隐藏与不存在统一为 404 TRACK_NOT_FOUND。之后才读取封面资产和 MinIO，避免条件请求和封面错误泄漏隐藏歌曲。

## 声称实现的行为

- 正整数 Long 路径校验；返回真实 JPEG/PNG/WebP 字节流，固定 8192 字节缓冲，不完整读入生产堆内存或本地文件。
- 独立短只读事务获取非 Entity 快照；事务创建/查询故障映射 503。真实存储 HEAD/GET 调用处测试断言无活动数据库事务。
- HEAD 校验大小、MIME、摘要；不重新计算完整内容摘要。200 打开流时重新校验 read() 返回元信息，元信息不符关闭流。
- 一致性校验通过的摘要生成强 ETag；200/304 使用 private, no-cache 和合并的 Vary: Authorization。支持弱标签、列表、通配符，无效标签不命中；304 不打开内容流。
- Range 忽略，不声明客户端字节范围，条件命中仍 304。
- 无封面/对象缺失：404 COVER_NOT_FOUND；资产本地错误：500 INTERNAL_ERROR；存储元信息不一致：502 COVER_STORAGE_INCONSISTENT；存储不可用：503 MEDIA_STORAGE_UNAVAILABLE。
- 对象缺失在封面 Service 边界转换，缺失 warning 只含 trackId/traceId，不含 bucket/key 或底层 cause。远端摘要格式异常只在存储调用边界转换。
- 提交前失败清除图片头和缓冲，保留 traceId 等非图片头后交统一错误处理，错误 no-store；提交后断流关闭源流并交容器结束响应，不能追加 JSON 或重试图片流。
- 只增加必要全局 404/502 映射。封面错误缓存 Advice 按本路由生效，未改变其他业务路由错误契约。
- 更新 OpenAPI、中文封面 API、详情文档可用说明、任务书 T01 待审核状态及计划实际执行记录。

## 变更文件（含 untracked）

- docs/api/track/get-track-detail.md
- docs/superpowers/specs/2026-10-30-track-cover-design.md
- docs/tasks/backend-roadmap.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- docs/api/track/get-track-cover.md
- docs/superpowers/plans/2026-10-30-track-cover-plan.md
- src/main/java/org/example/music/track/controller/TrackCoverController.java
- src/main/java/org/example/music/track/exception/TrackCoverNotFoundException.java
- src/main/java/org/example/music/track/exception/TrackCoverStorageInconsistentException.java
- src/main/java/org/example/music/track/service/PreparedTrackCover.java
- src/main/java/org/example/music/track/service/TrackCoverConditions.java
- src/main/java/org/example/music/track/service/TrackCoverQueryService.java
- src/main/java/org/example/music/track/service/TrackCoverService.java
- src/main/java/org/example/music/track/service/TrackCoverSnapshot.java
- src/main/java/org/example/music/track/web/TrackCoverErrorAdvice.java
- src/main/java/org/example/music/track/web/TrackCoverResponseWriter.java
- src/test/java/org/example/music/track/TrackCoverIntegrationTest.java
- src/test/java/org/example/music/track/controller/TrackCoverControllerTest.java
- src/test/java/org/example/music/track/service/TrackCoverConditionsTest.java
- src/test/java/org/example/music/track/service/TrackCoverQueryServiceTest.java
- src/test/java/org/example/music/track/service/TrackCoverServiceTest.java
- src/test/java/org/example/music/track/web/TrackCoverResponseWriterTest.java
- src/test/resources/fixtures/cover/README.md
- src/test/resources/fixtures/cover/cover.jpg
- src/test/resources/fixtures/cover/cover.png
- src/test/resources/fixtures/cover/cover.webp
- src/test/resources/fixtures/cover/large.png
- docs/reviews/2026-10-30-track-cover-review.md

## 迁移、依赖与基线

- 数据库迁移：无，既有迁移未修改。
- 新依赖：无。
- 基线：main @ 4e7baa0 / working tree；代码尚未提交、未推送。
- 设计：docs/superpowers/specs/2026-10-30-track-cover-design.md；本轮仅补 accepted 状态及设计审核三条非阻断措辞/日志要求。
- 计划：docs/superpowers/plans/2026-10-30-track-cover-plan.md。
- API 文档：docs/api/track/get-track-cover.md。

## 实际验证

最终命令：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check
```

BUILD SUCCESSFUL（5m 17s），通过 328 / 失败 0 / 跳过 0。使用 cleanTest 强制重跑，统计自最终 build/test-results/test/TEST-*.xml。跳过项：无。

| 测试类 | 通过 | 失败 | 跳过 |
| --- | --- | --- | --- |
| org.example.music.storage.S3AudioStorageIntegrationTest | 5 | 0 | 0 |
| org.example.music.storage.SongStorageMigrationIntegrationTest | 2 | 0 | 0 |
| org.example.music.track.controller.TrackCoverControllerTest | 19 | 0 | 0 |
| org.example.music.track.MediaImportIntegrationTest | 1 | 0 | 0 |
| org.example.music.track.service.TrackCoverConditionsTest | 11 | 0 | 0 |
| org.example.music.track.service.TrackCoverQueryServiceTest | 12 | 0 | 0 |
| org.example.music.track.service.TrackCoverServiceTest | 17 | 0 | 0 |
| org.example.music.track.TrackCoverIntegrationTest | 18 | 0 | 0 |
| org.example.music.track.web.TrackCoverResponseWriterTest | 6 | 0 | 0 |

封面新增 83 项；MySQL 8.4、Redis 7.4、MinIO 为真实 Testcontainers。真实存储故障通过暂停 MinIO 注入，并在 finally 恢复；真实对象覆盖元信息不一致和对象删除。存储 spy 仅用于断言 IO 无数据库事务、304 不调用 read，以及对真实读取流添加可控断流包装。

随机端口的 HttpURLConnection 验证三类 HTTP 行为：正常实际 MinIO 图片字节；超过 Servlet 缓冲后注入 IOException，响应仍为已提交 200、收到内容严格为原图片前缀且长度不足、无 JSON 拼接、源流关闭；客户端首块读取后关闭，源流在有界等待内释放。

首个 ETag 测试红灯为缺失实现类型；首次专项 52 项曾有 3 项失败（两处 mock 重设、一个响应头清理），均修复；随后专项通过，最终全量强制执行结果如上，不将早期失败隐藏为从未发生。

另运行 git diff --check；生产封面代码扫描无 TODO/FIXME/readAllBytes/System.out/printStackTrace。测试图片由本项目生成，README 记录复现方法，测试运行不依赖 Python/Pillow。测试类中的 readAllBytes 仅用于小型测试资源和 HTTP 断言。

容器结束后旧 Spring 上下文的连接池可能输出连接已关闭告警，测试 XML 无失败或跳过；未在本单接口调整无关容器生命周期。

## curl 示例

POSIX shell，ACCESS_TOKEN 从已有登录流程获取，ETAG 取上次响应头完整标签值：

```sh
curl --request GET 'http://localhost:8080/api/v1/tracks/42/cover' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --dump-header cover.headers --output cover.bin

curl --request GET 'http://localhost:8080/api/v1/tracks/42/cover' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "If-None-Match: ${ETAG}"
```

## 默认项与限制

- 无新增专用限流、Redis 缓存、CDN、格式转换或时间条件请求。
- 304 仍发生认证、数据库和存储元信息读取，只节省内容传输；认证缓存 O01 未实现。
- 摘要比较依赖元信息与对象不可原地修改，不是每次重算内容摘要；已下载图片无法撤回。
- JWT 解码失败沿用现有资源服务器 401/WWW-Authenticate，可能没有业务 ProblemDetail；O06 不在本次处理。O07/O08 亦未顺手修改。
- 实际 HTTP 断流测试与普通集成测试合并于 TrackCoverIntegrationTest，复用同一随机端口/容器，未单独新增计划中曾提议的 StreamingHttpTest 类。
- 请 Claude 独立按 cleanTest check 重跑并输出代码审核结论；本文件为首轮材料，不代表代码已经 accepted。

## 首轮代码审核结论（2026-10-30）

Verdict：accepted；无阻断项，可提交推送。

Claude 独立执行 `MINIO_INTEGRATION_TESTS=true ./gradlew cleanTest check`，BUILD SUCCESSFUL（5m 13s），328 通过 / 0 失败 / 0 跳过。逐类结果与本请求一致；MySQL、Redis、MinIO 和真实 HTTP 用例均已执行。

非阻断项保留后续评估：发送前 EOF 当前按已文档化契约返回 503，将来可评估是否细分为 502；封面资产一致性专用异常继续由 O08 跟踪。本次不改已审核代码。用户已授权推送，任务书 T01 更新为已完成。

上文 working tree/未提交及待审核描述为首轮请求时的历史状态，以本节最终结论为准；提交号见任务书。
