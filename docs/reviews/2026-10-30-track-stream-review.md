# 歌曲播放接口首轮代码审核请求

本次接口：`GET /api/v1/tracks/{trackId}/stream`（T03），代码审核第 1 轮。设计审核 accepted，计划获用户确认；当前无代码审核结论。

## 需求与权限

有效 Bearer 请求头认证；ADMIN 登录角色允许全部状态，其他用户含上传者本人仅 PUBLISHED，管理员以 USER 登录降权。不可见/不存在统一 404 TRACK_NOT_FOUND。每次 Range/条件/隐式 HEAD 都走真实认证链，不使用 URL Token 或 Cookie；本次不改变认证状态校验与撤销策略。

## 声称实现的行为

- 流式代理原始 MP3/FLAC，完整 200、单区间 206，固定 8192 缓冲，不完整读入内存或落盘；无转码及播放记录写入。
- 单区间闭/开/后缀支持大整数安全裁剪；合法全覆盖仍 206，非法或不可满足单个 bytes 范围 416 + bytes */N；多区间、重复 Range、未知单位忽略。
- 先认证、可见性、本地资产和存储 HEAD，再 If-Match、缺省 If-Unmodified-Since、If-None-Match、缺省 If-Modified-Since；最后 If-Range 决定是否解析 Range。304/412 优先于坏 Range，If-Range 失败忽略坏 Range。
- ETag 为已验证摘要强标签，Last-Modified 使用资产创建时刻秒精度；未来时间钳制并使日期不能满足 If-Range；私有缓存每次重新验证，Vary 合并 Authorization。304 无体、不读内容，不设置长度 0。
- 隐式 HEAD 同样鉴权和校验，仅返回完整元信息/条件结果，忽略 Range，零内容读取；没有独立 HEAD Mapping。
- QueryService 短只读事务取得非 Entity 快照，最多两条 findById；HEAD/GET 均在事务外。DB/事务故障 503；坏本地资产 500；对象丢失 404 且脱敏 warning；远端元信息/实际响应不一致 502；存储不可用或提交前读失败 503。
- 新存储读取适配严格校验实际 GET 状态、Content-Range、长度、MIME、SHA 元信息及内容编码，错误范围/上游忽略 Range 不会伪装为 206。SDK 类型不进入 track 层。
- 未完成读取 close 使用 SDK abort，不 drain 剩余音频；全部预期字节写出后才 completed。提交前清缓冲/音频头并保留 traceId；提交后失败抛脱敏 IOException 中止传输，不追加 JSON。
- 错误 Advice 仅作用于 stream 路由的 ProblemDetail，no-store，416 保留范围总长；GlobalExceptionHandler 最小新增 412/416/502 映射。既有 JWT 解码失败 401/WWW-Authenticate 契约保持。
- 新增 API/部署文档，T03 待审核；O12 单独跟踪浏览器原生媒体授权，不扩大本次接口范围。

## 变更文件（含 untracked）

- .env.example
- build.gradle
- docs/api/track/stream-track.md
- docs/deployment/song-storage.md
- docs/reviews/2026-10-30-track-stream-review.md
- docs/superpowers/plans/2026-10-30-track-stream-plan.md
- docs/superpowers/specs/2026-10-30-track-stream-design.md
- docs/tasks/backend-roadmap.md
- src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java
- src/main/java/org/example/music/storage/S3AudioStreamStorage.java
- src/main/java/org/example/music/storage/config/StorageConfig.java
- src/main/java/org/example/music/storage/config/StreamReadProperties.java
- src/main/java/org/example/music/storage/domain/AudioByteRange.java
- src/main/java/org/example/music/storage/domain/AudioStreamStorage.java
- src/main/java/org/example/music/storage/domain/VerifiedAudioStream.java
- src/main/java/org/example/music/storage/exception/AudioStreamInconsistentException.java
- src/main/java/org/example/music/track/controller/TrackStreamController.java
- src/main/java/org/example/music/track/exception/TrackAudioStorageInconsistentException.java
- src/main/java/org/example/music/track/exception/TrackPreconditionFailedException.java
- src/main/java/org/example/music/track/exception/TrackRangeNotSatisfiableException.java
- src/main/java/org/example/music/track/service/PreparedTrackStream.java
- src/main/java/org/example/music/track/service/TrackStreamConditions.java
- src/main/java/org/example/music/track/service/TrackStreamQueryService.java
- src/main/java/org/example/music/track/service/TrackStreamRangeParser.java
- src/main/java/org/example/music/track/service/TrackStreamService.java
- src/main/java/org/example/music/track/service/TrackStreamSnapshot.java
- src/main/java/org/example/music/track/web/TrackStreamErrorAdvice.java
- src/main/java/org/example/music/track/web/TrackStreamResponseWriter.java
- src/main/resources/application.properties
- src/test/java/org/example/music/storage/S3AudioStreamStorageHttpTest.java
- src/test/java/org/example/music/storage/S3AudioStreamStorageTest.java
- src/test/java/org/example/music/storage/config/StreamReadConfigTest.java
- src/test/java/org/example/music/track/TrackStreamIntegrationTest.java
- src/test/java/org/example/music/track/controller/TrackStreamControllerTest.java
- src/test/java/org/example/music/track/service/TrackStreamConditionsTest.java
- src/test/java/org/example/music/track/service/TrackStreamQueryServiceTest.java
- src/test/java/org/example/music/track/service/TrackStreamRangeParserTest.java
- src/test/java/org/example/music/track/service/TrackStreamServiceTest.java
- src/test/java/org/example/music/track/web/TrackStreamResponseWriterTest.java
- src/test/resources/fixtures/audio/README.md
- src/test/resources/fixtures/audio/silence.flac

工作区 `.gitignore` 有本次开始前用户改动，不属于本任务，未修改、不纳入待提交范围。上表列出其余全部变更（含本审核材料）。

## 迁移、配置与依赖

- 数据库迁移：无；V1—V8、ddl-auto、SecurityConfig、既有外部服务版本均未改动。
- 新显式依赖：software.amazon.awssdk:apache-client:2.25.60，版本来自既有 BOM；用于同步读取客户端的有限连接池与连接/获取/读取空闲超时，不升级 BOM。
- 新配置 app.storage.stream.max-connections / S3_STREAM_MAX_CONNECTIONS，默认 50，@Min(1)。.env.example 仅增加占位默认值，无真实凭据。
- 复用 app.storage.connect-timeout / S3_CONNECT_TIMEOUT 默认 2s：新客户端连接建立和池获取等待；复用 app.storage.read-timeout / S3_READ_TIMEOUT 默认 30s：新客户端 socket 读取空闲时限。
- 新客户端无 SDK API 总/单次预算，RetryPolicy.none()；不会将连续长音频误限制为连接预算。两个客户端由 Spring 关闭。原客户端 @Primary，既有 AudioStorage 消费者及原调用预算不变，有配置测试证明。
- 同步 Servlet，不引入执行器。真实 HTTP 测试使用 Tomcat connection-timeout=1s（测试值），反射确认实际连接/线程/超时上限均为正；客户端不读取时有界等待关闭源流。部署文档要求核对实际生产配置、有限并发、流路径禁 buffering/gzip/cache 及逐跳空闲超时。

## 实际验证

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check
```

BUILD SUCCESSFUL，6m 51s；通过 **519 / 失败 0 / 跳过 0**，71 个测试类，来自最终 build/test-results/test/TEST-*.xml。cleanTest 强制重跑；新增播放相关 102 项。真实 MySQL 8.4 / Redis 7.4 / MinIO 全部执行。跳过项：无。随后 check 为 UP-TO-DATE，只复核最终构建状态，不当作重跑测试。git diff --check 通过；仅用户既有 .gitignore 有换行提示。

| 类 | 通过 | 失败 | 跳过 |
| --- | --- | --- | --- |
| org.example.music.storage.S3AudioStorageIntegrationTest | 5 | 0 | 0 |
| org.example.music.storage.S3AudioStreamStorageHttpTest | 8 | 0 | 0 |
| org.example.music.storage.S3AudioStreamStorageTest | 12 | 0 | 0 |
| org.example.music.storage.SongStorageMigrationIntegrationTest | 2 | 0 | 0 |
| org.example.music.storage.config.StreamReadConfigTest | 2 | 0 | 0 |
| org.example.music.track.MediaImportIntegrationTest | 1 | 0 | 0 |
| org.example.music.track.TrackCoverIntegrationTest | 18 | 0 | 0 |
| org.example.music.track.TrackDetailIntegrationTest | 8 | 0 | 0 |
| org.example.music.track.TrackListIntegrationTest | 20 | 0 | 0 |
| org.example.music.track.TrackStreamIntegrationTest | 16 | 0 | 0 |
| org.example.music.track.controller.TrackStreamControllerTest | 15 | 0 | 0 |
| org.example.music.track.service.TrackStreamConditionsTest | 10 | 0 | 0 |
| org.example.music.track.service.TrackStreamQueryServiceTest | 11 | 0 | 0 |
| org.example.music.track.service.TrackStreamRangeParserTest | 16 | 0 | 0 |
| org.example.music.track.service.TrackStreamServiceTest | 6 | 0 | 0 |
| org.example.music.track.web.TrackStreamResponseWriterTest | 6 | 0 | 0 |

覆盖证据：

- MockMvc 参数边界、401、错误 ProblemDetail/头、条件优先级、HEAD 与 GET 竞态；Service 角色×状态及本地/远端元信息校验；Range 单测含超 long。
- 真实容器 MP3/FLAC 原始字节、范围、304/412/416、权限隐藏、降权管理员、真实 JWT 版本撤销优先于 304、重复读取 version/资产计数不变、存储访问时无事务。
- MinIO 真实删除、HEAD/GET 间修改/删除、暂停与恢复；对象异常不误返 304，错误不泄漏 bucket/key。
- 随机端口实际 HTTP 提交后断流验证原字节前缀且无 JSON；TCP RST 取消源流且池复用；4.27MB 音频客户端停止读取验证写入超时释放。HTTP 集成合并在 TrackStreamIntegrationTest，避免重复建立同套上下文。
- JDK HttpServer + 实际 Apache/S3Client 受控上游：实际错误响应头/状态、1MiB 长对象取消不 drain 且复用池、连接预算 100ms 下持续约 1 秒成功、600ms socket 空闲预算下停顿 1500ms 失败；不替代 MinIO 集成证据。
- 自生成 66 字节有效 FLAC 静音夹具及说明；MP3 复用既有生成器，长音频仅重复完整 MPEG 帧，不依赖外部编码器或素材版权。

## 文档、基线与 curl

- 基线：main @ b258d63 / working tree。代码尚未提交/推送。
- API：docs/api/track/stream-track.md（含完整权限、参数、响应头、错误表、条件优先级及 curl）。
- 部署：docs/deployment/song-storage.md（播放专项配置）。
- 设计：docs/superpowers/specs/2026-10-30-track-stream-design.md（仅 accepted 状态更新）。
- 计划：docs/superpowers/plans/2026-10-30-track-stream-plan.md（执行记录）。

POSIX shell，ACCESS_TOKEN 为现有登录获得的凭证变量：

```sh
curl 'http://localhost:8080/api/v1/tracks/42/stream' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Range: bytes=0-1023' \
  --dump-header range.headers --output first-1024.bin
```

## 默认项、限制与未决问题

无阻塞业务问题。此接口保持 Bearer 请求头方案，原生 audio.src 媒体授权另行设计。无新增专用播放限流，有限池及容器/代理并发保护不等于按用户限流；扩大规模前按 O01/O09 压测认证查询、吞吐和池占用后独立设计。音频不可原地替换是摘要/日期验证器的当前前提，已下载字节无法撤回。已提交的断流无法改写状态码，客户端须依据长度/传输错误识别失败并重新请求。O07 既有 TRACK_STORE_UNAVAILABLE 文案等公共清理不在本次扩大修改。

## 第 1 轮独立审核结论

2026-10-30，Claude：accepted，无阻断项。独立执行 MINIO_INTEGRATION_TESTS=true .\gradlew.bat cleanTest check，BUILD SUCCESSFUL（6m 54s），519 通过 / 0 失败 / 0 跳过，共 71 类。

三条非阻断建议（Range 单位判定可读性、错误 Advice supports 作用域、提交前失败日志措辞）登记为任务书 O13，本次不修改已审核代码。用户已授权提交推送。上文“待审核/未提交”为送审时状态，本节为最终结论；.gitignore 既有用户改动继续排除。
