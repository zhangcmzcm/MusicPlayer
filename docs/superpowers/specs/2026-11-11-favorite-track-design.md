# F01 收藏歌曲接口设计

日期为2026-11-11，时区为Asia/Shanghai。

状态为用户于2026-11-12确认书面设计并进入writing-plans编写实施计划。

独立设计审核第1/3轮结论为accepted，审核基线为08c78bb，零阻断项。NOTE-001与NOTE-002的复核及处置见[设计审核记录](../../reviews/2026-11-12-favorite-track-design-review.md)。

设计核对基线为 `main @ cb40d20`。工作树已有任务书修改和未跟踪的 `PROJECT_STATE.md`，恢复来源为交接区 `PH-20261111-favorites-01`。

## 1. 目标与已确认契约

当前登录用户将指定歌曲加入本人的收藏。

| 项目 | 已确认规则 |
| --- | --- |
| HTTP 接口 | `PUT /api/v1/users/me/favorites/{trackId}` |
| 身份来源 | 服务端验证后的 `AuthenticatedUserPrincipal` |
| 请求体 | 无 |
| 歌曲标识 | 正整数 Long，范围为1至9223372036854775807 |
| 首次收藏 | 204，无响应体 |
| 重复收藏 | 204，无响应体，保留首次收藏时间 |
| 唯一性 | 每个用户与歌曲至多一条收藏关系 |
| 收藏数量 | 首版不设每用户业务上限 |
| 专用限流 | 首版暂不增加收藏专用限流 |
| 歌曲归档 | 保留已有收藏关系 |
| 歌曲彻底删除 | 在数据库删除事务内级联清除对应收藏关系 |
| 用户记录删除 | 在数据库删除事务内级联清除其收藏关系 |

接口路径方案比较已经完成。选定的 PUT 以用户与歌曲共同确定收藏资源，明确表达设为已收藏，客户端可以使用同一请求安全重试。另一种 POST 集合接口需要通过请求体传递 trackId，并由业务规则赋予幂等行为。

## 2. 权限与可见性

需要有效 Bearer JWT，复用既有签名、issuer、audience、有效期、token version、账户与登录角色校验。

| 当前登录角色 | DRAFT | PUBLISHED | ARCHIVED |
| --- | --- | --- | --- |
| USER | 404 TRACK_NOT_FOUND | 允许 | 404 TRACK_NOT_FOUND |
| ADMIN | 允许 | 允许 | 允许 |

管理员以 USER 角色登录时按 USER 规则处理。收藏用户只能取自认证主体的 Integer userId，角色只能取自 loginRole。

每次请求均先核对歌曲存在性及当前可见性，再执行幂等写入。已有收藏不能绕过权限校验。USER 已收藏歌曲归档后，再次收藏返回404，原收藏关系保留。ADMIN 可收藏仍然存在的归档歌曲。

Service 入口使用 `@PreAuthorize("isAuthenticated()")`。现有 SecurityConfig 的默认认证规则覆盖此路由。

## 3. HTTP 校验与响应

路径参数使用 Spring MVC 类型转换及 `@Positive` 等价校验。非整数、零、负数和 Long 溢出返回400 VALIDATION_FAILED。缺少路径段按现有路由行为处理。

本接口无业务查询参数。技术默认沿用现有本人资料入口，未知查询参数忽略，用户 ID、角色等查询参数和自定义请求头均不能参与身份判定。Controller 无请求体绑定，附带的请求体不参与业务处理。该默认项已随书面设计获确认。

认证过滤链先执行，已认证请求进入参数校验，参数合法后检查歌曲可见性。成功响应统一携带 `Cache-Control: no-store`，204响应无正文。

| HTTP | 稳定 code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 路径歌曲 ID 非法 |
| 401 | UNAUTHORIZED | 未提供认证 |
| 401 | INVALID_TOKEN | 认证必要声明无效，沿用既有认证契约 |
| 401 | TOKEN_REVOKED | 账户不存在、版本失效或登录角色失效，沿用既有认证契约 |
| 404 | TRACK_NOT_FOUND | 歌曲不存在或当前登录角色不可见 |
| 503 | FAVORITE_STORE_UNAVAILABLE | 收藏业务数据库读取、写入或事务故障，包括锁等待超时和死锁 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 既有认证状态存储故障 |
| 500 | INTERNAL_ERROR | 未预期的内部错误，沿用统一处理 |

业务错误使用 `application/problem+json`、稳定 code 及 `urn:problem:music:*`。新增存储故障对应 `urn:problem:music:favorite_store_unavailable`。复用现有404和400映射，新增收藏存储故障异常及统一映射。

JWT 解码失败沿用资源服务器现有401及 WWW-Authenticate行为。日志沿用SLF4J和traceId，响应通过统一异常处理屏蔽底层SQL与异常细节。

## 4. 数据结构与迁移

新建 `user_favorites` 表，新增迁移 `V9__create_user_favorites.sql`。实施前再次核对迁移目录中的下一个版本号。

| 列 | MySQL 类型 | 约束与含义 |
| --- | --- | --- |
| user_id | INT | 非空，收藏所属用户 |
| track_id | BIGINT | 非空，收藏歌曲 |
| created_at | DATETIME(6) | 非空，首次收藏的UTC时间 |
| updated_at | DATETIME(6) | 非空，首次插入时与created_at相同 |
| created_by | INT | 非空，首次插入时取当前用户ID |
| updated_by | INT | 非空，首次插入时取当前用户ID |

联合主键为 `(user_id, track_id)`，同时满足本人定向查找和并发唯一性。为 track_id 建立显式索引，服务歌曲外键与级联清理。

外键集合固定为两条，user_id指向users.id，track_id指向tracks.id，两者均采用ON DELETE CASCADE。created_by与updated_by使用普通INT审计列，由服务端在首次写入时赋为user_id，并由重复请求保持原值。此策略明确了NOTE-001中的审计列约束边界，用户记录删除由user_id外键完成级联。真实MySQL测试核验外键集合及用户删除结果。

数据库时间采用既有UTC约定，由UTC Clock生成，写入DATETIME(6)前统一到微秒精度。实施验证发现Hibernate原生LocalDateTime绑定受JVM默认时区影响，故以固定格式UTC文本参数配合MySQL CAST为DATETIME(6)写入，保留非UTC默认时区回归测试。本接口成功响应无时间字段。后续收藏读取接口的时间表示在其设计中使用Instant和ISO-8601。

## 5. 事务与并发实现方案

已确认的技术默认采用歌曲行的排他锁读，即按主键读取当前status并执行 `SELECT ... FOR UPDATE`。该锁使同一歌曲上的收藏写入串行执行，并与既有归档UPDATE、发布UPDATE和歌曲DELETE协调。不同歌曲可并行处理。

这一实现的代价是热门歌曲上的不同用户收藏也需要等待同一行锁。锁内仅执行状态权限判断与单条收藏写入，事务保持简短。该方案已随书面设计获确认。

事务步骤如下。

1. 以当前歌曲ID执行主键锁定读，取得最新已提交状态。不存在时抛出既有TrackNotFoundException。
2. 根据当前登录角色核对歌曲状态，不可见时返回同一404。
3. 使用MySQL原子插入及重复键原值更新语义写入收藏。重复时仅执行不改变值的更新，保留所有审计字段。实现可使用 `ON DUPLICATE KEY UPDATE`，正常路径不依赖捕获唯一约束异常。
4. 完成事务提交，外层Service返回后Controller构造204。

Repository采用现有EntityManager原生SQL方式读取状态和写入收藏，参数化绑定用户ID、歌曲ID及审计值。接口不需要返回持久化对象。

Service与事务Service通过Spring代理分层调用，外层Service统一捕获DataAccessException和TransactionException，包括事务开启、flush及提交阶段故障。Controller只接触HTTP参数和Service结果。

出现死锁或锁等待超时时回滚并返回503，由客户端重试同一幂等请求。提交结果不确定时也返回503，重试会重新检查权限及唯一关系。

### 并发归档

收藏先取得锁并提交时，归档随后完成，收藏关系保留。归档先提交时，收藏锁定读取得ARCHIVED，USER返回404，ADMIN允许收藏。归档事务的既有版本更新规则继续生效。

### 并发删除

收藏先提交时，后续歌曲DELETE在同一数据库事务内级联清除收藏。歌曲DELETE先提交时，收藏锁定读返回不存在。用户删除由用户外键保证收藏关系一致性，外键写入失败按存储故障处理。

既有歌曲删除流程先处理媒体对象再删除数据库记录。F01按数据库歌曲记录及状态判断可收藏性，ADMIN可能在数据库删除完成前短暂收藏该归档记录，随后由数据库级联清除。收藏操作无需访问媒体对象。

## 6. 组件与变更范围

预计实现文件分布如下，具体文件清单由实施计划细化。

| 位置 | 职责 |
| --- | --- |
| favorite/controller | 收藏PUT入口、路径校验、204与OpenAPI |
| favorite/service | 当前角色可见性规则、事务协调、存储故障转换 |
| favorite/repository | 歌曲主键锁定读、收藏原子幂等写入 |
| favorite/exception | 收藏存储不可用异常 |
| common/exception/GlobalExceptionHandler.java | 新增收藏503映射 |
| db/migration/V9__create_user_favorites.sql | 收藏表、主键、索引和级联外键 |
| src/test/java/org/example/music/favorite | HTTP、Service及真实数据库集成验证 |
| docs/api/favorite/favorite-track.md | 中文API文档及可执行curl |

既有认证链、Principal、TrackNotFoundException和统一校验映射直接复用。数据库级联承担歌曲删除后的收藏清理。使用当前依赖中的JPA、Validation、安全框架和MySQL驱动完成实现。

## 7. 测试与验收

### HTTP与安全

- USER收藏PUBLISHED、ADMIN收藏三种状态，首次和重复均为204且无正文，带no-store。
- 管理员以USER登录遵循USER规则，上传者本人也不能以USER收藏DRAFT或ARCHIVED。
- 正整数边界、零、负数、非整数、Long溢出及存在格式合法但无对应记录的ID。
- 无认证、错误或过期JWT、失效版本与角色失效，真实签名JWT经过现有SecurityConfig和认证状态过滤器。
- 伪造查询用户ID、角色、自定义头或请求体均不能选择他人的收藏身份，断言其他用户收藏不受影响。
- 400、404、503的ProblemDetail媒体类型、code、type、缓存头及错误详情脱敏。
- NOTE-002对应真实路由测试，分别请求 `PUT /api/v1/users/me/favorites`、`PUT /api/v1/users/me/favorites/`、`PUT /api/v1/users/me/favorites/42/`，记录已认证与未认证时的实际状态码、响应格式及路由处理器，并断言收藏业务Service未调用。API文档按实测结果说明这些路径行为。

### 持久化与并发

- Testcontainers MySQL 8.4执行完整Flyway迁移链，核对联合主键、外键和索引。
- 首次插入所有审计字段正确，重复请求保持原有时间和全部字段，跨角色重复收藏仍属于同一用户。
- 同一用户同一歌曲并发请求全部正常成功且只保留一条记录，不同用户可分别收藏同一歌曲。
- 使用可控锁与事务同步点验证收藏先提交、归档先提交和删除先提交等顺序，断言HTTP结果与数据库最终关系一致。
- 歌曲归档后收藏保留，实际歌曲DELETE级联清理所有对应关系，其他歌曲收藏保持正确。
- 通过现有删除Service的完整入口验证级联行为，涉及媒体清理时使用真实MinIO。
- 用户删除的数据库级联行为使用独立测试用户验证。
- 事务开启、读取、写入、锁等待、提交故障映射503，故障时数据库状态保持一致，重试仍符合幂等约定。
- 正常持久化与并发场景使用真实数据库，故障注入限于验证异常分支。

完整验证使用以下PowerShell命令。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat check
```

需要强制重跑测试时执行 `.\gradlew.bat cleanTest check --console=plain`，同时保留上述环境变量。根据XML报告记录实际通过、失败、错误与跳过数量，逐项列出跳过原因。必要MySQL、Redis及MinIO集成测试需实际执行。

完成后按AGENTS.md准备唯一Method与Path、需求权限、声称行为、全部变更文件、迁移、API文档、curl、基线及测试证据，交由Claude独立只读审核。首次审核反馈处置记为“不涉及”，后续沿用原编号与轮次核实处置。

## 8. curl示例

调用者预先通过已有登录流程设置ACCESS_TOKEN，示例歌曲ID为123。

```sh
curl --request PUT 'http://localhost:8080/api/v1/users/me/favorites/123' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```

成功返回 `HTTP 204`，响应体为空。相同权限与歌曲状态下重复执行返回同一成功状态。

## 9. 设计自检与下一步

已核对单接口边界、当前登录角色、重复收藏的重新鉴权、首次时间保留、联合主键、数据库级联、锁取得顺序、事务提交故障及真实容器验证范围。

已随书面设计确认的技术默认包括未知查询参数及附带请求体不参与业务、歌曲行排他锁读、原生SQL原子幂等写入和统一no-store响应头。

实施计划已由用户确认，当前进入实现与完整验证阶段，后续提交Claude独立实现审核。
