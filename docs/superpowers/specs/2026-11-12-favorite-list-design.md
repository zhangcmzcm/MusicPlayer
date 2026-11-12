# F03 本人收藏列表接口设计

日期为2026-11-12，时区为Asia/Shanghai。

设计核对基线为 `main @ 2948527`，工作目录为 `D:\code\Java-code\projects\music`。设计开始时工作树干净，本地main与origin/main一致。F01收藏、F02取消收藏均已通过独立实现审核。

状态为用户已逐项确认业务选择、方案A及三段详细设计，本文完成设计自查，等待用户审阅书面设计。尚未开始实现或运行本接口测试。

## 1. 目标与范围

实现一个接口 `GET /api/v1/users/me/favorites`，分页展示当前登录用户可见的已收藏歌曲，支持状态筛选、歌曲字段排序及收藏时间排序。

本次仅设计F03。F01的PUT、F02的DELETE和现有 `GET /api/v1/tracks` 的外部行为保持不变。关键词搜索、其他用户收藏列表、不可用歌曲占位、收藏总量独立接口、游标分页、跨请求快照、列表缓存和专用限流不在范围内。

参考现有 [歌曲列表API](../../api/track/list-tracks.md)、[收藏API](../../api/favorite/favorite-track.md)、[取消收藏API](../../api/favorite/unfavorite-track.md) 与 [任务书](../../tasks/backend-roadmap.md)。

## 2. 方案比较与用户决定

| 方案 | 做法与取舍 | 决定 |
| --- | --- | --- |
| A，独立收藏查询 | 在收藏模块关联收藏表和歌曲表，数据库内完成归属、状态、排序、分页；复用统一分页响应与现有封面批量元信息查询。增加少量专用类型，保持既有歌曲查询稳定 | 用户选定 |
| B，统一列表查询组件 | 将歌曲列表和收藏列表改造为公共查询框架，减少部分重复，但扩大已有接口改动与回归范围 | 本次不采用 |

用户另行确认以下选择。

- USER仅显示已发布收藏，隐藏歌曲不计入总数，收藏关系继续保留；ADMIN可显示本人收藏的全部状态。物理删除歌曲后由已有外键级联移除收藏。
- 沿用page、size、sort、status，增加favoritedAt排序，默认最新收藏在前。
- 使用平铺歌曲列表项，增加favoritedAt字段，不嵌套track对象。

## 3. 认证、归属与状态权限

需要有效Bearer JWT。用户ID来自服务端验证后的 `AuthenticatedUserPrincipal.userId`，角色使用loginRole。USER与ADMIN均只能查询自己的收藏。

| 登录角色 | 未传status | PUBLISHED | DRAFT或ARCHIVED |
| --- | --- | --- | --- |
| 未认证 | 401 | 401 | 401 |
| USER，含上传者本人 | 仅PUBLISHED | 允许 | 403 FORBIDDEN |
| ADMIN | 全部状态 | 仅该状态 | 仅该状态 |

管理员以USER身份登录时采用USER规则。上传者身份不扩大可见范围。内容与统计始终使用完全相同的本人归属和状态条件，不暴露其他用户或隐藏歌曲的数量。

查询参数不接收userId、role等身份声明，作为未知参数返回400。自定义身份请求头不能覆盖认证主体。Service入口使用方法级认证保护，现有路由认证规则继续生效。

认证先于参数校验；所有查询参数合法后再判断状态权限。例如未认证且page非法返回401；已认证USER同时提交非法page和status=DRAFT返回400；所有参数合法且筛选DRAFT才返回403。

## 4. 查询参数

无业务请求体，不声明RequestBody，不从附带正文读取参数或身份，沿用歌曲列表处理方式。

| 参数 | 类型 | 默认值 | 约束 |
| --- | --- | --- | --- |
| page | Integer | 0 | 从0开始，非负 |
| size | Integer | 20 | 1至100，超限拒绝 |
| sort | String | favoritedAt,desc | 一个字段和一个方向 |
| status | String | 按登录角色决定 | 严格大写DRAFT、PUBLISHED、ARCHIVED之一 |

仅接受这四个参数。未知参数、重复参数（即使值相同）、空值、空白值、数字溢出和格式错误均为400 VALIDATION_FAILED。不接受q、status=ALL、多个状态或多个排序。

page和size沿用歌曲列表Integer.parseInt解析语义，不trim，不增加新的数字规范化规则。解码后的前导正号、前导零如可被现有解析器接受则同样接受；小数、数字两侧空格及超出Integer范围拒绝。

使用long运算检查 `page × size <= 10000`。page=100、size=100合法，page=101、size=100非法。超过实际结果页数但参数合法时返回正常空页。

排序字段如下。

| 字段 | 含义 |
| --- | --- |
| id | 歌曲ID |
| title | 歌曲标题 |
| artistName | 歌曲艺术家 |
| createdAt | 歌曲创建时间，保持歌曲列表含义 |
| favoritedAt | 本人当前收藏记录的首次收藏时间 |

方向严格为asc或desc，语法为 `字段,方向`，大小写敏感，不trim。非id排序追加同方向歌曲id作为次排序，默认实际为 `收藏created_at DESC, 歌曲id DESC`。字符串沿用MySQL既有utf8mb4_0900_ai_ci排序规则，不提供拼音排序。

排序列与方向通过服务端白名单映射，用户文本不能直接拼入SQL。用户ID、状态、分页偏移和页大小使用绑定参数。

## 5. 成功响应

200 OK，application/json，Cache-Control: no-store。复用 `PageResponse<FavoriteListItemResponse>`，专用列表项为record，不返回Entity。

```json
{
  "items": [
    {
      "id": 42,
      "title": "歌曲标题",
      "artistName": "艺术家",
      "albumName": "专辑名称",
      "durationMs": 245000,
      "status": "PUBLISHED",
      "cover": {
        "url": "/api/v1/tracks/42/cover",
        "contentType": "image/jpeg",
        "sizeBytes": 125600
      },
      "favoritedAt": "2026-11-12T08:30:00.123456Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

id、durationMs为Long，title、artistName、albumName、status为String。albumName、durationMs、cover缺失时明确输出null。cover的字段与歌曲列表一致，URL由歌曲ID生成，是已有同源鉴权封面地址。

favoritedAt为非空Instant，序列化为UTC ISO-8601。读取user_favorites.created_at的DATETIME(6)时明确按UTC解释，保留微秒精度，不依赖JVM默认时区，避免对已存UTC值再次进行本地时区偏移。

重复PUT保留首次收藏时间。取消后重新PUT使用新收藏记录的时间，排序随新时间变化。createdAt仅用于歌曲创建时间排序，不增加对应响应字段。

page、size为Integer，totalElements、totalPages为Long，hasNext为Boolean。空结果为items=[]、totalElements=0、totalPages=0、hasNext=false。越界页保留请求page/size与真实totalElements/totalPages，items=[]、hasNext=false。

totalPages使用商加余数计算向上整除，hasNext按long运算比较page+1与totalPages。不返回其他收藏审计字段、用户ID、原始ID3、资产ID、内部存储位置、摘要或凭据。

## 6. 查询、组件与事务

在favorite模块增加独立FavoriteListController、参数与排序类型、FavoriteListService、FavoriteListQueryService、FavoriteListRepository及查询投影和DTO。具体文件与测试拆分在实施计划中列明。

- Controller负责HTTP参数校验、调用Service、返回分页响应及no-store。
- 外层Service负责方法级认证、角色与状态判断，在事务代理外捕获DataAccessException和TransactionException，转换为已有FavoriteStoreUnavailableException。
- QueryService在 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` 短事务内读取页面、总数和封面，转换为DTO。
- Repository以本人user_id关联user_favorites与tracks，在数据库内筛选、排序、分页，投影只读取本页所需字段，不加载原始ID3或整张Entity。
- 复用PageResponse与MediaAssetRepository.findListCovers批量读取能力。封面规则与歌曲列表保持一致，本次不为通用框架重构已有列表查询。

最多三条业务SQL，分别为本页歌曲与收藏时间投影、同范围精确count、本页去重后的封面元信息批次。每页最多100条歌曲，封面批次最多100个资产ID。空页或无封面时跳过资产查询。认证链已有SQL另计。

禁止先拉取所有收藏后内存分页，禁止先分页再过滤隐藏状态，禁止逐条读取封面。内容与count均带user_id和相同状态限制。

### 快照与并发

三个读取步骤在同一MySQL REPEATABLE_READ事务的同一快照中执行，使用普通非锁定SELECT，不复用F01/F02的SELECT FOR UPDATE歌曲行锁。

快照建立后，即使另一个事务取消收藏、归档或物理删除歌曲，本次响应仍可返回快照内的行和计数；下次请求反映已提交变化。封面元信息也按该快照读取，防止同一响应因读时点不同产生内部矛盾。

单次响应的权限范围按已验证的登录角色及快照内歌曲状态确定。快照内返回的封面URL在后续独立请求时仍会重新鉴权，歌曲随后归档或删除可使该请求失败。

同值排序通过歌曲ID确定顺序；跨请求发生新增、取消或状态变化时，offset分页可能重复或遗漏移动的条目，本接口不提供跨页快照保证。

## 7. 迁移与性能边界

现有迁移实际为V1至V9，全部保持不变。新增 `src/main/resources/db/migration/V10__add_favorite_list_index.sql`，向user_favorites添加命名索引 `ix_user_favorites_user_created_track`，列为 `(user_id, created_at, track_id)`，服务本人收藏时间排序及同值次排序。

保留V9联合主键、track_id索引、两条级联外键及所有字段定义。不改变F01/F02写入和删除语义。索引增加写入与存储成本，部署需评估建索引空间及元数据锁影响。

其他排序结合既有歌曲索引与收藏联合主键，实际访问路径由MySQL优化器选择。实施时在真实MySQL 8.4上对默认排序、反向排序、其他排序与有无状态过滤收集执行计划，使用有代表性的收藏量及可见状态分布，记录扫描、排序与分页成本。

本人收藏范围、页大小和偏移均受约束，但精确count成本仍随匹配数据增长，部分排序和状态筛选仍可能扫描或排序较多候选行。不得把索引存在或offset限制表述为恒定成本，不预先承诺固定延迟SLA。

本次不增加依赖、Redis缓存、专用限流或对象存储访问。若执行计划暴露需改变已确认契约或扩大索引范围的问题，说明证据与方案后再决策。

## 8. 封面语义

沿用歌曲列表的元信息校验，封面引用为空时返回null。共享封面资产每页去重批量查询一次，各歌曲仍使用自己的封面URL。

本页引用的资产记录缺失、kind不是COVER、MIME不在JPEG/PNG/WebP范围、大小非正数、摘要格式非法、bucket或objectKey为空时，整页500 INTERNAL_ERROR，不静默改为null。内部校验字段不出现在响应中。

其他页、其他用户或不可见歌曲的损坏封面不影响本页。列表不发送MinIO HEAD/GET；数据库元信息有效但实际存储对象丢失时，列表仍返回元信息，随后调用封面接口按其已有契约处理。

## 9. 错误、缓存与日志

| HTTP | 稳定code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 查询参数非法，包括未知、重复、空值、格式、范围和深分页 |
| 401 | UNAUTHORIZED | 未提供认证 |
| 401 | INVALID_TOKEN | 认证必要声明无效 |
| 401 | TOKEN_REVOKED | 用户不存在、token version失效或登录角色失效 |
| 403 | FORBIDDEN | USER显式筛选DRAFT或ARCHIVED |
| 500 | INTERNAL_ERROR | 本页封面一致性故障或其他未预期异常 |
| 503 | FAVORITE_STORE_UNAVAILABLE | 收藏列表内容、count、封面元信息查询或事务边界故障 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态存储故障 |

业务错误使用application/problem+json，保留code、instance及既有 `urn:problem:music:*` 类型规则。集合无结果不返回404，本接口没有对象存储502分支。

收藏存储故障复用现有异常与映射，type为 `urn:problem:music:favorite_store_unavailable`，title为“收藏存储不可用”，detail沿用“歌曲收藏暂时无法完成”。不为本次读取改变F01/F02共享错误契约。

新增收藏列表专用参数异常，使用稳定400错误码，固定安全detail为“收藏列表查询参数无效”。通过统一异常处理及限于本集合路径的响应处理确保业务错误no-store，避免改变其他接口响应。成功与业务ProblemDetail均不生成ETag，不使用服务端列表缓存。

JWT签名、过期、issuer/audience等解码失败沿用资源服务器401与WWW-Authenticate，部分没有业务ProblemDetail；认证错误头沿用既有组件。日志使用SLF4J和现有traceId，不记录Token、正文、存储凭据或原始敏感信息。

事务开始及结束阶段失败也必须由外层Service转换；不得返回部分items或伪造空页来掩盖数据库错误。全局未匹配路由治理继续作为独立事项，不在本次扩展。

## 10. 验证与验收

### 参数与HTTP契约

- 默认参数、上下界、溢出、空值、空白、未知、重复参数及page×size边界，保留与歌曲列表相同的数字解析行为。
- 全部五个排序字段的升降序，非法字段、方向、格式与多排序拒绝。
- 未认证先于参数校验，参数校验先于状态权限；真实JWT失效和角色切换行为。
- 200分页结构、显式null、favoritedAt格式、错误媒体类型、code及缓存头。
- OpenAPI参数、默认值、错误状态、收藏列表DTO与响应泛型准确，无请求体声明。

### MySQL归属、排序与时间

- USER、ADMIN及管理员以USER登录的状态矩阵，上传者也不能额外读取隐藏歌曲。
- 多用户收藏相同歌曲、同一用户收藏多状态歌曲，验证归属、状态、items与count全部一致，伪造参数或头无法扩大范围。
- 隐藏收藏不占页内名额、无收藏、全部隐藏、第一页、末页、越界空页及精确totalPages/hasNext。
- 五类排序及同值ID次排序，歌曲创建时间与收藏时间刻意不一致，验证createdAt/favoritedAt无混淆。
- UTC微秒时间读取，非UTC JVM默认时区回归，重复PUT时间不变，取消再收藏采用新时间并影响默认排序。
- Flyway从已有版本升级、索引列顺序、旧迁移不变，记录真实执行计划及成本边界。

### 一致性、封面与故障

- 使用可控同步点建立读取快照，再由独立真实事务分别取消、归档、删除，验证同一次响应的内容、count和封面元信息一致，下次读取反映新状态。
- 验证普通列表读取不会主动持有阻塞F01/F02所需的歌曲排他行锁；并发测试采用有界等待并可靠清理。
- 有封面、无封面、共享封面、本页损坏、其他页损坏、隐藏歌曲损坏；验证最多三条业务SQL及批量查询，不访问对象存储。
- 实际对象缺失但元信息合法时列表仍成功；测试与原封面读取契约一致。
- 内容、count、封面查询故障及真实Spring代理事务开始/结束失败，验证503与原异常保留；一致性故障为500，不误归类为503。
- 保留F01、F02及既有歌曲列表测试，运行相关回归，不通过删断言或跳过容器达成通过。

### 完整验证与独立审核

设置 `MINIO_INTEGRATION_TESTS=true` 后运行 `.\gradlew.bat cleanTest check --console=plain`。MySQL、Redis与MinIO相关必要测试实际运行，汇报套件数、通过、失败、错误、跳过数量，不能将skipped当作通过。

完成 `docs/api/favorite/list-favorites.md`，包含认证、全部参数、表示、错误、并发与成本边界、可执行curl占位符示例，补齐OpenAPI。

实现与验证完成后按AGENTS第2节准备单接口审核材料，自动调用Claude Code只读独立审核，记录实际模型、会话、Git范围与独立测试证据。收到反馈按原编号复核事实、分级与方向，最多三轮；accepted后将F03移入任务书已完成区并更新项目状态。

## 11. 设计自查与下一步

已检查范围仅为一个GET接口，参数与现有歌曲列表一致，createdAt与favoritedAt语义区分清楚，USER隐藏歌曲的内容和计数一致，ADMIN范围仍限定本人。快照语义、错误码、封面故障、事务边界与性能限制相互一致。

新增V10只建索引，不修改已执行迁移；独立组件方案不要求横向重构。未引入占位实现、未决关键业务或未经确认的新接口。本文仅为设计，测试清单不代表已执行结果。

用户审阅本设计后，使用writing-plans编写中文实施计划，再按已授权阶段继续。本次设计提交不包含业务代码、迁移文件或API实现。
