# 获取当前用户信息接口设计

日期：2026-11-01。

状态：用户已确认对话中的接口契约、读取方案、异常行为与测试范围；书面设计待用户审阅。

基线：当前分支 @ 31fd99f；开始设计时已有 `.gitignore` 未提交修改，不属于本次范围。

## 1. 目标与范围

仅设计并在后续实现 `GET /api/v1/users/me`，对应任务书 U01。从服务端验证后的认证主体确定本人，返回允许展示的账户资料和角色信息。

本次不实现资料修改、密码修改、邮箱变更、登录历史、退出登录、管理员查询其他用户或认证缓存，不改变其他接口契约。

## 2. 方案比较与选择

| 方案 | 收益 | 代价 |
| --- | --- | --- |
| 专用资料查询投影（选定） | 仅查询允许展示的字段，不加载密码摘要；资料查询与认证状态查询职责分明 | 增加一个投影及 Repository 查询方法 |
| 复用 findById 查询 Entity | 复用现有方法，由 Service 映射 DTO | 会加载密码摘要等无关字段 |

认证过滤器继续按现有方式查询认证状态，本次在业务 Service 中另行查询资料，不为减少一次查询而扩展共享认证主体或认证状态快照。

## 3. 请求与身份规则

- Method：GET；完整 Path：`/api/v1/users/me`。
- 需要有效 Bearer JWT，沿用签名、issuer、audience、有效期、token version 和登录角色校验。
- 无请求体，无业务查询参数，无路径用户 ID。
- USER、ADMIN 登录身份均可访问，但只能查询自己。
- Controller 只接收服务端生成的 `AuthenticatedUserPrincipal`，Service 查询 ID 只能来自其中的 `userId`。
- 未定义的查询参数忽略；例如 `?userId=其他用户ID&loginRole=ADMIN` 不改变本人身份、查询对象或角色结果。文档明确这些参数不受支持，并以回归测试验证无法越权。
- 不读取自定义请求头、请求体、查询参数中的用户 ID 或角色作为身份来源。

## 4. 成功响应与字段白名单

返回 `200 OK`、`application/json`，设置 `Cache-Control: no-store`。使用独立的 Java record 响应 DTO，不返回 JPA Entity，不复用具有不同角色语义的登录响应 DTO。

| 字段 | JSON 类型 | 来源与含义 | 可空 |
| --- | --- | --- | --- |
| id | integer | 数据库用户 ID，保持现有 Integer 类型 | 否 |
| username | string | 数据库当前用户名 | 否 |
| email | string 或 null | 本人完整邮箱，不脱敏 | 是 |
| userRole | string | 数据库账户角色，USER 或 ADMIN | 否 |
| loginRole | string | 认证主体的本次登录身份，USER 或 ADMIN | 否 |
| createdAt | string 或 null | 数据库创建时间，按 UTC 转为 Instant，ISO-8601 输出 | 是 |

数据库现有 created_at 允许为空，历史记录为空时显式返回 null，不伪造时间。email 为空时也保留字段并返回 null。

示例：管理员以普通用户身份登录。

```json
{
  "id": 42,
  "username": "alice",
  "email": "alice@example.com",
  "userRole": "ADMIN",
  "loginRole": "USER",
  "createdAt": "2026-10-21T12:34:56Z"
}
```

前端以 loginRole 展示本次可用操作；userRole 表示账户角色，不能据此绕过本次登录身份限制。所有受保护操作仍由服务端独立鉴权。

严格排除 passwordHash、password_token、tokenVersion、访问 Token、验证码、updatedAt、lastLoginAt 等未列入白名单的字段。

## 5. 组件与数据流

1. 现有安全链校验 JWT 和数据库认证状态，生成 AuthenticatedUserPrincipal。
2. 当前用户 Controller 取得认证主体，调用专用资料查询 Service，构造成功 HTTP 响应及缓存头，不承担业务规则。
3. Service 在只读事务中，使用主体 userId 调用 Repository 专用投影查询。
4. Repository 按主键等值查询 id、username、email、userRole、createdAt，不查询密码摘要或 tokenVersion，不使用写锁。
5. Service 将查询结果与主体 loginRole 组装成响应 DTO，Controller 不接触 Entity。

只读事务不涵盖安全过滤器先前的认证状态查询；不新增远程调用。认证与资料查询不是跨请求或跨阶段的原子快照。账户角色来自资料查询时的数据库记录，loginRole 来自本次已验证主体；并发角色变更通过现有版本机制在后续请求校验，不承诺中止所有已经进入业务层的请求。

本接口不写数据库、不更新最后登录时间、不新增缓存、不新增独立限流。重复读取没有业务写入副作用，不增加幂等键、分页、排序或资源版本参数。

## 6. 异常与错误契约

| 阶段或场景 | HTTP / code | 行为 |
| --- | --- | --- |
| 缺少认证 | 401 UNAUTHORIZED | 沿用现有认证入口 |
| JWT 必需声明无效 | 401 INVALID_TOKEN | 沿用现有声明验证逻辑 |
| 认证时账户不存在、版本失效或登录角色不再允许 | 401 TOKEN_REVOKED | 沿用现有认证过滤器 |
| 认证通过后资料查询发现账户已不存在 | 401 TOKEN_REVOKED | 使用专用业务异常映射，不复用管理接口的 404 USER_NOT_FOUND |
| 认证状态查询失败 | 503 AUTH_STATE_STORE_UNAVAILABLE | 沿用现有逻辑 |
| 资料查询或其只读事务发生数据访问故障 | 503 USER_PROFILE_STORE_UNAVAILABLE | 新增专用异常及统一处理，不泄漏数据库细节 |
| 未预期错误 | 500 INTERNAL_ERROR | 沿用统一异常处理 |

业务错误使用 application/problem+json、稳定 code、现有 traceId 约定及 urn:problem:music:* 类型格式。新增错误类型为 `urn:problem:music:user-profile-store-unavailable`；TOKEN_REVOKED 使用 `urn:problem:music:token-revoked`。

JWT 签名错误、过期等资源服务器响应继续使用现有安全链行为，不在本次统一修改公共认证错误契约；API 文档应明确这一现状，不宣称全部 JWT 失败均已返回统一 ProblemDetail。

不存在独立的查询他人资料入口，也无正常已认证身份的 403、资源 404 或写入冲突 409 分支，不为测试数量人为添加业务错误。

异常日志保持现有 SLF4J 与 traceId 约定，不记录邮箱、密码摘要、Token 或完整敏感响应，不改变 Actuator 暴露范围。

## 7. 预计变更范围

- user/controller：当前用户查询 Controller，完整 OpenAPI 注解。
- user/service：只读资料查询 Service。
- user/dto：专用当前用户响应 DTO。
- user/domain：只含白名单持久化字段的查询投影。
- user/repository/UserRepository：新增按主体 ID 查询资料的投影方法。
- user/exception：本人账户消失、资料存储不可用专用异常。
- common/exception/GlobalExceptionHandler：仅增加本接口所需异常映射。
- src/test/java/org/example/music/user：Controller、Service 及真实数据库认证链集成测试。
- docs/api/user/get-current-user.md：中文接口文档，含字段、权限、空值、错误和 curl。

不新增依赖，不新增或修改数据库迁移，不修改共享认证主体、登录响应和认证状态快照；现有 SecurityConfig 已要求未放行接口认证，无需新增公开路径。

## 8. 测试与完成标准

HTTP 契约测试验证成功状态、精确字段集合、字段类型、显式 null、UTC 时间、no-store 和 ProblemDetail 错误。主体为 USER、ADMIN 及 ADMIN 账户以 USER 登录均覆盖；普通身份访问本人成功，不因缺少 ADMIN 权限被拒绝。

Service 测试验证仅使用主体 ID、数据库账户角色与主体登录角色分别取值、空值处理、账户消失及资料存储故障转换。数据访问异常不得被当作账户不存在；与只读事务相关的故障也应在统一错误契约内处理。

集成测试使用 Testcontainers MySQL 8.4 和真实签名 JWT 经过实际安全过滤链，验证：

- 本人资料读取成功；管理员两种登录身份输出正确。
- 数据库投影查询、历史空邮箱与空创建时间、现有 Flyway 迁移可执行。
- 未认证、错误主体声明、过期或错误签名 JWT、已撤销版本及不存在账户被拒绝，错误响应符合现有链路及本设计声明。
- 前端提交他人 ID 或伪造角色无法读取他人资料或改变登录角色。
- 角色变化导致旧 Token 在后续请求失效。
- 敏感字段不出现在 JSON，重复读取不更新账户字段或 last_login_at。

本接口本身不调用 Redis/S3，不新增这些服务的模拟来替代实际依赖验证；如复用的应用集成测试环境需要 Redis，则沿用真实 Testcontainers Redis。认证之后账户消失和存储故障可在 HTTP/Service 测试中通过受控故障验证，不新增删除用户接口。

基准验证命令为 Windows PowerShell `.\gradlew.bat check`。若任务执行期间实际触及 storage/track、音频、封面或对象存储范围，必须启用 MINIO_INTEGRATION_TESTS=true；可启用该环境变量运行全量回归以避免已有 MinIO 用例跳过，但不得据此扩大实现范围。

记录实际通过、失败、跳过数量及跳过原因；必要集成测试被跳过时不得声称完成。OpenAPI 与中文 API 文档均须与最终实现一致，按 AGENTS.md 准备单接口审核材料。

示例 curl（环境变量 ACCESS_TOKEN 由调用者提供，不写入文档）：

```sh
curl --request GET 'http://localhost:8080/api/v1/users/me' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```

## 9. 书面设计自查与后续门禁

已核对字段白名单、两个角色的来源、可空时间、查询参数忽略语义、账户消失与存储故障分支、并发边界和单接口范围。无数据库结构改动、占位业务或额外模块。

本设计文件单独提交。按 brainstorming 流程，用户审阅并批准书面设计后，进入 writing-plans 制定实现计划；当前不编写业务代码、不运行实现验证、不宣称接口已完成。
