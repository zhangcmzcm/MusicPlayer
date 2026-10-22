# 用户注册接口设计

日期：2026-10-21

## 1. 目标与范围

本次只实现 `POST /api/v1/auth/register`，用途是创建用户，属于无需认证的公开接口。登录、发送邮箱验证码、修改用户信息、管理员授权和找回密码均不在本次范围。

请求不允许指定角色。新用户固定创建为 `UserRole.USER`；`UserRole.ADMIN` 只能由后续用户信息管理接口授权。

## 2. 接口契约

### 2.1 请求

Content-Type 为 `application/json`。

| 字段 | 类型 | 必填 | 校验规则 |
| --- | --- | --- | --- |
| `username` | string | 是 | 3-20 位英文字母、数字、下划线；唯一 |
| `password` | string | 是 | 8-128 个字符；HTTPS 传输；服务端 BCrypt 存储 |
| `email` | string/null | 否 | 合法邮箱；最多 120 个字符；规范化为小写；唯一 |
| `code` | string/null | 条件必填 | 恰好 6 位数字；仅在 `email` 非空时允许且必须提供 |

密码字段为 `password`，不接受客户端预哈希的 `passwordToken`。

```json
{
  "username": "alice_2026",
  "password": "correct-password",
  "email": "alice@example.com",
  "code": "123456"
}
```

### 2.2 成功响应

成功返回 `201 Created` 和用户资源 DTO，不返回密码摘要、验证码或 JPA Entity。

```json
{
  "id": 42,
  "username": "alice_2026",
  "email": "alice@example.com",
  "userRole": "USER",
  "createdAt": "2026-10-21T12:34:56Z"
}
```

`createdAt` 使用 UTC、ISO-8601。未提供邮箱时 `email` 为 `null`。

### 2.3 RFC 9457 错误

所有错误返回 `ProblemDetail`，包含 `type`、`title`、`status`、`detail`、`instance` 和稳定扩展字段 `code`。

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 格式、长度、条件必填或字段组合不合法 |
| 409 | `USERNAME_ALREADY_EXISTS` | 用户名已存在 |
| 409 | `EMAIL_ALREADY_EXISTS` | 邮箱已存在 |
| 409 | `USER_ALREADY_EXISTS` | 并发插入触发唯一约束且无法安全区分字段 |
| 409 | `EMAIL_CODE_ALREADY_USED` | 验证码已消费 |
| 422 | `EMAIL_CODE_INVALID` | 验证码不存在或不匹配 |
| 422 | `EMAIL_CODE_EXPIRED` | 验证码已过期 |
| 429 | `EMAIL_CODE_ATTEMPTS_EXCEEDED` | 验证码错误尝试次数超限 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |

本接口不需要认证和授权，测试会验证未携带 Authorization 也能注册。

## 3. 验证码消费

采用注册服务统一编排方案。验证码消费与用户创建在同一数据库事务内完成，避免验证码已使用但用户未创建，或用户已创建但验证码未消费。

### 3.1 数据模型

假设现有 `users` 表已经存在。本次新增 `verification_codes`：

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `id` | BIGINT AUTO_INCREMENT | 主键 |
| `email` | VARCHAR(120) | 非空、规范化小写 |
| `purpose` | VARCHAR(20) | 非空，当前仅支持 `REGISTER` |
| `code_hash` | VARCHAR(255) | 非空，BCrypt 摘要，不存明文 |
| `attempts` | INT | 非空、默认 0 |
| `expires_at` | DATETIME(6) | 非空、UTC |
| `used_at` | DATETIME(6) | 可空、UTC |
| `created_at` | DATETIME(6) | 非空、UTC |
| `updated_at` | DATETIME(6) | 非空、UTC |
| `created_by` | BIGINT | 可空 |
| `updated_by` | BIGINT | 可空 |

索引：

- `ix_verification_codes_email_purpose_created_at (email, purpose, created_at)`
- `ix_verification_codes_expires_at (expires_at)`

同一邮箱的注册验证码以最新创建记录为准。后续发送验证码时必须使旧验证码失效，或由消费端只接受最新记录。

### 3.2 原子流程

1. Controller 校验 HTTP 请求并调用注册服务。
2. 注册服务去除用户名前后空白、将邮箱转为小写，先检查用户名和邮箱冲突。
3. 提供邮箱时，验证码服务按邮箱和用途锁定最新验证码记录。
4. 校验 BCrypt 摘要、过期时间和使用状态。
5. 校验成功后写入 `used_at`，在同一事务中创建 `UserRole.USER`。
6. 用户创建触发唯一约束时整体回滚，验证码不被消费。
7. 验证失败累计次数时使用独立事务，确保注册事务回滚后失败次数仍保留。
8. 验证码最多错误尝试 5 次，超限返回 429。

## 4. 组件设计

| 组件 | 职责 |
| --- | --- |
| `AuthController` | HTTP 映射、Bean Validation、状态码、OpenAPI |
| `UserRegistrationService` | 注册编排、事务、冲突处理、固定 USER 角色 |
| `EmailVerificationCodeService` | 校验、失败计数、一次性消费 |
| `UserRepository` | 用户查询和持久化 |
| `VerificationCodeRepository` | 验证码锁定查询和持久化 |
| `PasswordHasher` | 复用 BCrypt 密码摘要 |
| `GlobalExceptionHandler` | 业务和参数异常转 RFC 9457 |

Controller 和跨层边界只使用 record DTO，不返回 Entity。

邮箱发送工具在对应发送接口实现时放入 `common.util.EmailVerificationCodeSender`，只负责邮件发送，不负责验证码生成、过期策略或注册业务。本次不创建未使用的发送工具。

## 5. 数据库迁移

新增 Flyway 迁移 `V1__create_verification_codes.sql`。`users` 表由现有数据库提供，本接口不修改。禁止 `ddl-auto=update`；配置使用 `validate` 或 `none`。

后续从空库完整部署所需的 users 基线迁移单独决策，不在本接口中猜测线上迁移历史。

## 6. 安全与可靠性

- 密码和验证码只保存 BCrypt 摘要。
- 日志不输出密码、验证码、摘要或敏感请求体。
- 邮箱统一小写；用户名、邮箱唯一约束和事务回滚防止并发重复注册。
- 错误信息不泄露额外隐私。
- 发送验证码限流属于后续接口；本接口实现验证码尝试次数保护。
- 失败尝试独立提交，成功消费和用户创建共同提交。

## 7. 依赖

仅补充缺失依赖：Spring Boot Web、Spring Boot Security、Spring Boot Actuator、Flyway Core、Flyway MySQL、兼容 Boot 的 springdoc-openapi、Testcontainers MySQL/JUnit、Spring Security Test。版本优先使用 Boot BOM；springdoc 使用与 Boot 兼容的稳定版本。

不引入 Redis、邮件发送库、OAuth2 或 JWT。

## 8. 测试计划

Web 测试：

- 无 Authorization 的合法请求返回 201。
- 无邮箱请求和有邮箱加验证码请求成功。
- 字段长度、格式和条件必填边界。
- 提供邮箱缺验证码、未提供邮箱却提供验证码返回 400。
- 缺少必填字段返回 400。
- 响应不含密码、摘要或验证码。

服务单元测试：

- BCrypt 后保存且固定创建 `UserRole.USER`。
- 无邮箱跳过验证码；有邮箱验证并消费。
- 错误、过期、已用和次数超限映射稳定错误码。
- 创建用户失败时验证码消费回滚。
- 失败次数在主事务回滚后仍保留。
- 用户名和邮箱冲突映射 409。

MySQL Testcontainers 集成测试：

- 用户名、邮箱唯一约束。
- 并发重复注册只成功一次。
- 验证码只能消费一次。
- 验证失败不创建用户。
- Flyway 可从空数据库迁移验证表。
- UTC 时间正确持久化。

测试命名采用 `should_<result>_when_<condition>`。本接口公开，无权限场景不适用。

## 9. API 文档与完成定义

新增 `docs/api/auth/register-user.md`，包含方法、路径、权限、字段规则、成功和 RFC 9457 示例、错误码、curl。Controller 使用完整 `@Operation`、`@ApiResponse`、`@Schema`。

完成必须满足：只实现该接口、代码编译、迁移可执行、测试通过、OpenAPI 和文档完成、无 TODO/假实现/硬编码密钥/敏感日志、运行 `gradlew check`。

## 10. 决策与默认项

已确认：

- 本次接口为 `POST /api/v1/auth/register`。
- 明文密码经 HTTPS 传输，服务端 BCrypt。
- 邮箱可选；填写邮箱必须提供验证码。
- 错误统一 RFC 9457。
- 验证码消费和用户创建使用同一事务。
- 保留 `USER/ADMIN` 角色枚举；注册固定 USER。
- 管理员授权由后续用户信息接口处理。
- 邮件发送工具后续放入 `common.util`。

默认项：

- 用户名 3-20 位字母、数字、下划线。
- 密码 8-128 个字符，复杂度策略后续定义。
- 邮箱保存小写。
- 验证码 6 位数字，最多错误尝试 5 次。
- 后续接口统一采用“成功返回资源 DTO，失败返回 RFC 9457”。
