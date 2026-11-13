# 音乐播放器后端任务书

更新日期为2026-11-12。核对实现基线为main @ 95893fe，最新独立完整验证为185类2169通过、0失败、0错误、0跳过，详见[取消收藏实现审核](../reviews/2026-11-12-unfavorite-track-review.md)。

本文维护接口需求、工程优化及审核完成记录。未完成区登记下一步候选，完成区集中保存已交付能力和历史验证证据。

## 1. 状态与优先级

- 待设计表示需求已登记，接口契约待确认。未完成区的新功能默认处于此状态，表中的路径与优先级属于规划建议。
- 待实现表示设计已确认，开发中表示正在实施，待审核表示代码、API文档和必要验证已完成，已完成表示独立实现审核accepted。
- P0为正式对外使用前的安全与数据可靠性事项，P1为核心使用链路，P2为体验与管理能力，P3为按使用需求推进的增强项。
- 每次选中一个HTTP Method + Path实施，完成代码、文档、必要测试与独立审核后推进下一项。功能模块包含多个动作时，先拆成逐接口任务。
- 环境部署与验收事项集中跟踪O03—O05。完成区的代码审核结果与环境验收分别记录。
- 历史三项测试数字沿用原报告的通过/失败/跳过口径，四项数字表示通过/失败/错误/跳过，均为对应阶段的实际结果。

## 2. 未完成区

### 2.1 用户与账户

| 编号 | 优先级 | 任务 | 建议接口 | 简要说明 |
| --- | --- | --- | --- | --- |
| U08 | P2 | 获取本人登录历史 | `GET /api/v1/users/me/login-history` | 分页展示时间、结果、登录方式和必要设备信息；先在现有登录流程建立审计记录，设定保留期限及 IP 等信息的脱敏规则。 |
| U09 | P1 | 退出全部登录 | `POST /api/v1/auth/logout-all` | 通过版本递增撤销本人全部现有 Token，明确撤销何时生效；明确服务端撤销与客户端清理的配合。依赖统一认证状态更新机制。 |
| U10 | P2 | 退出当前登录 | `POST /api/v1/auth/logout` | 仅撤销当前会话，不影响其他设备；需先设计 jti 或会话标识及有效期内撤销记录，与 U09 区分语义。 |
| U11 | P3 | Token 续期 | `POST /api/v1/auth/refresh` | 确认长期登录需求，设计刷新凭证轮换、重放检测、撤销与安全存储。刷新继承原 auth_time 和有效认证版本，维护近期验证资格的原始时间。 |

### 2.2 歌曲与模糊搜索

| 编号 | 优先级 | 任务 | 建议接口 | 简要说明 |
| --- | --- | --- | --- | --- |
| T04 | P1 | 歌曲模糊搜索 | 待设计，可选 `GET /api/v1/tracks/search` 或扩展 `GET /api/v1/tracks?q=...` | 按歌曲名、歌手、专辑进行部分关键词匹配并分页。确认中文匹配、大小写、空白、匹配字段组合、排序与索引策略，限制关键词长度和查询成本，处理 `%`、`_` 等通配字符。搜索结果与统计遵守歌曲可见范围。 |
| T05 | P2 | 管理员歌曲列表 | `GET /api/v1/admin/tracks` | 围绕额外管理字段或新筛选需求设计，复用已有列表及状态筛选能力；是否需要独立入口在需求确认时决定。 |
| T06 | P2 | 管理员替换封面 | `PUT /api/v1/tracks/{trackId}/cover` | 确认允许修改的歌曲状态；验证文件大小、MIME、扩展名和魔数，创建新资产并处理旧对象清理、并发和失败补偿。 |
| T07 | P2 | 编辑已发布歌曲元数据 | `PATCH /api/v1/tracks/{trackId}/metadata` | 如实际需要，单独设计标题、艺术家、专辑等字段的在线修改及版本冲突；不顺手扩大现有草稿编辑接口权限。 |
| T08 | P3 | 恢复归档歌曲 | `POST /api/v1/tracks/{trackId}/restore` | 仅在确认需要时实施；先决定恢复到 DRAFT 还是 PUBLISHED，并验证媒体资产存在及状态并发，不能默认恢复即公开。 |

T04当前作为独立待设计需求。现有GET /api/v1/tracks已交付分页、排序与状态筛选，当前请求校验拒绝q参数。搜索路径在设计时确认，关键词匹配与结果分页复用现有列表表示及权限规则。拼音、错别字容错、搜索建议或全文检索按后续需求单独评估。

### 2.3 收藏与歌单

| 编号 | 优先级 | 任务 | 接口规划 | 目标与待确认事项 |
| --- | --- | --- | --- | --- |
| F03 | P1 | 本人收藏列表 | 待审核，`GET /api/v1/users/me/favorites` | 设计与计划已批准，代码与API完成，完整自验194套件2293/0/0/0，独立审核待执行；按本人及角色可见范围分页，默认收藏时间倒序。 |
| P01 | P2 | 创建私人歌单 | 待设计，独立创建接口 | 用户创建多个歌单，确认名称、描述、封面来源及数量限制。 |
| P02 | P2 | 本人歌单列表 | 待设计，分页读取接口 | 展示本人歌单、歌曲数及必要封面信息。 |
| P03 | P2 | 歌单详情与曲目 | 待设计，按单接口拆解 | 展示歌单资料和有序曲目，确认分页、不可播放歌曲及访问权限。 |
| P04 | P2 | 编辑与删除歌单 | 待设计，修改/删除分别实施 | 编辑名称、描述或封面及删除本人歌单，确认并发版本和删除影响。 |
| P05 | P2 | 歌单添加与移除歌曲 | 待设计，添加/移除分别实施 | 同一歌曲可属于多个歌单，确认同一歌单内的重复规则、批量上限及资源归属。 |
| P06 | P2 | 调整歌单歌曲顺序 | 待设计，独立排序接口 | 支持手动排序，确认顺序表示、并发移动及分页下的排序语义。 |
| P07 | P3 | 公开歌单与链接分享 | 待设计，发布/读取等分别实施 | 在私人歌单基础上确认公开范围、分享标识、撤回与访问规则。 |
| P08 | P3 | 收藏他人公开歌单 | 待设计，写/删/读分别实施 | 确认订阅原歌单或复制为本人歌单的语义，以及原歌单更新、撤回和删除后的行为。 |

### 2.4 听歌体验与内容扩展

| 编号 | 优先级 | 任务 | 接口规划 | 目标与待确认事项 |
| --- | --- | --- | --- | --- |
| H01 | P2 | 最近播放 | 待设计，播放上报/历史读取分别实施 | 展示最近听过的歌曲，确认何时记为一次播放、去重、保留期限及分页。 |
| H02 | P2 | 播放进度保存 | 待设计，保存/读取分别实施 | 恢复上次播放位置，确认秒或毫秒精度、完成阈值、多设备覆盖及写入频率。 |
| L01 | P2 | 歌词管理与读取 | 待设计，上传/编辑/读取分别实施 | 提供LRC或文本歌词，支持前端同步展示，确认来源、编码、大小、权限及存储方式。 |
| C01 | P2 | 歌手与专辑页面 | 待设计，按资源及单接口拆解 | 从现有元数据需求出发，确认歌手/专辑实体、同名合并、封面、曲序与分页。 |
| R01 | P3 | 个性化推荐 | 待设计，依赖收藏/历史等数据 | 根据歌曲元数据、歌手、标签及用户行为制定可解释推荐，确认冷启动、更新频率及结果分页。 |
| M01 | P2 | 音乐库批量整理 | 待设计，独立管理任务 | 批量编辑元数据、重复歌曲检测、封面及歌词补全，按具体操作拆接口，确认重复判据、人工确认及失败处理。 |

### 2.5 管理员与审计

| 编号 | 优先级 | 任务 | 建议接口 | 简要说明 |
| --- | --- | --- | --- | --- |
| A01 | P2 | 管理员查看用户列表 | `GET /api/v1/admin/users` | 仅 ADMIN，分页与受限筛选，响应最小化，按管理需求决定邮箱脱敏；不得暴露密码摘要或认证凭证。 |
| A02 | P2 | 管理员查看用户详情 | `GET /api/v1/admin/users/{userId}` | 提供必要账户状态与审计信息，与本人资料 DTO 分离；限定访问权限并记录必要管理访问。 |
| A03 | P2 | 禁用用户 | `POST /api/v1/admin/users/{userId}/disable` | 新增账户状态前先设计迁移；使登录及既有 Token 一并失效，保护最后一个可用管理员，记录操作原因与操作者。 |
| A04 | P2 | 启用用户 | `POST /api/v1/admin/users/{userId}/enable` | 独立实现状态恢复，明确启用后旧 Token 是否仍无效；与 A03 的状态模型一致。 |
| A05 | P2 | 管理员操作审计查询 | `GET /api/v1/admin/audit-events` | 对角色变更、用户禁用及歌曲发布/删除等关键操作建立安全审计并分页查询；需先核对现有角色历史，避免重复存储。 |

### 2.6 工程优化与上线保障

| 编号 | 优先级 | 任务 | 建议接口 | 简要说明 |
| --- | --- | --- | --- | --- |
| O01 | P1 | 用户认证状态优化 | 不新增接口；认证过滤链及状态服务 | 当前每次受保护请求查询 MySQL 获取角色和 token version。评估 Redis 认证状态缓存，明确可接受撤销延迟、角色变更一致性、并发旧值回填、TTL、防击穿与故障策略，压测后量化减少的 MySQL 查询。高频播放、封面请求规模化前优先完成。 |
| O02 | P0 | 认证入口限流剩余核验 | 注册、登录、角色选择及验证码入口，逐接口核验 | 建立入口与额度覆盖清单，核对现有注册、密码登录、邮箱验证码登录、角色选择等流程的实际规则，按明确缺口补充防枚举、暴力尝试及并发测试。已交付的专用验证用途与限流见完成区 O02-A。 |
| O03 | P0 | 数据与对象备份恢复演练 | 不新增业务接口 | 核对 MySQL 与 MinIO 的实际备份、保留及恢复能力，执行恢复并验证歌曲引用与对象完整性，记录恢复耗时和可接受数据损失。 |
| O04 | P0 | 部署安全与故障告警核验 | 不新增业务接口 | 验证 TLS、反向代理、CORS 白名单、密钥管理、私有 bucket、健康检查与错误告警，保留 Actuator 最小暴露。核验认证版本全实例升级与旧请求排空、真实 SMTP、代理 IP 桶、Redis 权限及同步时钟，记录环境验收与故障处理。 |
| O05 | P1 | 媒体补偿与孤儿对象清理核验 | 不新增接口；维护任务 | 对照现有导入/删除补偿实现与存储设计，补齐未覆盖的崩溃、中断、重复清理和遗留对象场景；避免误删有效引用，具备重试、退避和告警。 |
| O06 | P2 | 统一认证错误响应 | 不新增接口；既有安全入口 | 将 JWT 签名、过期等资源服务器错误与业务 ProblemDetail 规范统一，保留正确 WWW-Authenticate；作为独立公共契约变更回归全部认证入口。 |
| O07 | P2 | 歌曲存储故障文案修正 | 不新增接口；GlobalExceptionHandler | 将 TRACK_STORE_UNAVAILABLE 当前“歌曲草稿暂时无法更新”改为适用于读取与写入的中性文案，保持稳定 code 与 HTTP 状态。来自详情审核非阻断项。 |
| O08 | P2 | 封面一致性专用异常 | 不新增接口；track 模块 | 用模块专用异常表达封面资产缺失或类型不符，替代裸 IllegalStateException，保持外部 500 INTERNAL_ERROR 契约，改善日志定位。来自详情审核非阻断项。 |
| O09 | P1 | 核心链路性能基线 | 不新增业务接口；压测及现有指标 | 测量认证、列表、搜索、封面、播放的请求量、P95/P99、MySQL 查询数、连接池和存储吞吐；根据结果决定缓存/索引/限流，不用推测代替性能验证。 |
| O10 | P2 | CI 与真实集成测试保障 | 不新增业务接口；构建流程 | 核对现有 CI，确保 Gradle check、MySQL/Redis/MinIO 真容器回归、测试数量及 skipped 检查在合并前执行；保留报告，不能以缓存未执行或跳过冒充通过。 |
| O13 | P2 | 播放接口局部清理 | 不新增接口；Range 解析、错误 Advice、流日志 | 来自 T03 审核非阻断项：合并 Range 单位判定并固定 bytes =0-1 的非法语法归属；收窄错误 Advice supports；区分提交前失败与已提交中断日志。独立小任务，保持外部契约，修改后完整真实容器回归。 |
| O14 | P2 | 本人资料共享时间映射 | 不新增接口；user 模块响应映射 | 来自 U02 审核非阻断项：提取 GET/PATCH 两处 createdAt 文本到 UTC Instant 的转换，避免语义漂移；保持六字段契约、微秒精度和显式 null，独立验证两接口后再合入。 |
| O15 | P2 | 管理员启动密码边界 | 不新增接口；AdminBootstrapInitializer 与部署文档 | 注册密码修复审核 NOTE-001：初始化实际创建管理员时，密码只检查非空白，超过 BCrypt UTF-8 72 字节上限会导致启动失败。独立设计启动配置校验或部署约束；是否完整采用 8—72 个可打印 ASCII 字符需先确认，保持已有账户登录兼容，不在注册修复中扩改。 |
| O12 | P1 | 浏览器原生媒体授权 | 待设计；与播放入口单独确认 | 解决 audio.src 无法直接附加 Bearer 头的接入方式；评估短期媒体凭证或受控 Cookie 等方案，明确撤销、跨域、URL/日志泄漏与续传，不默认开放私有音频。T03 当前仅支持携带 Authorization 的客户端。 |
| O11 | P3 | 封面缓存与条件请求 | 扩展封面接口响应头 | 在正确鉴权和下线语义下评估 ETag、Last-Modified、304 与私有缓存，减少重复传输；先制定归档/权限变化后的缓存可见性策略。 |

O01继续执行认证版本校验，设计缓存时明确撤销生效窗口、旧值回填、TTL及故障策略。工程任务按实际配置、测试或环境验收证据完成登记。

## 3. 已完成区

### 3.1 已交付基础接口

| 分类 | 能力 | 实际接口与文档 |
| --- | --- | --- |
| 用户认证 | 注册及密码创建规则 | `POST /api/v1/auth/register`，[注册 API](../api/auth/register-user.md)。密码规则修复29d3f3e、37c294f已accepted，历史802/0/0/0 |
| 用户认证 | 用户名密码、邮箱密码、邮箱验证码登录及管理员角色选择 | `POST /api/v1/auth/login`，[登录 API](../api/auth/login.md) |
| 验证码 | REGISTER、LOGIN用途发码 | `POST /api/v1/email/send-code`，[发码 API](../api/email/send-code.md) |
| 用户管理 | 管理员修改用户角色 | `PATCH /api/v1/users/{id}/role`，[角色 API](../api/user/update-user-role.md) |
| 歌曲管理 | 音频导入并创建草稿 | `POST /api/v1/track-imports`，[导入 API](../api/track/import-track.md) |
| 歌曲管理 | 编辑草稿元数据 | `PATCH /api/v1/tracks/{trackId}/draft`，[草稿 API](../api/track/update-track-draft.md) |
| 歌曲管理 | 发布歌曲 | `POST /api/v1/tracks/{trackId}/publish`，[发布 API](../api/track/publish-track.md) |
| 歌曲管理 | 归档歌曲 | `POST /api/v1/tracks/{trackId}/archive`，[归档 API](../api/track/archive-track.md) |
| 歌曲管理 | 删除已归档歌曲 | `DELETE /api/v1/tracks/{trackId}`，[删除 API](../api/track/delete-track.md) |

### 3.2 已完成任务与历史验证

| 任务编号 | 状态 | 设计/计划 | API 文档 | 提交与审核结论 |
| --- | --- | --- | --- | --- |
| F02 取消收藏歌曲 | 已完成 | [设计](../superpowers/specs/2026-11-12-unfavorite-track-design.md) / [计划](../superpowers/plans/2026-11-12-unfavorite-track-plan.md) | [取消收藏API](../api/favorite/unfavorite-track.md) | `DELETE /api/v1/users/me/favorites/{trackId}`。实现cbdeb5c、6a6e036、a6b9a86，送审95893fe。自动独立实现第1轮accepted，185类2169/0/0/0，MySQL/Redis/MinIO实跑。本机Claude Code默认模型mimo-v2.6-pro，[审核与处置](../reviews/2026-11-12-unfavorite-track-review.md) |
| F01 收藏歌曲 | 已完成 | [设计](../superpowers/specs/2026-11-11-favorite-track-design.md) / [计划](../superpowers/plans/2026-11-12-favorite-track-plan.md) | [收藏 API](../api/favorite/favorite-track.md) | `PUT /api/v1/users/me/favorites/{trackId}`。实现cb38211、b90125f、845c5a0，送审98152c0。独立实现第1轮accepted，179类2097/0/0/0，MySQL/Redis/MinIO实跑。审核使用本机Claude Code默认模型mimo-v2.6-pro，[审核与处置](../reviews/2026-11-12-favorite-track-review.md) |
| 认证时间绑定（再次验证/邮箱变更前置） | 已完成 | [设计](../superpowers/specs/2026-11-02-auth-time-binding-design.md) / [计划](../superpowers/plans/2026-11-02-auth-time-binding-plan.md) | [登录 API](../api/auth/login.md) / [时钟与部署条件](../deployment/auth-time-binding.md) | 实现63a9d92、c4c93ef、5c4fe17；材料6708cf0；独立第1轮审核通过，零阻断/非阻断，999/0/0/0（88类、MinIO实跑）；设计NOTE代码侧关闭。交付 JWT auth_time 及登录/角色票原始认证时间继承 |
| U03 修改本人密码 | 已完成 | [设计](../superpowers/specs/2026-11-01-change-password-design.md) / [计划](../superpowers/plans/2026-11-02-change-password-plan.md) | [改密 API](../api/user/change-password.md) | 实现 e94942d、fbac5a0、af39dc5、c3ad118、eaf2c14；材料 af357c7；第1轮 accepted，零阻断/非阻断；独立 cleanTest check：925/0/0/0（86类，8分17秒）。当前及其他登录旧Token撤销 |
| 认证版本绑定（U03 前置） | 已完成 | [设计](../superpowers/specs/2026-11-01-auth-version-binding-design.md) / [计划](../superpowers/plans/2026-11-01-auth-version-binding-plan.md) | [登录 API](../api/auth/login.md) / [部署条件](../deployment/auth-version-binding.md) | 实现 5e8ceba；第 1 轮 accepted，零阻断/非阻断；独立 cleanTest check：767/0/0（81 类，8m 26s）；交付认证结果与版本绑定、失效校验及并发保护 |
| U02 修改个人资料 | 已完成 | [设计](../superpowers/specs/2026-11-01-update-current-user-design.md) / [计划](../superpowers/plans/2026-11-01-update-current-user-plan.md) | [修改资料 API](../api/user/update-current-user.md) | 实现提交 b1a826c；代码审核第 1 轮 accepted；独立 cleanTest check：658/0/0（7m 19s）；共享时间映射建议登记 O14 |
| U01 当前用户信息 | 已完成 | [设计](../superpowers/specs/2026-11-01-current-user-design.md) / [计划](../superpowers/plans/2026-11-01-current-user-plan.md) | [当前用户 API](../api/user/get-current-user.md) | 实现提交 1ce8b67；代码审核第 1 轮 accepted；独立 cleanTest check：551/0/0（7m 26s）；非阻断日志建议已补传异常堆栈 |
| T03 歌曲播放 | 已完成 | [设计](../superpowers/specs/2026-10-30-track-stream-design.md) / [计划](../superpowers/plans/2026-10-30-track-stream-plan.md) | [播放 API](../api/track/stream-track.md) | 代码审核第 1 轮 accepted；独立 cleanTest check：519/0/0（6m 54s）；实现提交 147c623 |
| T02 分页歌曲列表 | 已完成 | [设计](../superpowers/specs/2026-10-30-track-list-design.md) / [计划](../superpowers/plans/2026-10-30-track-list-plan.md) | [列表 API](../api/track/list-tracks.md) | afffbf5；代码审核第 1 轮 accepted；独立 cleanTest check：417/0/0 |
| T01 封面获取 | 已完成 | [设计](../superpowers/specs/2026-10-30-track-cover-design.md) / [计划](../superpowers/plans/2026-10-30-track-cover-plan.md) | [封面 API](../api/track/get-track-cover.md) | 79f06c9；代码审核第 1 轮 accepted；独立 cleanTest check：328/0/0 |
| 歌曲详情（已完成基线） | 已完成 | [设计](../superpowers/specs/2026-10-29-track-detail-design.md) / [计划](../superpowers/plans/2026-10-30-track-detail-plan.md) | [详情 API](../api/track/get-track-detail.md) | 58c5c48；代码审核第 1 轮 accepted；245/0/0 |
| U04 新邮箱发码申请 | 已完成 | [设计](../superpowers/specs/2026-11-03-email-change-request-design.md) / [计划](../superpowers/plans/2026-11-04-email-change-request-plan.md) | [申请 API](../api/user/request-email-change.md) | `POST /api/v1/users/me/email-change-requests`。第1轮accepted，独立全量1445/0/0/0。收尾定向53/0/0/0，[审核与处置](../reviews/2026-11-04-email-change-request-review.md) |
| U05 确认绑定或更换邮箱 | 已完成 | [设计](../superpowers/specs/2026-11-04-email-change-confirmation-design.md) / [计划](../superpowers/plans/2026-11-04-email-change-confirmation-plan.md) | [确认 API](../api/user/confirm-email-change.md) | 实际接口 `PUT /api/v1/users/me/email`。用户已提供审核至0705928的第1轮accepted报告，独立1599/0/0/0（126类，12分49秒）。原始材料见[审核记录](../reviews/2026-11-04-email-change-confirmation-review.md)，accepted依据为用户审核附件ad6520bd-2efd-4197-ab56-40615c42063a |
| U06 申请找回密码 | 已完成 | [设计](../superpowers/specs/2026-11-04-password-reset-request-design.md) / [计划](../superpowers/plans/2026-11-04-password-reset-request-plan.md) | [申请 API](../api/user/request-password-reset.md) | 实际接口 `POST /api/v1/users/password-reset-requests`。第1轮accepted，独立1718/0/0/0。收尾源码b564f85全量自验1718/0/0/0，[审核记录](../reviews/2026-11-04-password-reset-request-review.md) |
| U07 票据提交新密码及历史摘要兼容 | 已完成 | [三步提交设计](../superpowers/specs/2026-11-09-password-reset-completion-design.md) / [兼容修复计划](../superpowers/plans/2026-11-11-password-reset-legacy-hash-plan.md) | [提交 API](../api/user/confirm-password-reset.md) | 实际接口 `POST /api/v1/users/password-resets`，接收resetTicket/newPassword。三步切换第1轮accepted，1973/0/0/0。历史非空摘要迁移修复第1轮accepted，2034/0/0/0（172类，16分16秒），审核登记cb40d20，[三步审核](../reviews/2026-11-10-password-reset-completion-review.md) / [兼容修复审核](../reviews/2026-11-11-password-reset-legacy-hash-review.md) |
| U12 向当前邮箱发送再次验证码 | 已完成 | [设计](../superpowers/specs/2026-11-03-reauthentication-email-request-design.md) / [计划](../superpowers/plans/2026-11-03-reauthentication-email-request-plan.md) | [发送 API](../api/user/request-reauthentication-email.md) | `POST /api/v1/users/me/reauthentication-email-requests`。第1轮accepted，独立1105/0/0/0（96类），送审6fc1ddf，[审核记录](../reviews/2026-11-03-reauthentication-email-request-review.md) |
| U13 密码或邮箱码再次验证本人 | 已完成 | [设计](../superpowers/specs/2026-11-03-reauthentication-design.md) / [计划](../superpowers/plans/2026-11-03-reauthentication-plan.md) | [再次验证 API](../api/user/reauthenticate-current-user.md) | `POST /api/v1/users/me/reauthentications`。第1轮accepted，独立1285/0/0/0（103类），送审d595fef，[审核记录](../reviews/2026-11-03-reauthentication-review.md) |
| U14 独立校验找回验证码 | 已完成 | [设计](../superpowers/specs/2026-11-08-password-reset-verification-design.md) / [计划](../superpowers/plans/2026-11-08-password-reset-verification-plan.md) | [验证 API](../api/user/verify-password-reset.md) | `POST /api/v1/users/password-reset-verifications`，返回resetTicket/expiresAt。第1轮accepted，独立1926/0/0/0（168类），源码be2f091，[审核记录](../reviews/2026-11-08-password-reset-verification-review.md) |
| O02-A 已交付的专用验证用途与限流 | 已完成 | 对应U04—U07、U12—U14的设计与计划 | 对应专用验证API | 再次验证、邮箱变更、密码找回的主体/目标绑定、用途隔离、专用额度、期限、一次性消费及并发测试已随各接口独立accepted。剩余入口核验单列O02 |


## 4. 建议推进顺序

1. F01收藏歌曲和F02取消收藏已交付，音乐功能候选为T04模糊搜索及F03收藏列表，再推进P01—P06私人歌单。
2. 账户安全候选为U09全部退出，随后按会话需求设计U10当前退出及U11续期。
3. 浏览器播放接入核验O12，高频访问前推进O09性能基线及O01认证状态优化。
4. 在收藏与歌单基础上推进H01最近播放、H02进度恢复及L01歌词，再按需要扩展C01歌手/专辑、P07—P08分享及R01推荐。
5. 正式对外使用前完成O02剩余核验、O03备份恢复、O04部署验收及O05媒体补偿核验，持续保障O10真实集成测试。
6. 管理员与音乐库整理按实际运营需求择项推进。

## 5. 维护方式

选中任务时确认唯一Method + Path、权限、请求响应、错误、幂等、并发及测试边界，记录设计、计划与实际进度。新功能模块按单接口逐项交付。

独立实现审核accepted后，将对应条目从未完成区移入完成区，附实际接口、代码提交、审核结论与测试记录。模块部分交付时，将已交付部分独立编号保存于完成区，剩余范围在未完成区明确列出。

新路径、字段及业务规则在设计阶段确认。任务书中的收藏、歌单、历史、歌词及推荐等条目属于用户确认登记的需求库存，按选定任务分别开展设计和实现。
