# 歌曲播放接口设计

日期：2026-10-30

状态：对话设计已确认，待书面规格审阅。

基线：main @ 05f8843。

## 1. 目标与范围

本次只实现 `GET /api/v1/tracks/{trackId}/stream`：经认证和权限检查，由后端从私有 MinIO/S3 流式读取原始音频，支持单区间 HTTP Range、条件验证与断点续传。

初始媒体为现有 MP3、FLAC；不转码、不提供 HLS、多码率、波形、播放历史或统计写入。字节偏移不等同于秒数，拖动进度对应区间的计算由客户端完成，实际解码能力由客户端决定。

不新增媒体凭证、Cookie 登录、匿名播放、预签名 URL 或 URL 中的 Token。仅支持现有 Bearer 请求头认证。原生浏览器 audio.src 不能直接添加自定义 Bearer 头；本次供能发送认证头和 Range 的客户端使用，原生网页播放器媒体授权另行规划，不以下载完整文件再播放替代流式能力。

## 2. 已确认方案

| 决策 | 选定方案 | 其他方案与取舍 |
| --- | --- | --- |
| 读取路径 | 后端代理私有对象存储 | 直接存储地址/预签名链接不符合既定部署与本次鉴权边界 |
| 管理员试听 | ADMIN 可访问所有状态 | 限制所有角色只读已发布歌曲会妨碍发布前试听 |
| 区间读取 | 支持单区间；多区间忽略并回退完整 200 | multipart/byteranges 增加实现与测试复杂度，暂不实现 |
| 缓存 | private, no-cache，每次验证 | no-store 增加重复传输；短期免验证缓存延迟权限检查 |
| 客户端认证 | Bearer 请求头 | 原生 audio 的额外授权作为独立任务，不混入本接口 |

HTTP 字段规则参考 [RFC 9110 条件请求](https://www.rfc-editor.org/rfc/rfc9110.html#section-13) 与 [Range](https://www.rfc-editor.org/rfc/rfc9110.html#section-14)。下文明确产品选择和处理边界，不增加其他 HTTP 业务接口。

## 3. 请求与权限

- Method：GET。
- Path：`/api/v1/tracks/{trackId}/stream`。
- 请求体：无；不提供文件名、对象键、用户 ID 或角色参数。
- trackId：Long 正整数。零、负数、小数、非整数和溢出返回 400 VALIDATION_FAILED。
- 必需：Authorization: Bearer Token。
- 可选：Range、If-Range，以及标准条件头 If-Match、If-Unmodified-Since、If-None-Match、If-Modified-Since。

| 登录角色 | DRAFT | PUBLISHED | ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| 已认证非管理员，含上传者本人 | 404 | 允许继续检查/读取 | 404 |
| 以 ADMIN 角色登录的管理员 | 允许继续检查/读取 | 允许继续检查/读取 | 允许继续检查/读取 |

“允许”后仍可能返回条件响应或资产/存储错误。管理员以 USER 角色登录时按非管理员处理。共享曲库不按上传者归属限制管理员访问。

每次请求（含每次 Range 和条件验证）执行既有 JWT、角色及 token version 校验。先验证歌曲存在及可见性，再访问音频资产和存储。不可见与不存在使用相同 TrackNotFoundException 及 404 TRACK_NOT_FOUND；不通过 Range、条件头或错误响应泄漏隐藏音频的大小、摘要和时间。

授权以本次请求快照为准，不按每个传输块查询用户或歌曲。归档或权限撤销会影响后续请求，不承诺中途终止已经授权的传输，也无法撤回客户端已取得的数据。

## 4. 媒体及对象校验

本地音频资产必须存在，kind=AUDIO；大小为正数；sha256 为合法 64 位十六进制；bucket/objectKey 非空；createdAt 可用。标准存储 MIME 为 audio/mpeg、audio/flac，沿用导入时的规范化结果，不重新解码音频。

本地引用缺失、kind 或元信息非法为 500 INTERNAL_ERROR。读取对象元信息后比较大小、MIME、摘要；缺失/非法或不一致为 502 AUDIO_STORAGE_INCONSISTENT。摘要只比较已保存的元信息，不在每次播放中读完整内容重算，继续依赖上传校验及对象不可原地修改的约束。

对象不存在为 404 TRACK_NOT_FOUND。资产记录存在但对象缺失时记录 warning，仅含 trackId、traceId 和事件类别，不含 bucket/key 或底层异常消息。不得透传 AudioObjectNotFoundException 的存储定位文案。

现有 head() 对远端非法摘要会抛 IllegalArgumentException，应仅在该存储调用边界转换为 502，不笼统捕获整个业务方法的编程异常。

HEAD 检查通过后，实际 GET 仍可能遇到删除、变化或上游不按要求返回区间。打开流时验证实际响应而非只相信 HEAD 推算值，失败关闭/中止该流，再按本接口错误契约处理。

## 5. 成功响应头

200/206 的响应体为音频字节流，包含：

| 响应头 | 规则 |
| --- | --- |
| Content-Type | 已校验的实际音频 MIME |
| Content-Length | 200 为完整字节数，206 为返回区间字节数 |
| Accept-Ranges | bytes |
| Content-Range | 仅 206 为 bytes start-end/total；普通 200 不返回 |
| ETag | DB 与对象一致性校验后的 SHA-256，小写并加双引号，强标签 |
| Last-Modified | 音频资产 createdAt 按 UTC 转为秒精度 HTTP-date，不用歌曲 updatedAt |
| Cache-Control | private, no-cache |
| Vary | 包含 Authorization，保留既有项 |

保持现有安全头。不返回原始文件名、内部存储位置、凭据或底层错误。服务器和反向代理不压缩/转换音频，确保 ETag、长度与字节位置对应同一表示。

Last-Modified 不得晚于响应生成时间；元数据时间在未来时按响应时间钳制，并将日期验证器视为不可靠。日期用于展示和验证，内容身份仍以 ETag 为主。客户端 ETag 是内容 SHA-256，不等同于 S3 自身 ETag，不能直接拿它作为 S3 If-Match。

## 6. 条件请求顺序

先完成认证、歌曲可见性、音频资产与对象 HEAD 校验；正常读取资格成立后再处理条件，避免对象丢失或存储故障时误返 304。

| 优先级 | 条件 | 结果 |
| --- | --- | --- |
| 1 | If-Match（若存在） | 强比较；失败 412 |
| 2 | 无 If-Match 时的 If-Unmodified-Since | 有效日期条件失败 412 |
| 3 | If-None-Match（若存在） | 弱比较；匹配 304 |
| 4 | 无 If-None-Match 时的 If-Modified-Since | 有效日期表明未修改则 304 |
| 5 | 仍应为 200 时处理 Range/If-Range | 进入完整读取或区间读取 |

ETag 条件支持单标签、标签列表和通配符。If-Match 的弱标签不能匹配；If-None-Match 的弱标签可以匹配同值强标签。无法识别的 If-Match 按不能匹配处理，无法识别的 If-None-Match 不触发 304；无效日期条件忽略。If-Unmodified-Since/If-Modified-Since 按秒精度比较，不与 If-Range 的精确匹配混用。

304 无响应体、不打开内容流，携带 ETag、Last-Modified、Cache-Control 和 Vary，不伪填 Content-Length: 0，不携带 Content-Range。即使 Range 非法，也不能优先于已经成立的 304 或 412。

无 Range 时忽略 If-Range。有 Range 时，强 ETag 精确匹配才通过标签验证；弱标签、不同标签或损坏值均使 Range 被忽略，回退完整 200，不返回 412。

日期型 If-Range 仅在日期等于 Last-Modified 且可判定为强验证器时成立：当前音频资产及歌曲音频引用不可替换、时间未被钳制时，源站可依据不可变性判断。无法确认该不变性（包括未来引入替换能力）、无效或不同日期时回退完整 200。日期格式使用框架符合 HTTP-date 的解析能力，不手写宽松本地时区解析。

## 7. Range 契约与边界

范围基于完成校验的完整长度 N，且 N>0。

| 请求 | 结果 |
| --- | --- |
| 无 Range | 200，完整字节 |
| bytes=start-end | 起点有效且 end>=start 时 206；end 超末尾则钳制到 N-1 |
| bytes=start- | 起点有效时 206，从 start 到 N-1 |
| bytes=-length | length>0 时取末尾 min(length,N) 字节，206 |
| start>=N、end<start、后缀为 0 | 416 |
| 已识别 bytes 单区间语法损坏 | 416 |
| 多区间列表 | 忽略 Range，完整 200 |
| 无法识别的范围单位 | 忽略 Range，完整 200 |

Range 和条件头遵守 HTTP 字段语法与大小写规则；byte range 单位比较不依赖字母大小写。分隔符附近允许的空白与数字内部的非法空白区别处理。重复 Range 字段按合并后的多区间处理策略忽略，不能只取第一行悄悄处理；单值 If-Range 重复/冲突按验证不成立回退完整 200。

单区间数字可超出 long。使用 BigInteger 或等价的有界、安全比较，先与实际 N 比较/钳制再转 long；不能因解析溢出返回 500，不能把合法超大 end/后缀长度当成语法错误。大 start 为不可满足 416，超大 end/后缀按完整边界裁剪。受服务器请求头大小限制约束，不引入无限解析。

无论一个有效区间是否覆盖整个文件，都按有效 Range 返回 206。区间端点包含在内，Content-Length=end-start+1。

416 使用 ProblemDetail，携带 `Content-Range: bytes */N` 与 no-store，不打开内容流；只能在权限、对象及前置条件通过并且 If-Range 没有要求回退后产生。

多区间策略只是不实现 multipart，不限制单次区间最大长度或将客户端请求擅自改为固定小块；开放尾区间可覆盖剩余完整文件。

## 8. 存储适配的必要增强

现有 S3AudioStorage.readRange() 会 GET 指定范围，但 AudioObjectRange 中的 MIME、总长、范围和摘要来自 HEAD 及本地推导，尚未检查 GET 响应的实际状态/头。本次不能直接把这些推导值当作实际 GET 校验的证明。

在现有 AudioStorage 边界增加播放所需的严格读取能力（或等价最小扩展），由存储适配层检查 SDK GET 响应：区间请求应得到正确的状态、Content-Range、Content-Length、MIME、摘要元信息；不得从错误位置的完整响应截取前几字节冒充所请求区间。全量读取也要检查实际总长与元信息。

业务层不解析或强转 AWS SDK ResponseInputStream；SDK 类型留在存储实现。返回受控内部流/范围描述，不返回 Entity 或 SDK 对象给 Controller。

校验失败关闭/中止已开流，并转换为专用存储一致性异常，在播放边界映射为 502。已经按校验快照计算为合法的 Range，若随后上游返回 416 或再次 HEAD 发现长度变化，应视为存储竞态/不一致 502，不反过来指责客户端 Range 非法。

优先为播放增加严格读取入口，保持已有 put/head/read/readRange/delete 的既有消费者行为；若必须调整公共返回信息，仅作兼容增强并回归所有消费者。不重命名整个存储模块，不借机重构封面、导入或删除。

## 9. 超时、资源与部署

数据库只读事务取得歌曲及音频快照后结束；HEAD、GET 和响应传输均在事务外。404/412/416/304 不遗留音频内容流。

固定大小缓冲传输，不使用 readAllBytes，不将完整音频放入 byte[]、Redis 或本地持久目录。正常完成、元信息错误、读/写失败、客户端取消以及关闭失败均明确释放源流；取消长对象时避免通过完整排空剩余内容来“关闭”，必要时使用 SDK abort，由存储适配管理。

首块读取、响应头设置和失败清理顺序必须使提交前错误可以输出干净 ProblemDetail，不残留音频 Content-Length、Content-Type、ETag 或 206 Content-Range；416 的错误长度头按其专用契约重新设置。保留 traceId 和安全头，不能直接 reset 后丢失上下文。

提交后断流不能改状态、追加 JSON 或从头重试拼接。交容器终止传输，关闭/中止源流，记录脱敏 trackId/traceId 与事件类别；客户端按不足 Content-Length 识别失败，后续通过新请求续传。

当前 StorageConfig 将 connectTimeout/readTimeout 用作 SDK API 调用预算，并未显式设 socket 超时。网络连接、池获取和读取空闲超时与“整首歌可传输多久”必须区分；不能把一次短连接预算或一次 API 总预算错误用于限制正常长音频。

计划显式声明 `software.amazon.awssdk:apache-client`，版本由已有 `software.amazon.awssdk:bom:2.25.60` 管理。用途仅为配置现有同步 SDK HTTP 传输的连接、连接池等待及 socket 读取空闲超时；不引入新存储供应商、升级 SDK 或更换整套客户端。该模块可能已在运行时由 SDK 间接提供，显式声明是为了编译期配置能力。

播放严格读取可使用专用、生命周期受 Spring/SDK 管理的读客户端，复用现有 endpoint、region、凭据和桶设置。保留已有上传/删除客户端与预算；不为实现播放而改它们的耗时语义。优先沿用外部化 connectTimeout/readTimeout 的连接与空闲值，若实现计划确需独立字段，应列出配置名称、默认值和兼容行为后再实施。

同步或有界执行方式在计划中选定，不创建无界线程。连接池、容器并发及响应写入等待必须有界；真实测试验证慢上游和断开的客户端能在配置范围内释放资源，而不是只检查“设置了 API 超时”。不在本次实现分布式限流或用户级并发租约，单独性能/容量规划仍由 O09 跟踪。

更新既有部署文档：仅代理 Spring Boot，MinIO 保持私有；流路径关闭响应缓冲和音频 gzip/转换；设置合理的代理读取/发送空闲超时与有限连接并发，不用音频总时长作为读取空闲超时；客户端中断向上游传播。保留明确 CORS 白名单，不新增通配授权。

SDK 配置依据：[超时层次](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html)、[Apache 客户端的编译依赖与生命周期](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-apache.html)。采用项目固定版本能力，文档里的较新 SDK 示例版本不作为升级依据。

## 10. 错误表

业务错误：RFC 9457 ProblemDetail、application/problem+json、稳定 code、urn:problem:music:*，Cache-Control: no-store。响应不泄漏堆栈、SQL、对象位置或凭据。

| 场景 | HTTP | code |
| --- | --- | --- |
| 非法 trackId | 400 | VALIDATION_FAILED |
| 缺少认证 | 401 | UNAUTHORIZED |
| 认证必要声明非法 | 401 | INVALID_TOKEN |
| Token 版本不符、用户不存在或登录角色失效 | 401 | TOKEN_REVOKED |
| 歌曲不存在、不可见或音频对象丢失 | 404 | TRACK_NOT_FOUND |
| 条件失败 | 412 | PRECONDITION_FAILED |
| 已处理的单区间语法错误或不可满足 | 416 | RANGE_NOT_SATISFIABLE |
| 本地音频资产一致性故障 | 500 | INTERNAL_ERROR |
| 远端元信息、实际响应或区间不一致 | 502 | AUDIO_STORAGE_INCONSISTENT |
| 存储连接、超时或提交前读取故障 | 503 | MEDIA_STORAGE_UNAVAILABLE |
| 业务数据库查询/事务失败 | 503 | TRACK_STORE_UNAVAILABLE |
| 认证状态存储不可用 | 503 | AUTH_STATE_STORE_UNAVAILABLE |

JWT 解码失败仍沿用既有资源服务器 401/WWW-Authenticate，可能没有业务 ProblemDetail。权限隐藏为 404，无正常业务 403 分支。认证缓存、统一认证响应和预存错误文案由 O01/O06/O07 单独处理。

## 11. 文件、迁移与文档范围

预计新增 Stream Controller、Service、短事务查询/快照、Range 与条件请求解析类型、响应写入组件及专用异常；最小修改存储适配、读取客户端配置、build.gradle 显式 HTTP 模块与统一异常处理。若复用封面通用逻辑，必须证明不改变其对外行为，不为消除少量重复代码扩大重构。

无需新增数据库字段、索引或 Flyway 迁移，既有 V1—V8 不修改。新增依赖/配置的最终精确清单在实现计划与审核材料登记。

新增中文 `docs/api/track/stream-track.md`，完整 OpenAPI：二进制 200/206、304 无体、所有错误状态、认证角色矩阵、区间与条件头、响应头、缓存、断流和客户端限制及可执行占位 Token curl。

更新 `docs/deployment/song-storage.md` 的流代理和超时要求；任务书 T03 在实现后标待审核、代码审核 accepted 后才标已完成。原生浏览器播放授权另登记为独立规划项，不在本次实现其他接口。

工作区既有 .gitignore 改动属于用户额外变更，保留，不纳入本设计或实现范围。

## 12. 验证与完成标准

- 参数/Range 单测：正整数 ID；闭区间、开放尾、后缀、单字节、全覆盖、末尾裁剪、零后缀、逆序、越界、超 long 大整数、空白/损坏语法、未知单位、多区间和重复字段，不能溢出或发生无界解析。
- 条件测试：强/弱 ETag、列表/通配符、日期精度、优先级、无效日期、If-Range 标签/日期可靠性及回退；同请求带错误 Range 时 304/412 优先。授权失败、对象丢失/不一致或存储不可用不得误返 304。
- HTTP 契约：200/206 的精确字节与头部，416 的 bytes */N，304 无体，错误 ProblemDetail/no-store，提交前清理音频头，保留 traceId 和安全头。
- 角色：完整状态 × 角色矩阵，含管理员 USER 登录降权、上传者本人、真实 JWT 及版本撤销；每次区间/条件请求重新检查。
- 本地/远端资产：缺引用、错误 kind/MIME/大小/摘要，HEAD 与 GET 间删除或变化，实际 GET 错误状态、错区间、错长度；SDK 类型不流入业务 HTTP 层，错误不泄漏位置。
- MySQL/Redis/MinIO 真实 Testcontainers：有效 MP3/FLAC 上传与完整/范围读取、存储暂停故障及恢复；存储 IO 时断言没有数据库事务；无写入副作用。
- 实际上游响应校验的精确故障可用受控 HTTP 上游/SDK 测试注入，但不能替代真实 MinIO 集成。严格读取失败关闭流；覆盖上游忽略 Range 返回 200 等错误响应。
- 随机端口真实 HTTP：提交后断流无 JSON 拼接、客户端取消资源释放；长对象取消不排空全部剩余内容；慢上游触发读取空闲超时；持续有数据的正常传输超过连接预算仍可完成。配置值在测试中缩短，避免测试耗时依赖整首歌曲时长。
- 存储、封面、导入、删除的完整回归，验证读取增强和显式 HTTP 客户端配置未改变既有接口行为。

最终设置 `MINIO_INTEGRATION_TESTS=true` 执行 `.\gradlew.bat check`，必要时 cleanTest 强制执行。准确汇总通过/失败/跳过，必要容器测试 skipped 不能作为通过。按 AGENTS.md 提供单接口审核请求、全部含 untracked 文件、实际依赖/配置、迁移“无”、API 文档、基线和限制。

## 13. 默认项与限制

只有原始字节流与单区间，不预先切片、转码或解码，也不保证任意播放器都支持 FLAC。HTTP 缓存不等于应用已经实现认证状态缓存或消除了 MySQL 查询。

本次不增加专用业务限流；容量由有限的客户端连接池、容器并发、代理配置及超时约束，并以真实故障测试验证。后续扩大使用前仍应按任务书进行性能基线和认证状态优化。

不增加独立 HEAD 业务接口。若框架对 GET 隐式支持 HEAD，必须抑制内容读取、忽略 Range 并保持鉴权及正确的完整资源元信息；不因隐式处理泄漏流或执行整段下载。
