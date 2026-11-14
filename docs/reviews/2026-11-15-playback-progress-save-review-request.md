# H02 播放进度保存接口独立审核请求

本请求为第 1/3 轮实现审核，日期为 2026-11-15，时区 Asia/Shanghai。请先读取 AGENTS.md、CLAUDE.md、已确认设计及实施计划，核对 Git 范围与当前工作区，独立只读审查本接口。

## 本次接口

- `PUT /api/v1/users/me/playback-progress/{deviceId}`
- [正式 API](../api/playback/save-playback-progress.md)
- [已确认设计](../superpowers/specs/2026-11-14-playback-progress-save-design.md)
- [实施计划](../superpowers/plans/2026-11-15-playback-progress-save-plan.md)

## 需求与权限

- 需要有效 Bearer JWT，服务端主体提供 Integer userId 与 loginRole。每个用户、每个持久化设备 UUID 保存一份最近歌曲及整秒位置。
- USER 仅可保存 PUBLISHED，ADMIN 当前登录角色可保存全部现存状态，管理员以 USER 登录按 USER 判断。缺失与隐藏歌曲均为 404 TRACK_NOT_FOUND。
- UUID 接收标准 36 字符格式并规范化为小写，归属使用当前主体。相同 UUID 在不同用户下隔离。
- 请求只含 trackId、positionSeconds、sequence 三个整数字段。严格拒绝重复、未知、缺失、null、字符串、小数、科学计数法及溢出。
- trackId 为正 Long，positionSeconds 为非负 Long，sequence 为 1 至 9007199254740991。时长大于 0 时位置上限为 durationMs/1000 向下取整，历史 null 或 0 时长允许非负整数秒。

## 声称实现的行为

- 唯一 PUT 首次保存、更新与当前同号同内容重试均为 200，返回 deviceId、trackId、positionSeconds、sequence、updatedAt。成功含 no-store，时间为 UTC Instant 微秒。
- 按用户与设备联合维度比较序号，大号更新，同号同内容保留原完整审计行，小号返回 409 PLAYBACK_PROGRESS_STALE_SEQUENCE，同号异内容返回 409 PLAYBACK_PROGRESS_SEQUENCE_CONFLICT，两者包含 currentSequence。
- 切歌、向后拖动、自然结束归零均可用更大序号提交。客户端持久保存 UUID 和序号，重启及重新登录继续使用，多页面原子分配共享序号。
- 正常播放每 5 秒产生保存事件，暂停、拖动完成、切歌和自然结束立即产生。该频率作为客户端接入约定写入 API。
- 事务先以 FOR SHARE 检查歌曲，再以联合主键 upsert 取得设备行锁、FOR UPDATE 当前读并比较。原子首次创建使用完整合法事件，条件更新后读取实际状态。
- 歌曲物理删除使用 SET NULL，保留设备序号、位置及审计字段。原歌曲重试按可见性返回 404，旧号保存其他可见歌曲仍为 409，新号可保存。用户删除级联清理。
- 数据访问异常从事务 Bean 传播，由外层非事务 Service 捕获 DataAccessException 或 TransactionException，覆盖开始、执行与提交异常，返回专用存储 503。已提交但确认失败时原事件重试保持幂等。
- Redis 首请求建立 60 秒窗口，默认每账号 120 次，全部设备和登录角色共享。基础校验后的尝试消费额度，含重复、冲突、不可见、时长超限与数据库失败。429 含 Retry-After，额度故障 503。
- 媒体类型校验采用本接口 RequestReader 并映射 415。新增业务错误使用现有 ProblemDetail、稳定 code 与 urn 格式。新 503 日志记录 traceId 与异常类型。

## 基线与提交

实施基线为 `main @ c546a1a998c83b94cc5db3ef5691b0cd5cc1918f`，业务起点为 `0c854d96cad44c960575605bdf1033cd56dc2572`，设计提交为 4da9cf7。阶段实现提交为 ab4a5c2、403d127、f97c2d1、7020cfc。

本次完整自验对应的业务与测试快照为 `7020cfc54932f8e38413769a8bfb6d4e50748a60`。后续材料提交包含 API、设计状态、计划执行记录及本请求。CLI 启动提示将记录完整送审 HEAD，审核方同时使用 Git 核实。

设计开始及实施开始均存在既有 `.gitignore` 改动，内容为增加 `docs/learning` 忽略规则，来源已在设计与计划登记。当前送审工作区披露该既有改动，业务及测试均已提交。

## 变更文件，含 untracked 与既有改动

相对实施基线的完整范围为下列 40 项，包含本请求及既有 .gitignore。构建与 CLI 运行产物位于 build，按仓库现有规则忽略。

- `.env.example`
- `.gitignore`，既有用户改动，增加 docs/learning 忽略规则
- `docs/api/playback/save-playback-progress.md`
- `docs/reviews/2026-11-15-playback-progress-save-review-request.md`
- `docs/superpowers/plans/2026-11-15-playback-progress-save-plan.md`
- `docs/superpowers/specs/2026-11-14-playback-progress-save-design.md`
- `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`
- `src/main/java/org/example/music/playback/config/PlaybackProgressConfig.java`
- `src/main/java/org/example/music/playback/config/PlaybackProgressProperties.java`
- `src/main/java/org/example/music/playback/controller/PlaybackProgressController.java`
- `src/main/java/org/example/music/playback/controller/PlaybackProgressRequestReader.java`
- `src/main/java/org/example/music/playback/dto/SavePlaybackProgressRequest.java`
- `src/main/java/org/example/music/playback/dto/SavePlaybackProgressResponse.java`
- `src/main/java/org/example/music/playback/exception/PlaybackProgressRateLimitUnavailableException.java`
- `src/main/java/org/example/music/playback/exception/PlaybackProgressRateLimitedException.java`
- `src/main/java/org/example/music/playback/exception/PlaybackProgressSequenceConflictException.java`
- `src/main/java/org/example/music/playback/exception/PlaybackProgressStaleSequenceException.java`
- `src/main/java/org/example/music/playback/exception/PlaybackProgressStoreUnavailableException.java`
- `src/main/java/org/example/music/playback/exception/PlaybackProgressUnsupportedMediaTypeException.java`
- `src/main/java/org/example/music/playback/exception/PlaybackProgressValidationException.java`
- `src/main/java/org/example/music/playback/repository/PlaybackProgressRepository.java`
- `src/main/java/org/example/music/playback/repository/PlaybackProgressRow.java`
- `src/main/java/org/example/music/playback/repository/PlaybackProgressTrackRow.java`
- `src/main/java/org/example/music/playback/service/PlaybackProgressRateLimiter.java`
- `src/main/java/org/example/music/playback/service/PlaybackProgressService.java`
- `src/main/java/org/example/music/playback/service/PlaybackProgressTransactionService.java`
- `src/main/resources/application.properties`
- `src/main/resources/db/migration/V11__create_user_playback_progress.sql`
- `src/test/java/org/example/music/playback/PlaybackProgressConcurrencyIntegrationTest.java`
- `src/test/java/org/example/music/playback/PlaybackProgressIntegrationSupport.java`
- `src/test/java/org/example/music/playback/PlaybackProgressIntegrationTest.java`
- `src/test/java/org/example/music/playback/PlaybackProgressRepositoryIntegrationTest.java`
- `src/test/java/org/example/music/playback/config/PlaybackProgressConfigTest.java`
- `src/test/java/org/example/music/playback/controller/PlaybackProgressControllerTest.java`
- `src/test/java/org/example/music/playback/controller/PlaybackProgressRequestReaderTest.java`
- `src/test/java/org/example/music/playback/service/PlaybackProgressProxyTest.java`
- `src/test/java/org/example/music/playback/service/PlaybackProgressRateLimiterIntegrationTest.java`
- `src/test/java/org/example/music/playback/service/PlaybackProgressRateLimiterTest.java`
- `src/test/java/org/example/music/playback/service/PlaybackProgressServiceTest.java`
- `src/test/java/org/example/music/playback/service/PlaybackProgressTransactionServiceTest.java`

审核后的原始 result、来源及反馈复核将登记到 `docs/reviews/2026-11-15-playback-progress-save-review.md`。accepted 后按项目协议更新任务书、PROJECT_STATE 与设计/计划状态。

## 数据库迁移

新增 `src/main/resources/db/migration/V11__create_user_playback_progress.sql`，原有 V1 至 V10 保留既有内容。联合主键为 user_id/device_id，track_id 可空并有索引，秒数及序号具有 CHECK，用户外键 CASCADE、歌曲外键 SET NULL。真实 MySQL 8.4 已执行并通过迁移与约束验证。

## 实际测试与构建

完整自验命令如下。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

- 进程退出码 0，BUILD SUCCESSFUL，耗时 1422.85 秒。
- 按 XML 汇总为 210 套件，通过 2541 / 失败 0 / 错误 0 / 跳过 0。
- 本接口模块为 11 套件 165 通过 / 0 失败 / 0 错误 / 0 跳过。
- MySQL、Redis、MinIO 必要集成测试实跑，跳过清单为空。抽查 S3AudioStorageIntegrationTest 为 5/0/0/0、S3AudioStreamStorageHttpTest 为 8/0/0/0、TrackCoverIntegrationTest 为 18/0/0/0、TrackStreamIntegrationTest 为 16/0/0/0。
- 完整日志为 `build/playback-progress-save-self-check.log`，进程结果为 `build/reports/playback-progress-save-self-check-process.json`，统计为 `build/reports/playback-progress-save-self-check-summary.json`。
- 自验 XML 归档为 `build/reports/playback-progress-save-self-check-xml`，供事实核对。请另行独立完整执行并保存独立日志与 XML。
- 开发分组实际运行了 ReaderTest、TransactionServiceTest 与 RepositoryIntegrationTest、RateLimiterTest/IntegrationTest 与 ConfigTest、ServiceTest/ProxyTest/ControllerTest、真实 JWT IntegrationTest、ConcurrencyIntegrationTest，以及最终 `test --tests "org.example.music.playback.*"`。
- 最终模块回归为 165/0/0/0，真实 MySQL 1213/40001 死锁、锁超时、提交确认失败、初次并发和删除序号保护均覆盖。API 示例 JSON 与 PowerShell curl 语法已检查，实际 OpenAPI 路径与三/五字段 Schema 经完整 HTTP 集成验证。

## curl 示例

PowerShell 7 使用环境变量 ACCESS_TOKEN。

```powershell
curl.exe --include --request PUT `
  "http://localhost:8080/api/v1/users/me/playback-progress/bcf04f9c-3876-4a77-8f6d-632ebd78db2e" `
  --header "Authorization: Bearer $env:ACCESS_TOKEN" `
  --header "Content-Type: application/json" `
  --data-raw '{"trackId":123,"positionSeconds":83,"sequence":42}'
```

## 未决问题与默认项

- 当前关键业务选择已由用户确认，书面设计与实施计划已获批准。
- 配额默认 120、整数秒边界、未知时长、结束归零、设备身份与序号生命周期，以及同号重试和删除语义均按已确认契约实现。
- 设备维度为客户端持久 UUID 对应的逻辑设备，浏览器不同配置文件分别生成 UUID。
- JWT 解码阶段的签名或过期错误沿用现有资源服务器响应，认证状态过滤器的声明或撤销错误使用现有稳定 code。实际行为已在 API 说明。
- 默认实施与审核范围按当前单接口执行，任务书 H02 读取接口保持独立规划。

## 审核反馈复核与处置

首次提交为“不涉及”。请输出标准中文审核结论及问题编号、原分级、位置、触发条件与依据。后续按 AGENTS.md 第 3.1 节核实问题、分级与修改方向，保留同一会话与三轮上限。
