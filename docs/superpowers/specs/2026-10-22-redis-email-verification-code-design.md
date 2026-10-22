# Redis 邮箱验证码设计

日期：2026-10-22

状态：替代《用户注册接口设计》中的 MySQL 验证码消费方案。

## 1. 背景与决策

此前将邮箱验证码设计在 MySQL `verification_codes` 表中。生产环境的验证码具有以下特征：

- 生命周期短。
- 可以通过 TTL 自动过期。
- 不需要长期审计查询。
- 需要快速读取和原子递增尝试次数。

因此，验证码改为仅保存在 Redis。MySQL `users` 表继续保存用户账户数据。

已确认决策：

- 仅实现 Redis，不保留 MySQL 验证码双实现。
- 用户数据提交成功后，再原子消费验证码。
- Redis 只保存验证码 BCrypt 摘要，不保存明文。
- 验证码有效期为 5 分钟。
- 错误尝试上限为 5 次。
- 邮箱发送工具后续属于独立接口，放入 `common.util.EmailVerificationCodeSender`。

## 2. 修改范围

本次修改继续只涉及接口 `POST /api/v1/auth/register`，不增加新 HTTP 接口。

需要移除：

- `V1__create_verification_codes.sql`
- `VerificationCodeEntity`
- `VerificationCodeRepository`
- `EmailVerificationCodeFailureService`
- MySQL 验证码集成测试数据和表结构
- `spring-boot-starter-flyway`、`flyway-mysql` 等仅为验证码迁移引入的依赖

需要新增或修改：

- Redis 验证码存储端口和实现。
- 验证码验证、错误计数、版本校验和一次性消费服务。
- 注册服务中的数据库提交成功回调。
- Redis 配置和 Docker Compose 服务。
- Redis Testcontainers 集成测试。
- 注册接口 OpenAPI 和 API 文档。

## 3. Redis 数据模型

### 3.1 Key

```text
music:verification:code:REGISTER:<normalized-email>
```

`normalized-email` 在写入和读取前统一去除首尾空白并转换为小写。每个邮箱和用途只有一个当前验证码；写入新验证码会覆盖旧验证码。

### 3.2 Hash 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `codeHash` | string | 验证码 BCrypt 摘要 |
| `version` | string | UUID，防止消费旧版本验证码 |
| `attempts` | integer | 错误尝试次数，初始值为 0 |
| `usedAt` | string/null | 成功消费时间，UTC ISO-8601 |

整个 key 的 TTL 为 300 秒。创建新验证码时重置 `attempts` 和 `usedAt` 并重新设置 TTL。消费验证码不延长 TTL。

key 过期后由 Redis 自动删除，不再维护独立过期状态。因此过期和不存在统一映射为 `EMAIL_CODE_INVALID`。

## 4. 验证码生命周期

### 4.1 写入

写入能力属于后续“发送邮箱验证码”接口。该接口应：

1. 生成 6 位数字验证码。
2. 使用 BCrypt 生成摘要。
3. 生成新的 UUID 版本号。
4. 通过 Redis 原子写入覆盖旧值并设置 300 秒 TTL。
5. 将明文验证码交给 `common.util.EmailVerificationCodeSender` 发送。
6. 日志不输出验证码或摘要。

本次注册接口不提前创建未使用的邮件发送工具。

### 4.2 验证

注册请求填写邮箱时：

1. 读取当前 Redis Hash。
2. key 不存在时返回 `EMAIL_CODE_INVALID`。
3. `usedAt` 非空时返回 `EMAIL_CODE_ALREADY_USED`。
4. `attempts >= 5` 时返回 `EMAIL_CODE_ATTEMPTS_EXCEEDED`。
5. 使用 BCrypt 比较请求验证码和 `codeHash`。
6. 不匹配时通过 Lua 原子递增 `attempts`；若达到 5 次返回 `EMAIL_CODE_ATTEMPTS_EXCEEDED`。
7. 匹配时返回内部 `VerifiedEmailCode(email, version)`，不返回验证码摘要。

### 4.3 消费

注册采用数据库优先流程：

1. 验证验证码，但暂不标记已使用。
2. 在 MySQL 事务内创建 `UserRole.USER`。
3. MySQL 事务提交成功后，执行 Redis Lua 消费。
4. Lua 仅在 key 存在、`version` 相同且 `usedAt` 为空时写入 `usedAt`。
5. 数据库回滚时不调用消费，验证码保持可用。
6. 数据库成功但 Redis key 已过期、被覆盖或写入失败时，记录 WARN/ERROR 和 `traceId`；注册仍返回 201，因为邮箱唯一约束已经保证该邮箱无法创建第二个账户。

该策略避免数据库失败时丢失验证码，并接受“数据库已创建用户但 Redis 消费失败”的可恢复日志异常。由于同一邮箱只能创建一个用户，该异常不会造成重复注册。

## 5. Redis 原子性

以下操作必须使用 Lua 或等效原子执行：

- 错误尝试次数递增。
- 按 `version` 标记验证码已使用。

伪代码：

```text
consume(email, expectedVersion):
  if key does not exist:
    return MISSING
  if usedAt is not empty:
    return ALREADY_USED
  if version != expectedVersion:
    return VERSION_MISMATCH
  set usedAt = now
  return CONSUMED
```

Java BCrypt 比较不能放入 Lua，因此采用“读取快照、Java 比较、原子递增失败次数、数据库提交后原子消费”的流程。版本号保证消费响应不会误删除或覆盖后来生成的新验证码。

## 6. 注册接口错误

所有错误继续使用 RFC 9457 `ProblemDetail` 和稳定 `code`。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 字段或字段组合不合法 |
| 409 | `USERNAME_ALREADY_EXISTS` | 用户名已存在 |
| 409 | `EMAIL_ALREADY_EXISTS` | 邮箱已存在 |
| 409 | `USER_ALREADY_EXISTS` | 并发唯一约束冲突 |
| 409 | `EMAIL_CODE_ALREADY_USED` | 当前验证码已消费 |
| 422 | `EMAIL_CODE_INVALID` | 验证码不存在、已过期或不匹配 |
| 429 | `EMAIL_CODE_ATTEMPTS_EXCEEDED` | 错误尝试达到 5 次 |
| 503 | `VERIFICATION_CODE_STORE_UNAVAILABLE` | Redis 不可用或超时 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |

移除独立的 `EMAIL_CODE_EXPIRED`，因为 TTL 删除后无法可靠区分过期和不存在。

未填写邮箱的注册不访问 Redis。

## 7. 组件边界

| 组件 | 职责 |
| --- | --- |
| `AuthController` | HTTP、校验、OpenAPI |
| `UserRegistrationService` | 注册事务、角色固定 USER、提交后消费 Redis |
| `EmailVerificationCodeService` | 状态校验、BCrypt 比较、错误次数、消费编排 |
| `EmailVerificationCodeStore` | Redis 存储端口，暴露读取、失败计数和版本消费 |
| `RedisEmailVerificationCodeStore` | Redis Hash 和 Lua 实现 |
| `GlobalExceptionHandler` | RFC 9457 错误映射 |

验证码存储不返回 JPA Entity。跨层对象使用 record。

## 8. 配置与依赖

新增 Spring Data Redis 依赖：

- `spring-boot-starter-data-redis`
- Testcontainers core `GenericContainer` 和 JUnit 模块，版本 `2.0.5`

Redis 配置全部通过环境变量提供：

- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_DATABASE`
- `REDIS_USERNAME`
- `REDIS_PASSWORD`
- `REDIS_TIMEOUT`

不保存硬编码连接、用户名或密码。Docker Compose 增加 Redis 服务，密码必须由环境变量注入。

Flyway 和 `verification_codes` 迁移不再属于当前接口；后续业务如需要 MySQL 表，再按独立接口新增迁移。

## 9. 测试计划

单元测试：

- 无邮箱时完全不访问 Redis。
- 验证码不存在、不匹配、已使用和次数超限。
- 第 5 次错误返回次数超限。
- `version` 匹配后才能消费。
- 数据库失败时不调用 Redis 消费。
- 数据库成功后只消费一次。
- Redis 消费失败不影响已提交的用户注册。
- 响应不包含验证码或摘要。

Redis + MySQL Testcontainers 集成测试：

- TTL 约为 300 秒。
- 新验证码覆盖旧值并重置次数。
- 并发错误尝试原子递增。
- 并发消费只有一个成功。
- 重复注册不会创建两个用户。
- Redis 消费只标记匹配版本。
- Flyway/MySQL 验证码表不再创建。

Web 测试继续覆盖公开注册、RFC 9457、条件必填和敏感字段隔离。

## 10. 完成定义

- 仅修改 `POST /api/v1/auth/register` 的验证码实现。
- MySQL 验证码代码、迁移和测试全部移除。
- Redis 写入、验证和消费语义明确且原子。
- 无硬编码 Redis 凭据。
- 无 TODO、假实现、敏感日志。
- OpenAPI 和 `docs/api/auth/register-user.md` 与 Redis 语义一致。
- 单元、Web、Redis 和 MySQL Testcontainers 测试通过。
- 运行 `gradlew check` 并汇报结果。