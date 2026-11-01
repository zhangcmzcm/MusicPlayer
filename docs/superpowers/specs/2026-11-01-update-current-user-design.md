# 修改个人资料接口设计

日期：2026-11-01。

状态：用户已审阅书面设计，要求补充限流决策后确认并进入 writing-plans（2026-11-01）；本文件已补充该项。

基线：main @ 8b69edb。开始设计时 `.gitignore` 已有用户未提交修改，不属于本次范围。

## 1. 目标与范围

本次仅设计 `PATCH /api/v1/users/me`（U02），当前登录用户修改自己的 username。需要有效认证，USER 和 ADMIN 登录身份均可操作本人。

邮箱、密码、角色、用户 ID、tokenVersion、审计时间不得通过本接口修改。不新增昵称、头像、简介、管理员修改他人资料或其他账户接口。

不新增依赖、数据库字段或迁移，不修改已执行的 V1—V8；不调整全局 JSON 配置、共享认证主体、注册契约、GET 当前用户资料接口的契约或错误文案。

本次不新增用户名修改专用限流或改名冷却期，继续要求有效认证；不将认证校验等同于写操作限流，也不假定部署层已配置此类限流。如后续需要限制改名频率，另行设计规则与实现，本接口当前不新增 429 业务分支。

## 2. 已核对的项目事实

- AuthenticatedUserPrincipal 包含 Integer userId 和 UserRole loginRole；账户角色与本次登录角色可能不同。
- 注册校验 username 为 3—20 位 ASCII 英文字母、数字或下划线；空白字符不通过入口校验。数据库 username 列容量为 80，本接口仍采用注册入口的 3—20 位规则，不扩展新输入。
- users 的 username 唯一索引为 ix_users_username，表排序规则 utf8mb4_0900_ai_ci，用户名匹配不区分大小写。
- JWT subject 绑定用户 ID，认证过滤器每次查询账户角色与 token version，不以旧用户名识别本人。用户名密码登录按数据库查找当前用户名；邮箱登录不依赖用户名。
- 已有 CurrentUserResponse、UserProfileSnapshot、findProfileSnapshot 和 CurrentUserRevokedException 可复用。GET 查询已通过文本读取 UTC DATETIME，避免 JDBC Calendar 与 JVM 时区组合产生偏移。
- ProblemDetail.type 由 code.toLowerCase() 构造，保留 code 中的下划线。

## 3. 方案比较与选择

| 方案 | 收益 | 代价 |
| --- | --- | --- |
| 按本人 ID 定向更新，唯一索引兜底（选定） | 无结构改动；仅修改白名单列，避免整份 Entity 保存覆盖安全字段 | 同一用户并发改名按数据库执行顺序生效，不检测旧页面版本 |
| 新增资料版本号，客户端提交版本 | 可用 409 拒绝旧页面覆盖新资料 | 需要迁移及读取接口提供版本号，超出本次已确认范围 |

不接受客户端提供旧用户名、用户 ID 或版本替代身份，不新增乐观锁字段或强制 If-Match。后执行的成功更新可覆盖前一次，不提供跨请求快照或幂等键。

## 4. HTTP 请求与严格字段边界

- Method：PATCH。
- 完整 Path：`/api/v1/users/me`。
- Content-Type：`application/json`，普通 JSON 对象，不提供 JSON Patch 或 JSON Merge Patch 操作协议。
- 需要有效 Bearer JWT，沿用签名、issuer、audience、有效期、token version 及登录角色校验。
- 无路径用户 ID，无业务查询参数。未知查询参数忽略，不参与身份或更新内容；自定义请求头同样不能决定用户身份。

仅允许以下请求：

```json
{
  "username": "alice_new"
}
```

| 字段 | 类型 | 要求 |
| --- | --- | --- |
| username | string | 必须存在且非 null；3—20 位；仅 A—Z、a—z、0—9、下划线 |

保留输入大小写，不自动转小写、不自动删除首尾空格。任何空白字符均不符合允许字符规则，包括首尾空格、制表符和换行。

以下情况返回 400 VALIDATION_FAILED，且不执行修改：

- 缺少请求体、非法 JSON、JSON null、数组、字符串或其他非对象顶层类型。
- 空对象、缺少 username、username 为 null、空串、空白串、非字符串或长度/字符不合法。
- 将数字或布尔值隐式转为字符串的输入；例如数字 123 必须拒绝，字符串 "123" 合法。
- 重复 username 键，即使两个值相同；不能解析后取最后一个值。
- 任意额外 JSON 字段，包括 email、password、passwordHash、role、userRole、loginRole、id、userId、tokenVersion、updatedAt 及其他未知名称；值为 null 也不例外。
- username 的大小写拼写错误，例如 Username，视为未知字段。

请求局部绑定逻辑负责保留重复键检测并拒绝未知字段和类型强制转换；后续 Bean Validation 验证字段约束。不得为实现这些规则修改全局 ObjectMapper，或影响注册、登录、其他 PATCH 接口。

不支持的媒体类型按现有 Spring MVC 行为拒绝，不扩大本次范围去统一全项目协议错误。认证链先于请求体业务校验；未认证请求仍按现有安全链处理。

## 5. 用户名相等与唯一性

- 对其他账户：唯一性沿用数据库排序规则；别人已占用 Alice 时，不能改为 alice，返回 409 USERNAME_ALREADY_EXISTS。
- 对本人：Alice 改为 alice 允许，视为实际修改，保存请求中的大小写并更新 updated_at。
- 新旧用户名逐字节完全相同时为成功的无变化请求，返回 200，不更新时间。
- 不允许清空用户名或通过省略 username 表达无变化请求。
- 不以预查询“用户名未占用”保证唯一性；最终竞争由 ix_users_username 唯一索引解决。

若历史用户名不符合当前 3—20 位规则，仍可通过本接口改为合法新值；提交不合法的旧值不能绕过入口校验。历史字段不迁移、不截断、不自动重写。

## 6. 身份与成功响应

Controller 只从经过现有认证链生成的 AuthenticatedUserPrincipal 取得本人 ID 和登录角色。Service 以主体 userId 为唯一更新目标，不能从 JSON、查询参数或请求头取得目标 ID。

成功返回 200 OK、application/json、Cache-Control: no-store。复用 CurrentUserResponse 的六字段白名单，不返回 JPA Entity：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| id | integer | 本人 ID，保持 Integer |
| username | string | 本次事务更新后读取的用户名，保留大小写 |
| email | string 或 null | 本人完整邮箱；空值保留字段，不修改 |
| userRole | USER / ADMIN | 数据库中的账户角色，不修改 |
| loginRole | USER / ADMIN | 本次已验证的登录身份，不提升权限 |
| createdAt | UTC ISO-8601 string 或 null | 原账户创建时间，保留微秒和显式 null，不修改 |

示例：管理员以 USER 身份修改本人用户名。

```json
{
  "id": 42,
  "username": "alice_new",
  "email": "alice@example.com",
  "userRole": "ADMIN",
  "loginRole": "USER",
  "createdAt": "2026-10-21T12:34:56Z"
}
```

不返回 passwordHash、password_token、tokenVersion、Token、验证码、updatedAt、lastLoginAt 等未列入白名单的字段。不为本次写接口扩展 GET 响应。

## 7. 组件、事务与并发

1. 现有安全链验证 JWT 和认证状态，生成认证主体。
2. 独立的资料修改 Controller 负责 HTTP、局部请求绑定、@Valid、主体注入、调用 Service 和成功响应缓存头，不写业务逻辑。
3. 专用资料修改 Service 开启短写事务，按主体 ID 执行一次定向 UPDATE，只允许赋值 username、updated_at；数据库侧使用 UTC 当前时间，精度保留到微秒。
4. UPDATE 条件对新旧用户名采用二进制精确比较，避开大小写不敏感排序规则，保证仅大小写变化能更新、完全相同不会更新时间。
5. UPDATE 之后，在同一事务内调用现有 findProfileSnapshot，读取允许展示的数据库字段并与主体 loginRole 组装 DTO。正常路径不在 UPDATE 之前进行资料快照查询，避免先建立旧的一致性读快照。
6. 事务提交确认成功后，Service 才向 Controller 返回成功结果。Controller 不接触 Entity；事务内不调用 Redis、邮件或对象存储。

UPDATE 返回 0 不直接等同于账户不存在，因为完全相同的用户名也不更新。之后投影有结果即成功；投影无结果时使用 CurrentUserRevokedException，映射 401 TOKEN_REVOKED。UPDATE 返回 1 时仍须在事务内完成投影读取，不在事务外调用 GET Service 重新查询。

同一用户的并发更新由数据库行锁与语句执行顺序串行化，后执行的成功写入生效；不引入版本冲突 409。不同用户竞争同一个新用户名时，由唯一索引保证最多一个成功，另一请求返回 409；不通过应用内锁代替数据库保护。

仅在这条定向用户名 UPDATE 的故障链中确认 MySQL 重复键错误（errorCode 1062、SQLState 23000）时，映射 USERNAME_ALREADY_EXISTS。该语句不修改主键或其他唯一列，当前结构中其唯一冲突来源为 ix_users_username。不得将任意 DataIntegrityViolationException 或其他事务异常泛化为“用户名重复”。

事务边界必须覆盖 UPDATE、投影读取及 DTO 组装；投影失败或组装异常时回滚未提交修改。数据库、事务启动与提交故障在事务外层翻译；不得捕获异常后继续在已失败事务中查询并返回成功。死锁或锁等待超时不自动重试，返回可重试的存储不可用错误。

提交确认阶段连接中断时，服务端可能不能判断数据库是否已经提交，因此不承诺所有提交异常都意味着修改肯定未生效。返回 503，不伪造成功；调用方可重新 GET 确认，或重复提交目标用户名。当前值与请求完全相同的重复提交不更新时间；有其他并发改名插入其间时，后续提交仍按普通更新处理。

认证状态校验和资料修改事务是两个阶段，不是原子快照。并发角色变更通过既有 token version 机制使后续请求失效，不承诺中止所有已进入业务层的请求，也不利用该接口更新或恢复认证版本。

## 8. 改名后的认证行为

- 不递增 tokenVersion，不撤销已有 Token；身份仍绑定用户 ID。
- 后续 GET /api/v1/users/me 返回新的数据库用户名。
- 用户名密码登录按当前数据库用户名匹配；仅大小写变化时，大小写不敏感的原拼写依然能匹配。
- 实际改成不同名称后，旧用户名不再指向本人；若后来被其他账户占用，则按该账户处理，不保留旧用户名别名或占用记录。
- 邮箱密码、邮箱验证码登录不因用户名变化而改变规则。
- 不绕过现有登录限流或修改登录响应、认证主体、角色选择和撤销机制。

## 9. 异常与错误响应

| 场景 | HTTP / code | 处理 |
| --- | --- | --- |
| 请求字段、类型、重复键或 JSON 非法 | 400 VALIDATION_FAILED | 请求局部校验，不执行写入 |
| 未认证 | 401 UNAUTHORIZED | 沿用现有安全入口 |
| JWT 必需声明非法 | 401 INVALID_TOKEN | 沿用现有声明校验 |
| 版本撤销、登录角色不再允许、账户不存在 | 401 TOKEN_REVOKED | 沿用认证链；事务中账户消失也返回同码 |
| 用户名已占用或并发唯一键冲突 | 409 USERNAME_ALREADY_EXISTS | 复用既有稳定错误码与统一映射，不暴露占用者身份 |
| 认证状态查询失败 | 503 AUTH_STATE_STORE_UNAVAILABLE | 沿用既有处理 |
| 修改、事务或投影读取发生存储故障 | 503 USER_PROFILE_UPDATE_UNAVAILABLE | 新增写接口专用异常和统一处理 |
| 未预期错误 | 500 INTERNAL_ERROR | 沿用统一异常处理 |

业务错误使用 application/problem+json 和现有缓存、安全头约定；401/409/503 等响应不得缓存。type 使用现有生成规则，例如 urn:problem:music:username_already_exists、urn:problem:music:token_revoked、urn:problem:music:user_profile_update_unavailable。

不复用 GET 的 USER_PROFILE_STORE_UNAVAILABLE 文案处理写失败，不调整 GET 错误契约。JWT 签名错误、过期等资源服务器错误仍沿用既有 401 与 WWW-Authenticate 行为，不承诺全部有 ProblemDetail 响应体。

无需正常已认证身份的 403 分支，无单独的目标资源 404 分支，不凭空增加权限或资源枚举错误。

关键故障按现有 SLF4J、traceId、exceptionType 与 throwable 约定记录。不记录密码、Token、验证码、完整请求体或邮箱，不向响应泄漏 SQL、内部路径、底层异常或其他用户资料。

## 10. 预计文件与文档范围

- user/controller：新增 PATCH 当前用户资料入口，不改 GET 路由契约。
- user/dto：新增仅 username 的请求 DTO、Schema 及本接口局部严格 JSON 绑定；复用 CurrentUserResponse。
- user/service：新增资料修改 Service 与必要的事务边界、错误翻译和响应组装。
- user/repository/UserRepository：新增定向用户名更新；复用现有 findProfileSnapshot。
- user/exception：新增资料更新存储故障异常；必要的局部请求校验异常统一走 400。
- common/exception/GlobalExceptionHandler：仅增加本接口所需映射；复用现有 TOKEN_REVOKED、USERNAME_ALREADY_EXISTS 处理。
- src/test/java/org/example/music/user：新增 HTTP、Service 和真实数据库/认证链集成测试。
- docs/api/user/update-current-user.md：中文 API 文档，含严格请求规则、唯一性、角色、错误、并发边界和可执行 curl。
- docs/superpowers/plans、docs/reviews：后续实现计划和单接口审核材料；完成后按既有方式登记 U02。

实现不需要 UserEntity 新增用户名 setter，也不需要保存整份 Entity。不会引入迁移、依赖、全局 JSON 定制、全项目 ID 调整或其他模块重构。

## 11. 测试与验收

### HTTP 契约与请求绑定

- 成功返回 200、JSON 严格六键、正确两角色、UTC 时间、显式 null 和 no-store；不返回内部字段。
- username 长度 3/20 合法，2/21 拒绝；仅数字、仅下划线在长度合法时允许，与注册规则一致。
- 缺失请求体、空对象、null、空串、空白、前后空格、中文、非法符号、数组、对象、数字、布尔值、重复键和未知字段均覆盖；确认无隐式字符串转换、无静默忽略敏感字段。
- 未认证先被拒绝；USER 与 ADMIN 两种登录身份均可操作本人。
- 额外查询参数、伪造用户 ID/角色头不能选择其他账户；JSON 中伪造字段直接 400，不执行写入。
- 400/401/409/503 的 code、type、媒体类型与响应缓存头符合契约；底层故障细节不泄漏。

### Service 与故障语义

- 验证更新 ID 仅来自主体、响应角色分别来自数据库与主体、事务为写事务且返回成功前已经完成提交。
- 无变化请求、大小写变化、0 行更新与账户消失的区别。
- 只将已确认的 MySQL 重复键错误翻译为用户名冲突；其他完整性、连接、事务启动/完成、死锁和锁超时故障映射存储不可用。
- UPDATE 后投影失败时事务回滚；未预期的响应组装异常不得留下已提交修改。

### 真实集成验证

- 使用 Testcontainers MySQL 8.4，验证真实 UPDATE、唯一索引、数据库排序规则及 UTC updated_at 写入；不使用 H2 或以 Repository mock 替代正常持久化验证。
- 使用真实签名 JWT 经过实际 SecurityConfig 与 TokenVersionAuthenticationFilter，覆盖普通用户、管理员以 USER/ADMIN 登录、本人归属、过期/错误签名、失效版本及账户不存在。注入主体的 WebMvcTest 仅证明 HTTP 契约，不能替代本项。
- Alice → alice 成功并保存大小写；本人完全相同值连续请求成功且 updated_at 不变；无变化请求以外的字段保持不变。
- 他人用户名及其大小写变体冲突，验证既有唯一索引处理；两个用户并发竞争同一新名称，验证最多一个成功且无重复数据。
- 同一用户并发更新按可控顺序执行，验证后执行的成功修改生效，不丢失角色、邮箱、密码摘要或 token version。
- 对比更新前后的账户行：仅 username、实际修改时的 updated_at 可变化；email、password_token、user_role、token_version、created_at、last_login_at 和其他账户均不变。
- 在真实事务中定点制造更新后的投影故障，验证用户名与 updated_at 回滚；故障注入仅用于异常分支，正常成功与并发场景使用真实数据库。
- 改名后的既有 Token 继续可用，后续 GET 返回新用户名；实际改成不同名称后，新用户名密码登录成功、旧名称不再匹配本人，邮箱登录仍有效。涉及现有登录 Redis 限流时使用真实 Testcontainers Redis，不 mock 掉该依赖。

验证基准为 Windows PowerShell `.\gradlew.bat check`。本接口不触及 storage/track、音频或封面；可设置 MINIO_INTEGRATION_TESTS=true 运行完整已有用例，避免环境门禁造成跳过，但不以此扩大改动范围。若测试未实际执行，使用 cleanTest check 强制重跑。

按 XML 报告记录真实通过、失败、错误和跳过数量，逐项列出跳过原因。必要 MySQL/Redis 集成验证被跳过时，不声称接口完成。完成后按 AGENTS.md 单接口格式准备基线、全部变更文件、API 文档、迁移无、curl 与实际验证证据，由 Claude 独立只读审核。

## 12. curl 示例

调用者预先在环境中设置自己的 ACCESS_TOKEN，文档不包含真实 Token、密码或验证码。

```sh
curl --request PATCH 'http://localhost:8080/api/v1/users/me' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data '{"username":"alice_new"}'
```

## 13. 设计自查与后续门禁

已核对单接口范围、用户名大小写和长度、严格请求绑定、六字段响应、唯一索引、无变化语义、事务与提交不确定性、改名后的认证边界和真实集成验证范围。没有待定业务字段、占位实现或额外模块。

本次只提交设计文档。按 brainstorming 流程，用户审阅并批准书面设计后，再使用 writing-plans 制定实施计划；当前不编写业务代码或接口测试，不宣称实现完成。
