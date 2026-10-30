# 歌曲封面读取接口实现计划

> 执行方式：遵循本项目 AGENTS.md，由 Codex 在当前会话逐项实现，Claude 只读独立审核。使用复选框跟踪步骤；不安排其他 Agent 编写代码。

**目标：** 实现 `GET /api/v1/tracks/{trackId}/cover`，安全流式返回封面并支持每次鉴权的 ETag 条件验证。

**架构：** 短只读事务取得歌曲与封面资产快照；事务外校验存储元信息，再处理条件请求或打开并传输图片。Controller 负责 HTTP 契约，Service 负责权限与存储协调，专用响应写入组件负责固定缓冲传输和资源生命周期。

**技术栈：** Java 21、Spring Boot 4.1.1、Spring MVC/Security、JPA、MySQL 8.4、AWS S3 SDK、MinIO、JUnit 5、MockMvc、Testcontainers；以 build.gradle 实际配置为准。

**规格：** [已获 accepted 的封面设计](../specs/2026-10-30-track-cover-design.md)。基线 main @ 4e7baa0。

## 全局约束

- 只实现一个 HTTP Method + Path，不新增依赖、迁移或匿名路由。
- 需要登录；ADMIN 登录角色可访问全部状态，其他角色含上传者本人仅 PUBLISHED。
- 不存在与不可见同为 404 TRACK_NOT_FOUND；先检查权限再访问资产与存储。
- 成功 200/304：ETag、Cache-Control: private, no-cache、合并 Vary: Authorization。错误 no-store。
- 只支持 image/jpeg、image/png、image/webp。ETag 来自数据库与对象一致性校验通过后的摘要。
- 不支持客户端 Range：普通响应完整 200，无 Content-Range，不声明字节范围；条件匹配仍为 304。
- 304 不打开内容流；网络 IO 不占数据库事务；不完整缓存图片到内存或磁盘。
- 不修改详情 Service 的一致性异常（O08）、既有存储错误文案（O07）或全局认证行为（O06）。
- API、计划、提交说明使用中文；完整 check 必须设置 MINIO_INTEGRATION_TESTS=true。

## 重点复核

1. HEAD 成功、GET 前对象被删除或替换：重新检查读结果，返回脱敏 404/502，已打开的流必须关闭。
2. 匹配 ETag 与对象缺失、MinIO 故障同时出现：优先返回错误，不能用 304 掩盖故障。
3. 图片头已设置但响应尚未提交时失败：清除图片长度、类型和 ETag，返回 no-store ProblemDetail。
4. 提交后断流：不能拼接 JSON、重试或伪装完整图片；通过真实 HTTP 检查截断与资源关闭。
5. 多标签、弱标签、通配符、损坏标签与 Range 同时出现：匹配规则明确，权限不可被条件头绕过。

以上五项分别纳入任务 2、3 的测试，不仅进行人工代码检查。

## 任务 1：数据库读取与权限快照

**文件：**
- 新增 `src/main/java/org/example/music/track/service/TrackCoverQueryService.java`。
- 新增 `src/main/java/org/example/music/track/service/TrackCoverSnapshot.java`。
- 新增 `src/main/java/org/example/music/track/exception/TrackCoverNotFoundException.java`。
- 新增 `src/test/java/org/example/music/track/service/TrackCoverQueryServiceTest.java`。

**接口：** `TrackCoverSnapshot load(Long trackId, AuthenticatedUserPrincipal actor)`；快照字段为 `Long trackId, String bucket, String objectKey, String contentType, long sizeBytes, String sha256`，仅服务端内部使用。快照不是 HTTP DTO，不得序列化返回。

- [ ] 先写参数化测试 `should_apply_role_and_status_visibility`：所有 TrackStatus × ADMIN/USER；管理员 USER 登录身份按 USER，上传者本人无额外特权。隐藏状态时断言资产 Repository 无调用。
- [ ] 写测试 `should_distinguish_missing_cover_from_inconsistent_asset`：空引用抛封面缺失异常；悬空引用、非 COVER、非法本地 MIME/size/SHA 或存储定位抛内部一致性异常；不返回 Entity。
- [ ] 执行 `.\gradlew.bat test --tests "*TrackCoverQueryServiceTest"`，确认测试因尚未实现的类型/行为失败。
- [ ] 实现 load，采用独立 Bean 上的 `@Transactional(readOnly = true)`；仅数据库访问。使用现有 TrackRepository/MediaAssetRepository，范围有限的 findById；统一隐藏与不存在异常。
- [ ] 在后续编排调用方将数据库访问/事务创建故障转换为 TrackStoreUnavailableException；本任务测试普通 DataAccessException，真实事务故障在集成验证覆盖。
- [ ] 重跑上述测试，通过后记录结果；不在此阶段宣称整个接口完成。

## 任务 2：存储校验、条件请求与异常边界

**文件：**
- 新增 `src/main/java/org/example/music/track/service/TrackCoverService.java`。
- 新增 `src/main/java/org/example/music/track/service/PreparedTrackCover.java`。
- 新增 `src/main/java/org/example/music/track/service/TrackCoverConditions.java`。
- 新增 `src/main/java/org/example/music/track/exception/TrackCoverStorageInconsistentException.java`。
- 新增 `src/test/java/org/example/music/track/service/TrackCoverServiceTest.java` 与 `TrackCoverConditionsTest.java`。

**接口：**
- `PreparedTrackCover prepare(Long trackId, AuthenticatedUserPrincipal actor)`：先调用 load，事务返回后 HEAD 并校验；结果含内部 snapshot 与 `String etag`，不含已打开流。
- `AudioObjectRange open(PreparedTrackCover cover)`：打开实际对象，检查返回 contentType、contentLength、totalSize、sha256 与已验证快照一致；异常时关闭已开流。
- `boolean matches(String ifNoneMatch, String etag)`：GET 弱比较，支持标签列表与通配符；无效标签不能触发匹配。

- [ ] 先写 `should_validate_storage_before_conditional_match` 所需行为测试，以及 MIME、长度、摘要不一致、无效存储摘要、对象缺失、SDK 故障的异常断言。
- [ ] 写 `should_close_opened_stream_when_metadata_changed` 与 `should_map_object_deleted_between_head_and_get`；mock 仅用于精确注入这些竞态，不替代真实 MinIO 测试。
- [ ] 条件测试使用固定标签 `"abc"`，断言 `W/"abc"`、`"other", "abc"`、`*` 匹配，空头、不匹配与损坏标签不匹配；没有资源时不允许调用匹配结果提前返回。
- [ ] 执行 `.\gradlew.bat test --tests "*TrackCoverServiceTest" --tests "*TrackCoverConditionsTest"`，确认失败，再实现上述接口。
- [ ] 复用 AudioStorage.head/read；仅在该存储调用边界捕获非法外部摘要导致的 IllegalArgumentException，转换 502，不捕获整个业务方法中的编程异常。
- [ ] 将 AudioObjectNotFoundException 转为 TrackCoverNotFoundException。资产存在但对象丢失时日志仅记录 trackId、traceId 和事件类别；不打印含 bucket/key 的异常 cause。
- [ ] read() 会再次 HEAD；检查其返回元信息，错误时关闭资源。外部 200 图片流与内部 S3 Range 请求分开处理。
- [ ] 重跑测试通过；确认 prepare/HEAD/GET 均不处于数据库事务中。新增精确测试断言 TransactionSynchronizationManager 在存储调用时无活动事务。

## 任务 3：HTTP 响应、错误映射与流生命周期

**文件：**
- 新增 `src/main/java/org/example/music/track/controller/TrackCoverController.java`。
- 新增 `src/main/java/org/example/music/track/web/TrackCoverResponseWriter.java`。
- 新增 `src/main/java/org/example/music/track/web/TrackCoverErrorAdvice.java`（只作用于本 Controller 的错误 no-store 和未提交响应清理）。
- 最小修改 `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`，增加封面 404 与 502 映射。
- 新增 `src/test/java/org/example/music/track/controller/TrackCoverControllerTest.java` 与 `src/test/java/org/example/music/track/web/TrackCoverResponseWriterTest.java`。

**接口：** Controller `void getCover(Long trackId, List<String> ifNoneMatch, AuthenticatedUserPrincipal actor, HttpServletResponse response)`；writer `void write(PreparedTrackCover cover, HttpServletResponse response) throws IOException`。Controller 完成条件判断后，304 分支不调用 writer，200 分支由 writer 调用 service.open 并接管流。

- [ ] 先写 WebMvcTest：正整数 ID、0/-1/非整数/溢出、小数、未认证、200 三种 MIME、完整字节、长度、ETag、304 无体、错误 ProblemDetail 和 no-store。
- [ ] 断言多标签/弱标签/*/无效标签、Range 与条件组合，200 无 Content-Range、不声明 Accept-Ranges: bytes；Vary 包含 Authorization 且保留既有值。
- [ ] 写 writer 测试：正常关闭、打开失败、元信息不符关闭、首次读取失败、写入失败、提前 EOF、客户端断开和 close 失败；区分 isCommitted=false/true，并断言不关闭 Servlet 输出流。
- [ ] 执行 `.\gradlew.bat test --tests "*TrackCoverControllerTest" --tests "*TrackCoverResponseWriterTest"`，确认失败，再实现同步 Servlet 图片传输，避免引入异步队列和上下文传播问题。
- [ ] 使用 8192 字节固定缓冲区，try-with-resources 关闭对象流；只读到预期长度并检测提前 EOF。成功打开并验证对象后、输出字节前设置图片头；失败未提交时清除缓冲及图片头，让统一异常映射输出干净 ProblemDetail。
- [ ] 提交后读取/写入失败只终止传输、关闭源流并记录脱敏 traceId；不得走通用 Exception 处理器追加 JSON。通过本 Controller 的局部异常处理将提交后 IOException 交回容器处理；在真实 HTTP 测试证明连接或响应体截断，不能仅假设抛异常就正确。
- [ ] 封面错误 Advice 只处理本路由的缓存与未提交头清理，保留全局错误体构造；不改变其他 Controller 的缓存或错误码。
- [ ] 完整 OpenAPI：@Tag、@Operation、路径参数、200 图片二进制三种媒体类型、304、400/401/404/500/502/503 及相关响应头。
- [ ] 重跑上述测试，通过后继续真实 HTTP 验证；必要的本路由细节调整必须保持已确认契约。

## 任务 4：真实容器、真实 HTTP 与文档交付

**文件：**
- 新增 `src/test/java/org/example/music/track/TrackCoverIntegrationTest.java`。
- 真实 HTTP 断流和客户端断开测试合并在 TrackCoverIntegrationTest，复用相同真实容器和随机端口，避免再启动一组容器。
- 新增 `src/test/resources/fixtures/cover/` 下小尺寸有效 JPEG、PNG、WebP 测试图片，来源为可复现生成或现有合法测试资源。
- 新增 `docs/api/track/get-track-cover.md`。
- 最小修改 `docs/api/track/get-track-detail.md`、`docs/tasks/backend-roadmap.md`。
- 新增 `docs/reviews/2026-10-30-track-cover-review.md`。

**接口/环境：** 随机端口 SpringBootTest；真实 MySQL 8.4、Redis 7.4、沿用现有固定镜像摘要的 MinIO；DynamicPropertySource 注入连接。使用现有 JwtTokenService 签发真实 Token，实际 HTTP 客户端访问本路由。

- [ ] 编写并运行三格式图片 200、权限矩阵、USER 登录降权、token version 撤销、条件 304、空封面、对象删除、元信息不一致和重复请求无写入集成测试。304 路径通过存储 spy 仅计数，断言没有 read 调用，底层 HEAD 仍访问真实 MinIO。
- [ ] MinIO 故障注入使用专用测试对象/客户端或容器暂停，保证在 finally 恢复，不影响其他用例。匹配 ETag 时仍应返回 503；元信息不一致返回 502；对象丢失返回 404。
- [ ] 真实 HTTP 测试使用可控的输入/输出故障注入：至少一条完整真实 MinIO 读取，另用受控包装流在已输出超过 Servlet 缓冲区后抛 IOException，断言客户端读到截断/IO 失败、无拼接 JSON、包装流关闭。故障包装不替代任务中的真实存储故障验证。
- [ ] 对客户端断开测试先读取首块再关闭 HTTP 响应，使用有界等待验证服务器源流释放；设置测试超时，避免线程/连接遗留。
- [ ] API 文档写明权限矩阵、无封面 404、元信息与内容摘要校验边界、200/304、忽略 Range、错误表、签名认证例外、Bearer 携带方式与下载/条件请求 curl。只移除详情文档“尚未实现”限制，不改变详情契约。
- [ ] 将设计审阅三条非阻断意见落实到实现与测试；任务书 T01 先标待审核，代码审核 accepted 后才能标完成。
- [ ] 执行 `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check`，预期 BUILD SUCCESSFUL，必要 MySQL/Redis/MinIO 测试零跳过；从最终 XML 汇总通过/失败/跳过与具体跳过原因。
- [ ] 执行 `git diff --check`，核对全部含 untracked 的变更文件、敏感数据和迁移未改，写完整单接口审核请求，包含实际命令/数量、API 文档、基线及限制。交 Claude 第 1 轮代码审核，不将设计 accepted 当成代码 accepted。
- [ ] 经 accepted 且用户要求提交时，以中文提交信息提交；不自行推进下一接口或推送其他未审核功能。

## 计划自检

规格第 3—6 节对应任务 1—3，第 7 节资源/事务对应任务 1—4，第 8—10 节文档/测试/限制对应任务 4。重点复核的五类故障均落到测试步骤；所有引用的方法和结果类型在本计划中已定义。执行仍需以真实 HTTP 结果验证容器提交后异常行为，不能以 MockMvc 代替该完成条件。

## 实际执行结果

用户已确认计划，已完成本接口代码、文档和验证，Claude 首轮代码审核 accepted，独立重跑 328/0/0。真实 HTTP 测试与其余集成测试合并在 TrackCoverIntegrationTest，共用随机端口和真实容器。

实际采用同步 Servlet 流式传输及本 Controller 的局部 IOException 处理。未提交时重置响应并保留 traceId 等非图片头；提交后重新抛出脱敏 IO 错误交由容器结束传输，真实 HTTP 已验证无 JSON 拼接和源流释放。

ETag 首个测试执行了缺失类型导致的失败、实现后通过；其余测试随实现补齐。首次专项执行曾暴露两处 mock 重设方式问题及响应长度清理问题，均已修复；原始分步清单不代表每一项都严格按先红灯再编码的顺序执行。

最终命令：`$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check`；BUILD SUCCESSFUL，328 通过 / 0 失败 / 0 跳过。封面新增 83 项，含真实 JPEG/PNG/WebP、MinIO 暂停故障注入、真实 HTTP 断流与客户端断开。具体审核材料见 `docs/reviews/2026-10-30-track-cover-review.md`。

用户已授权提交推送；本次只更新完成记录，不处理非阻断优化或推进其他接口。
