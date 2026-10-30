# 歌曲详情接口代码审核请求（第 1 轮）

本次接口：GET /api/v1/tracks/{trackId}。

需求与权限：需要登录；ADMIN 登录角色可读取全部状态，其他角色含上传者本人只能读取 PUBLISHED。共享曲库不限制上传者归属；不可见和不存在使用同一 TRACK_NOT_FOUND 异常与 404 响应。可信主体由现有 JWT 与认证状态过滤器提供。

声称实现的行为：
- 返回标题、艺术家、专辑、毫秒时长、曲序、碟号、状态与封面元信息；缺失可选字段返回 null。
- 先验证状态可见性再查封面；封面引用缺失或类型错误返回 500 INTERNAL_ERROR。
- 正整数 Long 路径校验、DTO 与 Entity 分离、只读事务、Cache-Control: no-store。
- 只查询数据库，不读取 MinIO、不生成预签名 URL、不返回 bucket/对象键/原始 ID3/审计字段。
- 完整 OpenAPI 与中文 API 文档；明确上传者本人限制与具体错误码。

变更文件（相对基线，含 untracked 和本审核请求）：
- docs/superpowers/specs/2026-10-29-track-detail-design.md
- docs/api/track/get-track-detail.md
- docs/superpowers/plans/2026-10-30-track-detail-plan.md
- src/main/java/org/example/music/track/controller/TrackDetailController.java
- src/main/java/org/example/music/track/dto/TrackDetailResponse.java
- src/main/java/org/example/music/track/service/TrackDetailService.java
- src/test/java/org/example/music/track/TrackDetailIntegrationTest.java
- src/test/java/org/example/music/track/controller/TrackDetailControllerTest.java
- src/test/java/org/example/music/track/service/TrackDetailServiceTest.java
- docs/reviews/2026-10-30-track-detail-review.md

数据库迁移：无；未修改既有迁移。新增依赖：无。安全配置：无修改。

API 文档：docs/api/track/get-track-detail.md。
设计文档：docs/superpowers/specs/2026-10-29-track-detail-design.md（设计审阅 accepted）。
实现计划：docs/superpowers/plans/2026-10-30-track-detail-plan.md。

测试：
- 最终命令：`$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat check`
- 最终结果：BUILD SUCCESSFUL（4m 49s），通过 245 / 失败 0 / 跳过 0。
- 统计来自 build/test-results/test/TEST-*.xml；未将跳过视为通过。
- 详情与存储相关类：
- org.example.music.storage.S3AudioStorageIntegrationTest：通过 5 / 失败 0 / 跳过 0
- org.example.music.storage.SongStorageMigrationIntegrationTest：通过 2 / 失败 0 / 跳过 0
- org.example.music.track.controller.TrackDetailControllerTest：通过 10 / 失败 0 / 跳过 0
- org.example.music.track.MediaImportIntegrationTest：通过 1 / 失败 0 / 跳过 0
- org.example.music.track.service.TrackDetailServiceTest：通过 12 / 失败 0 / 跳过 0
- org.example.music.track.TrackDetailIntegrationTest：通过 8 / 失败 0 / 跳过 0
- 跳过项：无
- 真实 MySQL、Redis Testcontainers、签名 JWT 验证完整权限矩阵、管理员 USER 登录角色、版本撤销、无效 Token、封面元信息、重复读取无写入与不一致资产；WebMvcTest 和 Service 单测另行覆盖 HTTP 契约、参数边界、空值、最大长度与存储故障。
- 早期编译曾因 Container 通配导入冲突失败，已修复；其后两次完整 check 成功，最终次数量如上。
- 容器关闭后旧 Spring 测试上下文 Hikari 可能打印连接已关闭/拒绝日志；最终测试 XML 无失败或跳过。

基线：main @ e057c6b / working tree。当前代码未提交，待独立审核。

curl 示例（POSIX shell，ACCESS_TOKEN 来自已有登录流程）：
```sh
curl --request GET 'http://localhost:8080/api/v1/tracks/42' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Accept: application/json'
```

未决问题 / 默认项 / 限制：
- 封面 URL 为后续独立 GET /api/v1/tracks/{trackId}/cover 的契约地址；当前尚不能加载图片。
- 默认不新增缓存、限流、条件请求；无写冲突或幂等键需求。
- JWT 签名等解码失败仍沿用现有资源服务器 401 + WWW-Authenticate 行为，不保证业务 ProblemDetail；API 文档已注明。未在单接口中调整全项目认证响应。
- 现有认证状态服务每次查询 MySQL 验证 token version 与可用角色；本接口状态权限判断不增加额外用户查询。
- 尚未取得 Claude 代码审核结论，本文件为可直接交给 Claude 的首轮审核材料。
