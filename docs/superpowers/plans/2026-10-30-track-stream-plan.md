# 歌曲播放接口实现计划

> 执行方式：遵循项目 AGENTS.md，Codex 在本会话实现，Claude 只读独立审核。其他 Agent 不编写业务代码。用复选框跟踪任务。

**目标：** 实现 `GET /api/v1/tracks/{trackId}/stream`，经 Bearer 鉴权代理原始 MP3/FLAC，支持单区间 Range、条件验证和有界资源释放。

**架构：** 短只读查询取得非 Entity 音频快照；事务外 HEAD 校验及条件/Range 决策；播放专用存储读取接口验证实际 GET 响应，返回可完成或取消的 SDK 无关流。同步 Servlet 写入固定缓冲，保留既有存储消费者行为。

**技术栈：** Java 21、Spring Boot 4.1.1、JPA/MySQL 8.4、Spring Security JWT、AWS S3 SDK BOM 2.25.60、JUnit 5、MockMvc、Testcontainers。实际以 build.gradle 为准。

**规格：** [歌曲播放设计](../specs/2026-10-30-track-stream-design.md)，设计审阅 accepted；基线 main @ b258d63。

## 全局约束

- 单接口 GET /api/v1/tracks/{trackId}/stream；不新增凭证、Cookie、转码、搜索、播放历史或独立 HEAD 业务接口。
- ADMIN 登录角色全部状态可试听；其他角色含上传者本人仅 PUBLISHED；每次请求重新鉴权，隐藏与不存在统一 404。
- 200 完整、206 单区间、304 无体、412 前置条件失败、416 带 bytes */N；未知 Range 单位、多区间回退完整 200。
- 先认证/可见性/资产/对象校验，再按 If-Match、If-Unmodified-Since、If-None-Match、If-Modified-Since、Range/If-Range 顺序处理。
- 200/206：实际 MIME/长度、Accept-Ranges、强 ETag、Last-Modified、private, no-cache、Vary: Authorization；206 带精确 Content-Range。
- 304 不开流；错误 no-store；大整数先比较/裁剪后转 long，不能溢出 500。
- 无需迁移，V1—V8 不改；不触碰用户 .gitignore 改动。
- 旧 AudioStorage.put/head/read/readRange/delete、封面/导入/删除的接口和调用预算不变。
- 固定缓冲、不完整读入内存或磁盘；取消长对象要 abort，不能排空剩余内容。
- API/部署/计划/提交说明用中文；最终 MINIO_INTEGRATION_TESTS=true 完整 check，必要容器 skipped 不算通过。

## 重点复核

1. 上游忽略 Range 回 200、错 Content-Range/长度、HEAD/GET 间变化：502 并取消流，不截取冒充区间；任务 3/5。
2. 304/412 优先于非法 Range，If-Range 失败使 Range 忽略：不能先解析 Range 抛 416；任务 1/4/5。
3. 大 end/后缀、重复字段及完整覆盖：正确裁剪/回退，不溢出，不返回错误状态；任务 1/4。
4. 取消/错误关闭触发 SDK drain，或连接预算误杀长传输：专用读客户端与可 abort 句柄，真实慢上游/取消测试；任务 3/5。
5. 响应已提交或隐式 HEAD：不能追加 JSON、泄漏流或为了 HEAD 下载整首歌曲；任务 4/5。

## 依赖与配置的精确清单

新增显式编译依赖：`implementation 'software.amazon.awssdk:apache-client'`，实际版本由既有 BOM 锁定为 **2.25.60**；不升级 BOM。用途是为播放专用同步 HTTP 客户端设置连接、池等待和 socket 空闲超时，不引入新存储供应商。

| 配置 | 默认 | 播放读取的用途 | 兼容行为 |
| --- | --- | --- | --- |
| 既有 app.storage.connect-timeout / S3_CONNECT_TIMEOUT | 2s | 新读客户端连接建立、连接池获取等待 | 不改旧客户端 API 预算 |
| 既有 app.storage.read-timeout / S3_READ_TIMEOUT | 30s | 新读客户端 socket 读取空闲时间 | 不当作音频总传输时限；旧配置含义保持 |
| 新 app.storage.stream.max-connections / S3_STREAM_MAX_CONNECTIONS | 50 | 读取专用连接池上限，正整数 | 旧客户端连接池不改 |

新读客户端只作一次 GET 尝试，使用 `RetryPolicy.none()`，不设置将整个音频限制为 connectTimeout/readTimeout 的 SDK API 总/单次预算。失败由客户端重新请求或续传；不能在已开始输出后自动重试。

保留原 `s3Client` 为默认注入客户端（必要时 @Primary），新增具名 `streamReadS3Client`，只由严格读取适配使用。HTTP 客户端通过 httpClientBuilder 交 SDK 管理，两个 S3Client 均由 Spring 关闭。

播放使用同步 Servlet、有界 Tomcat 线程/连接，不引入异步执行器；实际容器连接/写入等待不得为无限值，测试和审核材料记录生效值。部署文档要求有限并发与读取/发送空闲超时，不擅改上传业务总预算。

## 任务 1：Range 与条件决策

**新增文件：**
- `src/main/java/org/example/music/storage/domain/AudioByteRange.java`。
- `src/main/java/org/example/music/track/service/TrackStreamRangeParser.java`、`TrackStreamConditions.java`。
- `src/main/java/org/example/music/track/exception/TrackRangeNotSatisfiableException.java`、`TrackPreconditionFailedException.java`。
- `src/test/java/org/example/music/track/service/TrackStreamRangeParserTest.java`、`TrackStreamConditionsTest.java`。

**接口：**
- `AudioByteRange(long start, long end)`：已规范化、闭区间，构造时约束非负且 end>=start；`long length()` 使用有效范围安全计算。
- `AudioByteRange parse(List<String> ranges, long totalSize)`：返回 null 表示完整 200；合法单区间返回范围；语法/可满足性错误抛含 totalSize 的 TrackRangeNotSatisfiableException。
- `int evaluate(HttpHeaders headers, String etag, Instant lastModified)`：前置条件失败抛 TrackPreconditionFailedException，匹配返回 304，否则 200。
- `boolean allowsRange(List<String> ifRange, String etag, Instant lastModified, boolean dateStrong)`：缺少 If-Range 为 true；强 ETag 或可靠精确日期才匹配；弱/重复/坏值 false。

- [x] 先写 N=10 的范围测试：0-0、0-9、0-999、5-、-3、-999 对应 206 范围；10-、9-2、-0、bytes=abc 对应 416；多区间/未知单位/重复 Range 返回 null。
- [x] 大整数断言：超 long start→416；超 long end→末尾；超 long 后缀→完整范围但仍 206；不负溢出。
- [x] 条件矩阵：If-Match 强比较与 *、If-None-Match 弱比较/列表/*、日期秒精度、无效日期、优先级；重复 If-Range false、强/弱标签与可靠/不可靠日期的区别。
- [x] 执行 `.\gradlew.bat test --tests "*TrackStreamRangeParserTest" --tests "*TrackStreamConditionsTest"`，确认缺实现或行为失败后实现。
- [x] 用 BigInteger 比较/裁剪数字后再转 long；条件处理保留多行头，日期走框架 HTTP-date 解析；不修改已有 TrackCoverConditions。
- [x] 重跑上述测试通过。HTTP 层必须先 evaluate，再 allowsRange，再 parse，304/412 不解析 Range。

## 任务 2：授权与音频快照准备

**新增文件：**
- `src/main/java/org/example/music/track/service/TrackStreamSnapshot.java`、`PreparedTrackStream.java`。
- `src/main/java/org/example/music/track/service/TrackStreamQueryService.java`、`TrackStreamService.java`。
- `src/main/java/org/example/music/track/exception/TrackAudioStorageInconsistentException.java`。
- `src/test/java/org/example/music/track/service/TrackStreamQueryServiceTest.java`、`TrackStreamServiceTest.java`。

**接口：**
- `TrackStreamSnapshot load(Long trackId, AuthenticatedUserPrincipal actor)`：字段 `Long trackId, AudioObjectMetadata metadata, Instant createdAt`，内部快照，不作为 HTTP DTO。
- `PreparedTrackStream prepare(Long trackId, AuthenticatedUserPrincipal actor, Instant now)`；结果 record 字段为 `TrackStreamSnapshot snapshot, String etag, Instant lastModified, boolean dateStrong`。now 每请求固定取一次，测试可传固定时刻。
- QueryService 短 `@Transactional(readOnly=true)`；外层 Service 捕获 DataAccessException/TransactionException→TrackStoreUnavailableException；HEAD 在事务外调用既有 AudioStorage。

- [x] 先写完整状态×角色矩阵；上传者无额外特权、管理员 USER 登录降权；不可见/不存在同异常，资产与存储不被调用。
- [x] 本地 AUDIO/kind/MIME/大小/SHA/定位/时间错误为 500；正常 MIME 为 audio/mpeg、audio/flac。错误不回显存储字段。
- [x] HEAD 比较大小/MIME/SHA，远端非法摘要的 IllegalArgumentException 仅在该调用边界转 502；对象缺失转新的 TrackNotFoundException(trackId)，warning 无 bucket/key/cause。
- [x] Last-Modified 由 createdAt UTC 秒精度得到；未来时间钳制到 now 并置 dateStrong=false。当前不可替换的音频引用且时间未钳制时 dateStrong=true，未来替换能力须重新审视。
- [x] 执行 `.\gradlew.bat test --tests "*TrackStreamQueryServiceTest" --tests "*TrackStreamServiceTest"`，红灯后实现，再重跑通过。

## 任务 3：严格存储读取与专用客户端

**新增文件：**
- `src/main/java/org/example/music/storage/domain/AudioStreamStorage.java`、`VerifiedAudioStream.java`。
- `src/main/java/org/example/music/storage/S3AudioStreamStorage.java`。
- `src/main/java/org/example/music/storage/exception/AudioStreamInconsistentException.java`。
- `src/main/java/org/example/music/storage/config/StreamReadProperties.java`。
- `src/test/java/org/example/music/storage/S3AudioStreamStorageTest.java`、`S3AudioStreamStorageHttpTest.java`、`config/StreamReadConfigTest.java`。

**最小修改：** build.gradle、StorageConfig、application.properties、.env.example。StorageProperties 的既有字段/构造与 S3AudioStorage 原方法不改。

**接口：**
- `VerifiedAudioStream open(AudioObjectMetadata expected, AudioByteRange range)`；range=null 发送全量 GET，非 null 发送精确 Range。业务层不接触 SDK 类型。
- VerifiedAudioStream 是 SDK 无关接口：`InputStream content()`、`long contentLength()`、`void completed()`、`void close() throws IOException`。completed 仅在全部预期字节成功输出后调用；未完成 close 必须 abort，完成后正常 close。
- 专用存储不实现旧 AudioStorage，避免自动注入歧义；旧 S3Client 保持主客户端，专用客户端用 @Qualifier 注入。

- [x] SDK 精确测试先覆盖完整 GET=200、范围 GET=206；校验实际 contentType/contentLength/metadata.sha256，206 校验实际 Content-Range 起止/总长及预期长度。
- [x] 上游忽略范围回 200、错误范围/长度/MIME/SHA、缺元信息均抛 AudioStreamInconsistentException 并 abort；不得从完整内容截取所需片段冒充成功。
- [x] HEAD 后删除→AudioObjectNotFoundException，播放 Service/writer 边界转脱敏 404；合法范围对应的上游 416→存储不一致 502；网络/服务故障→503，不将这些映射成客户端 Range 416。
- [x] verified 句柄测试：正常 complete+close、未完成 close、校验失败、首读/中途读失败、输出失败、close 失败；确保取消不 drain 未消费的长对象。
- [x] 执行 `.\gradlew.bat test --tests "*S3AudioStreamStorageTest" --tests "*StreamReadConfigTest"`，红灯后实现新适配和本计划精确依赖/配置，再重跑通过。
- [x] JDK HttpServer 受控上游测试走真实 Apache/S3Client：持续数据传输 1 秒、连接预算 100ms、socket 空闲 300ms，应成功；发首块后空闲超过 300ms 应超时并释放连接。断言用有界宽容窗口，不锁死毫秒级耗时。
- [x] 同类受控 HTTP 测试上游实际坏状态/错头、连接中断、长对象取消后的连接释放；执行 `.\gradlew.bat test --tests "*S3AudioStreamStorageHttpTest"` 通过。模拟上游不代替真实 MinIO。
- [x] 验证旧默认 S3Client 的预算与使用方不变，新连接池有限、Spring 关闭新客户端，不扩大凭据或 bucket 可见范围。

## 任务 4：HTTP 响应、错误与流生命周期

**新增文件：**
- `src/main/java/org/example/music/track/controller/TrackStreamController.java`。
- `src/main/java/org/example/music/track/web/TrackStreamResponseWriter.java`、`TrackStreamErrorAdvice.java`。
- `src/test/java/org/example/music/track/controller/TrackStreamControllerTest.java`、`web/TrackStreamResponseWriterTest.java`。

**最小修改：** GlobalExceptionHandler 增加 TrackPreconditionFailedException→412 PRECONDITION_FAILED、TrackRangeNotSatisfiableException→416 RANGE_NOT_SATISFIABLE（含 bytes */N）、TrackAudioStorageInconsistentException→502 AUDIO_STORAGE_INCONSISTENT。不更改其他已登记错误码/文案。

**接口：**
- Controller `void stream(Long trackId, HttpHeaders headers, AuthenticatedUserPrincipal actor, HttpServletRequest request, HttpServletResponse response)`。
- writer `void write(PreparedTrackStream prepared, AudioByteRange range, HttpServletResponse response) throws IOException`：null 范围对应 200，否则 206。
- writer 使用 AudioStreamStorage；统一在本操作边界转换对象缺失/严格读取异常，不能让底层带位置异常进入原全局处理器。

- [x] 先写 WebMvcTest：正整数 ID、401、三状态权限相关错误、200/206 精确字节与长度、ETag/Last-Modified/Vary/缓存、304 无体、412、416 的 bytes */N 和 no-store、500/502/503。
- [x] 对同请求组合固定优先级：匹配 If-None-Match + 错 Range→304；失败 If-Match + 错 Range→412；If-Range 不匹配 + 错 Range→完整 200。对象缺失/不一致/不可用优先于 304/416。
- [x] 写 writer 单测覆盖 8192 固定缓冲、提前 EOF、写入失败、completed 调用时机、异常 close/abort、提交前清理全部音频头但保留 traceId、提交后抛脱敏 IOException 且不追加 JSON。
- [x] 执行 `.\gradlew.bat test --tests "*TrackStreamControllerTest" --tests "*TrackStreamResponseWriterTest"`，红灯后实现同步 Servlet 输出与本 Controller 局部 IOException 处理；不创建无界线程。
- [x] 304 只设置缓存验证头，不开流、不填长度 0；隐式 HEAD 完成鉴权/HEAD/条件检查后输出完整资源元信息，忽略 Range，不调用音频 open，不创建独立 HEAD Mapping。
- [x] TrackStreamErrorAdvice 仅匹配本 stream 路由，错误 no-store；清理成功 Content-Range 后，416 专用头仍保留/重新设置，不能被 Advice 无差别删除。
- [x] 完整 OpenAPI 覆盖媒体二进制、200/206/304、条件头与范围头、400/401/404/412/416/500/502/503 和响应头，注明 Bearer 客户端限制；重跑本任务测试通过。

## 任务 5：真实容器、HTTP、文档与审核交付

**新增文件：**
- `src/test/java/org/example/music/track/TrackStreamIntegrationTest.java`。
- `src/test/java/org/example/music/track/TrackStreamHttpIntegrationTest.java`。
- `docs/api/track/stream-track.md`、`docs/reviews/2026-10-30-track-stream-review.md`。

**最小修改：** docs/deployment/song-storage.md、docs/tasks/backend-roadmap.md；设计/计划可补实际执行及审核状态，不能将设计 accepted 写成代码 accepted。

- [x] 使用真实 MySQL 8.4、Redis 7.4、固定现有摘要 MinIO；有效 MP3/FLAC 可复用 AudioTestFixtures。通过 JwtTokenService 签发 Token，覆盖角色矩阵、USER 降权、本人无特权、版本撤销；每次 Range/条件请求重新验证。
- [x] 实际上传音频，验证完整 200、各单区间 206 的字节内容和头、末尾裁剪、巨大数字、多区间与未知单位回退、304/412/416 不开内容流；业务授权/错误不能泄漏隐藏音频长度。
- [x] HEAD 与严格 GET 时断言无数据库活动事务；重复读取版本/资产计数不变。损坏元信息、对象删除、暂停 MinIO 故障及恢复均真实执行。
- [x] 随机端口实际 HTTP：超过 Servlet 缓冲后注入读故障，收到原音频前缀且短于声明长度、无追加 JSON、源流关闭；客户端断开后有界等待 abort，不排空全部长对象。检查隐式 HEAD 零内容读取。
- [x] 受控实际上游验证超时与坏响应，用真实 MinIO 验证存储协议，两者均保留；不把 SDK mock 当成全部存储集成证据。
- [x] 核对生产容器有限线程/连接及非无限连接/写入等待，测试中记录实际生效值；部署文档列明配置核验方式、流路径关闭 buffering/gzip、代理读取/发送空闲超时和客户端中断传播。
- [x] API 文档必须明确：非法 bytes 单区间语法选定返回 416 而非忽略；404 既可能是 ID/权限，也可能是对象丢失，客户端可提示暂不可播放；说明全部条件优先级、头部、错误、缓存撤回限制和可执行 curl。
- [x] 记录新增精确依赖 `software.amazon.awssdk:apache-client:2.25.60` 和三个配置项/默认值/兼容行为；迁移写“无”，既有 V1—V8 不改。
- [x] T03 标待审核；原生浏览器媒体授权另登记待设计，不新增其接口，不提前标完成。
- [x] 执行 `$env:MINIO_INTEGRATION_TESTS="true"; .\gradlew.bat cleanTest check`，预期 BUILD SUCCESSFUL，真实 MySQL/Redis/MinIO 零必要跳过；包含封面/导入/删除等全部既有回归。
- [x] 从最终 XML 汇总通过/失败/跳过及类名/原因；运行 git diff --check，列全含 untracked 文件、基线、实际依赖/配置、API/部署文档和限制，交 Claude 第 1 轮代码审核。
- [x] 保留用户 .gitignore 改动；代码 accepted 且用户要求时再提交推送，不自行推进下一接口。

## 计划自检与审阅门槛

规格请求/协议由任务 1/4 覆盖；权限、快照与元信息由任务 2 覆盖；严格读取、取消、超时由任务 3/5 覆盖；错误、HEAD、真实 HTTP 与文档由任务 4/5 覆盖。五项重点复核都有所属测试，新增类型及方法在相邻任务中使用同名同型接口。

依赖、配置、默认值、兼容边界和三项异常映射已明确，无迁移；未更改 .gitignore 或既有存储消费者。计划已获用户确认并执行；执行方式遵循项目约定。代码尚待 Claude 第 1 轮独立审核，未提交或推送。

## 实际执行记录（2026-10-30）

- 完成单接口 GET /api/v1/tracks/{trackId}/stream；无迁移，V1—V8 未修改，用户既有 .gitignore 改动保留。
- 实际 HTTP 集成测试合入 TrackStreamIntegrationTest（随机端口 + MockMvc + 真实 MySQL/Redis/MinIO），未创建重复的 TrackStreamHttpIntegrationTest 上下文。
- 受控上游持续传输测试使用连接建立 100ms、池等待/读取空闲 600ms，连续传输约 1 秒成功；空闲 1500ms 失败。阈值放宽以避免环境调度抖动，不改变生产默认值。取消测试对象 1MiB，验证 abort 快速释放且池可复用。
- 真实 HTTP 覆盖响应提交后读失败不追加 JSON、客户端 RST 取消、4.27MB MP3 客户端停止读取触发写入超时释放。测试 Tomcat connection-timeout=1s 并确认实际协议处理器连接/线程上限为正值；生产按部署说明核验实际配置。
- 新显式依赖 apache-client:2.25.60；专用池默认 50、连接/池等待默认 2s、读取空闲默认 30s；旧客户端仍为主注入且旧预算不变，专用客户端无 API 总/尝试预算及自动重试。
- 最终强制回归：MINIO_INTEGRATION_TESTS=true .\gradlew.bat cleanTest check；BUILD SUCCESSFUL，6m 51s，519 通过 / 0 失败 / 0 跳过；新增 102 项。XML 共 71 类。之后 check 复核为 UP-TO-DATE，仅确认构建状态，不重复计作执行测试。
- API、部署、T03 待审核、O12 浏览器媒体授权任务及首轮审核请求已补齐；未实现下一接口。代码 accepted 并获用户提交授权后再提交推送。

## 代码审核完成

第 1 轮 Claude 代码审核 accepted，无阻断项；独立 cleanTest check：519/0/0，6m 54s。三条非阻断建议登记 O13，用户已授权提交推送，本次仅补完成记录，不改已审核业务代码。
