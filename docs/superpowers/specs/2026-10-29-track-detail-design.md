# 歌曲详情接口设计

日期：2026-10-29

状态：设计审阅 accepted（2026-10-30）

## 1. 目标与范围

只实现 `GET /api/v1/tracks/{trackId}`，返回歌曲元数据和封面元信息。所有调用者均需登录；管理员可查看所有状态，其他已登录用户只能查看已发布歌曲。

本次不实现歌曲列表、搜索、播放或封面读取接口，不修改歌曲、媒体资产或对象存储，不新增依赖和数据库迁移，不修改既有迁移。

## 2. 方案与已确认决策

权限方案比较：独立普通用户和管理员详情接口便于各自演进，但增加重复契约与维护成本；共用详情接口按角色控制可见状态能复用当前相同的响应字段。本次选择后者。

封面方案比较：后端鉴权代理符合私有 MinIO 和既定网络部署；预签名 URL 要求客户端可访问对象存储，且链接有效期内持有者可访问；仅返回资产元信息不能提供图片地址。本次选择返回后端封面地址和元信息。

封面读取必须作为下一个独立接口实现。本次响应中的地址仅定义后续契约，在读取接口完成前无法加载图片；API 文档必须明确此限制。

## 3. HTTP 契约

- 方法与路径：`GET /api/v1/tracks/{trackId}`。
- 认证：有效 Bearer JWT，沿用现有签名、issuer、audience、有效期及 token version 校验。
- 路径参数：`trackId` 使用现有 Long 类型，必须为正整数；非整数、溢出、零和负数返回 400。
- 无请求体、分页、排序或过滤参数。
- 成功：`200 OK`，媒体类型 `application/json`。

响应示例：

```json
{
  "id": 42,
  "title": "歌曲标题",
  "artistName": "艺术家",
  "albumName": "专辑名称",
  "durationMs": 245000,
  "trackNumber": 3,
  "discNumber": 1,
  "status": "PUBLISHED",
  "cover": {
    "url": "/api/v1/tracks/42/cover",
    "contentType": "image/jpeg",
    "sizeBytes": 125600
  }
}
```

`id`、`durationMs`、`sizeBytes` 为 Long；`trackNumber`、`discNumber` 为 Integer；标题、艺术家、专辑、状态和封面 URL、内容类型为字符串。

无封面时 `cover` 明确为 null。专辑、时长、曲序或碟号缺失时对应字段明确为 null，不生成假值。状态沿用 `DRAFT`、`PUBLISHED`、`ARCHIVED`。

不返回 bucket、对象键、存储凭据、资产 ID、原始 ID3、原始文件名或内部审计字段。

## 4. 权限与可见性

| 调用者 | DRAFT | PUBLISHED | ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| 已认证非管理员 | 404 | 200 | 404 |
| 已认证管理员 | 200 | 200 | 200 |

角色只能来自服务端验证后的认证上下文，不接受客户端传入的角色、管理员标记或用户 ID。歌曲是共享曲库资源，不按上传者归属限制读取。

不存在和无权查看均返回相同的 `404 TRACK_NOT_FOUND`，不泄漏隐藏歌曲的状态及封面信息。先验证可见性，再读取封面元信息。当前接口没有角色拒绝导致的 403 分支，应通过非管理员访问隐藏状态的 404 验证权限。

角色判断使用现有 Spring Security 权限，无需为本接口额外查询用户表。既有认证状态校验成本保持现状。

## 5. 组件与数据流

Controller 使用 Bean Validation 校验路径参数，获取可信认证上下文并调用详情 Service，返回 record DTO。Controller 不直接访问 Repository 或存储 SDK。

Service 以只读事务查询歌曲、验证状态可见性、按可选封面引用查询媒体资产并完成 DTO 映射。使用现有 TrackRepository、MediaAssetRepository，保持有界读取；单次最多查询一条歌曲及一条封面资产，不查询音频对象，不进行 S3 请求。

封面元信息取自媒体资产的 contentType 和 sizeBytes；URL 由后端按固定路由和歌曲 ID 构造。没有封面引用时不查询封面资产。若封面引用存在但资产记录缺失或类型不是 COVER，视为内部数据一致性故障，使用统一的 500 错误响应，不伪装成无封面，不暴露底层细节。

不引入 Redis 详情缓存或专用限流机制；只读查询不产生写入副作用，重复请求遵循请求时可见状态。默认不引入 HTTP 条件请求，本次设置 `Cache-Control: no-store`，避免管理员读取的隐藏状态详情被共享缓存。

## 6. 错误处理

沿用统一异常处理、RFC 9457 ProblemDetail、`application/problem+json`、稳定 code 和 `urn:problem:music:*` 类型，保留 traceId。

| 场景 | HTTP 状态 | code |
| --- | --- | --- |
| 非法路径参数 | 400 | VALIDATION_FAILED |
| 缺少或无效认证 | 401 | 沿用现有认证错误码 |
| 歌曲不存在或不可见 | 404 | TRACK_NOT_FOUND |
| 数据库不可用 | 503 | TRACK_STORE_UNAVAILABLE |
| 内部数据一致性故障 | 500 | 沿用现有统一内部错误码 |

认证状态存储不可用沿用已有认证层响应。本次不引入写冲突、幂等键或状态迁移错误码。异常日志不包含 Token 或凭据，响应不泄漏 SQL、堆栈和内部路径。

## 7. 实现边界与文档

预计新增详情 Controller、Service、响应 DTO 及必要映射，并补充对应测试。仅在必要时最小调整现有 Repository、异常处理和测试配置。沿用现有安全默认认证规则，不对该路由 permitAll，不添加仅管理员可调用的限制。

更新完整 OpenAPI 注解，新增中文 `docs/api/track/get-track-detail.md`，包含字段类型、空值规则、权限矩阵、请求和响应、错误码、封面地址尚待实现的限制及使用占位 Token 的可执行 curl。

## 8. 验证与审核

- Controller 测试：成功 JSON、null 字段、非法 ID、未认证、错误响应媒体类型 application/problem+json 与稳定错误码、不泄漏内部字段。
- Service 测试：管理员和非管理员对三种状态的完整权限矩阵、不存在、无封面、封面映射、数据库故障和资产一致性故障。
- MySQL 与 Redis Testcontainers 集成测试：真实认证及角色映射、状态可见性、歌曲和媒体字段读取、重复请求无写入副作用。管理员读取他人创建歌曲也应成功。
- 对状态隐藏的歌曲验证不返回封面元信息；封面测试不以 mock 替代真实数据库验证。
- 本接口不访问 MinIO，但因变更属于 track 模块，依项目要求设置 `MINIO_INTEGRATION_TESTS=true` 执行完整 `.\gradlew.bat check`，包含已有 MinIO 回归。
- 如实统计通过、失败和跳过数量；必要容器测试被跳过时不得宣称完成。
- 完成后按 AGENTS.md 提供单接口审核材料，包含全部变更文件、API 文档、实际测试命令与数量和基线 commit。

## 9. 默认项

只读请求采用即时数据库查询，不新增缓存或限流；响应禁止缓存；封面地址采用同源相对路径。上述工程默认项不扩展接口范围。
