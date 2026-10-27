# 管理员授权安全加固实现计划

日期：2026-10-27

设计文档：`docs/superpowers/specs/2026-10-27-admin-authorization-hardening-design.md`

## 1. 目标

在不新增 HTTP 接口的前提下，完成管理员授权链路安全加固：

- MySQL 中的角色和 Token Version 成为唯一权威授权状态。
- `PATCH /api/v1/users/{id}/role` 的角色、版本和审计原子提交。
- 最后一名管理员不能被降级。
- 登录签发和认证校验均使用单快照，旧权限 Token 在角色变更后失效。
- 首个管理员只能由环境变量在空管理员库中初始化。
- TraceId、JWT claims 和异常处理不产生 500 或安全绕过。

## 2. 实施约束

- Java 21、Spring Boot 3.2+、Spring Data JPA、MySQL、Flyway、JUnit 5、Testcontainers。
- 不修改已执行的 `V4__create_user_role_history.sql`。
- 不引入新依赖；使用现有 BCrypt `PasswordHasher`、Nimbus JWT、SLF4J 和 Testcontainers。
- 不使用 `ddl-auto=update`，不写入任何明文密码、密钥或 Token。
- Controller 不实现业务逻辑，Service 不返回 JPA Entity。
- 本次只重构既有 `PATCH /api/v1/users/{id}/role`、登录和认证链路，不实现新的 HTTP 接口。
- 每个阶段只修改当前功能所需文件，不顺带重构无关模块。

## 3. 工作分解

### 阶段 1：数据库授权状态

文件：

- `src/main/resources/db/migration/V5__add_user_token_version.sql`
- `src/main/resources/db/migration/V6__create_user_role_change_guard.sql`
- `src/main/resources/db/migration/V7__harden_user_role_history.sql`
- `src/main/java/org/example/music/user/domain/UserEntity.java`
- 新增 `src/main/java/org/example/music/user/domain/UserRoleChangeGuardEntity.java`
- 新增 `src/main/java/org/example/music/user/repository/UserRoleChangeGuardRepository.java`
- `src/main/java/org/example/music/user/repository/UserRepository.java`

实现：

1. V5 为 `users` 增加 `token_version BIGINT NOT NULL DEFAULT 0`，增加 `user_role` 查询索引。
2. V6 创建单行 `user_role_change_guard`，插入 `id = 1` 并限制 guard 行唯一。
3. V7 给 `user_role_history.target_user_id`、`actor_user_id` 增加 `ON DELETE RESTRICT` 外键；悬挂数据存在时由外键校验中止迁移。
4. `UserEntity` 映射 `tokenVersion`，提供只读 getter；角色变更方法不暴露跨字段任意 setter。
5. Repository 增加单条查询的角色与版本投影、管理员锁定读和条件更新方法。
6. 所有授权状态读取必须返回同一行的 `userRole + tokenVersion`，禁止两个查询拼装。

### 阶段 2：核心异常与授权状态服务

文件：

- `src/main/java/org/example/music/user/exception/LastAdminCannotBeDemotedException.java`
- `src/main/java/org/example/music/user/exception/RoleUpdateConflictException.java`
- `src/main/java/org/example/music/user/exception/AdminPrivilegeRevokedException.java`
- `src/main/java/org/example/music/user/exception/InvalidTokenException.java`
- `src/main/java/org/example/music/common/exception/GlobalExceptionHandler.java`
- 新增 `src/main/java/org/example/music/user/domain/UserAuthorizationSnapshot.java`
- 新增 `src/main/java/org/example/music/user/service/UserAuthorizationStateService.java`
- `src/main/java/org/example/music/user/repository/UserRepository.java`

实现：

1. 定义稳定错误码 `LAST_ADMIN_CANNOT_BE_DEMOTED`、`ROLE_UPDATE_CONFLICT`、`ADMIN_PRIVILEGE_REVOKED`、`INVALID_TOKEN`。
2. `UserAuthorizationSnapshot` 使用 record 保存 `userId`、`userRole`、`tokenVersion`。
3. `UserAuthorizationStateService.findSnapshot(userId)` 使用单条数据库查询返回角色和版本。
4. 数据库读取失败时抛出统一认证状态异常，禁止返回默认版本 `0`。
5. GlobalExceptionHandler 使用 RFC 9457 `ProblemDetail` 映射新异常，不吞异常。

### 阶段 3：实现并完成 `PATCH /api/v1/users/{id}/role`

文件：

- `src/main/java/org/example/music/user/service/UserRoleAuthorizationService.java`
- `src/main/java/org/example/music/user/controller/UserRoleController.java`
- `src/main/java/org/example/music/user/domain/UserRoleHistoryEntity.java`
- `src/main/java/org/example/music/user/repository/UserRoleHistoryRepository.java`
- `src/main/java/org/example/music/user/dto/UpdateUserRoleRequest.java`
- `src/main/java/org/example/music/user/dto/UserRoleUpdateResponse.java`
- `src/test/java/org/example/music/user/service/UserRoleAuthorizationServiceTest.java`
- `src/test/java/org/example/music/user/controller/UserRoleControllerTest.java`
- `src/test/java/org/example/music/user/UserRoleAuthorizationIntegrationTest.java`

实现顺序：

1. Controller 只负责路径和请求体校验；路径 ID 增加正数约束。
2. Controller 从替换后的认证主体读取用户 ID，不解析 JWT `role`。
3. Service 事务第一步锁定 `user_role_change_guard.id = 1`。
4. 锁后查询操作者当前角色；非管理员返回 `ADMIN_PRIVILEGE_REVOKED`。
5. 校验目标存在、禁止自授权、同角色幂等返回。
6. 降级管理员前使用 `SELECT id FROM users WHERE user_role = 'ADMIN' FOR UPDATE` 锁定读并计数。
7. 对目标执行 `id + previousRole` 条件更新，同时递增 `token_version` 和 `updated_at`。
8. 条件更新未命中返回 `ROLE_UPDATE_CONFLICT`，不重试、不写审计。
9. 审计历史与角色更新同事务提交。
10. 完成该接口的 OpenAPI、`docs/api/user/update-user-role.md` 和 curl 示例后才进入后续阶段。

### 阶段 4：登录原子快照签发

文件：

- `src/main/java/org/example/music/user/service/UserLoginService.java`
- `src/main/java/org/example/music/security/JwtTokenService.java`
- `src/test/java/org/example/music/user/service/UserLoginServiceTest.java`
- `src/test/java/org/example/music/security/JwtTokenServiceTest.java`

实现：

1. 角色选择 Ticket 消费后通过 `UserAuthorizationStateService` 一次读取授权快照。
2. 请求登录角色不得超过快照中的数据库角色。
3. `JwtTokenService` 直接使用该快照签发 `sub`、`tokenVersion` 和兼容用 `role`，不重新查询版本。
4. `role` claim 是本次实际登录身份，并被限制不得超过数据库最高角色。
5. 数据库不可用时不签发 JWT。
6. 测试并发登录与角色变更的线性化结果，确保旧快照 Token 在变更提交后失效。

### 阶段 5：认证过滤器与权限替换

文件：

- `src/main/java/org/example/music/security/JwtAuthenticationConverterProvider.java`
- `src/main/java/org/example/music/security/TokenVersionAuthenticationFilter.java`
- 新增 `src/main/java/org/example/music/security/AuthenticatedUserPrincipal.java`
- `src/main/java/org/example/music/config/SecurityConfig.java`
- `src/main/java/org/example/music/user/controller/UserRoleController.java`
- `src/test/java/org/example/music/security/TokenVersionAuthenticationFilterTest.java`

实现：

1. `JwtAuthenticationConverterProvider` 不再把 `role` claim 转换为 `ROLE_` 权限。
2. JWT 校验强制 `sub`、`role` 和 `tokenVersion`；`role` 必须是合法登录角色。
3. `TokenVersionAuthenticationFilter` 单条查询同时读取数据库最高角色和 `tokenVersion`，并校验 JWT 登录角色不超过最高角色。
4. 版本不一致返回 `TOKEN_REVOKED`；claims 非法返回 `INVALID_TOKEN`；数据库不可用返回 `AUTH_STATE_STORE_UNAVAILABLE`。
5. 根据已受数据库最高角色约束的 JWT 登录角色创建权限和 `AuthenticatedUserPrincipal`，并替换 `SecurityContext` 中的临时 Authentication。
6. Controller 使用替换后的 principal 用户 ID。
7. 测试非法 `role`、登录角色超过数据库最高角色、缺失 `role`、跨查询竞态和 SecurityContext 替换行为。

### 阶段 6：TraceId 规范化

文件：

- `src/main/java/org/example/music/common/web/TraceIdFilter.java`
- `src/test/java/org/example/music/common/web/TraceIdFilterTest.java`

实现：

1. 只接受 `[A-Za-z0-9._-]{1,64}` 的客户端 TraceId。
2. 非法、缺失或超长值使用服务端 UUID，不拼接或截断攻击者输入。
3. 规范化值写入响应头、MDC 和审计。
4. 测试超长、控制字符、空值和合法自定义 TraceId。

### 阶段 7：首个管理员初始化

文件：

- 新增 `src/main/java/org/example/music/user/config/AdminBootstrapProperties.java`
- 新增 `src/main/java/org/example/music/user/service/AdminBootstrapInitializer.java`
- 新增 `src/main/java/org/example/music/user/config/AdminBootstrapConfiguration.java`
- `src/test/java/org/example/music/user/service/AdminBootstrapInitializerTest.java`

实现：

1. `AdminBootstrapInitializer` 实现 `ApplicationRunner`，通过 `AdminBootstrapConfiguration` 注册，并从 `ADMIN_BOOTSTRAP_USERNAME`、`ADMIN_BOOTSTRAP_EMAIL`、`ADMIN_BOOTSTRAP_PASSWORD` 读取部署配置。
2. 仅在管理员数量为零时初始化。
3. guard 锁必须是初始化事务的首个数据库交互。
4. 缺失配置、用户名冲突或邮箱冲突时启动失败，不静默修改已有用户。
5. 密码只通过现有 BCrypt `PasswordHasher` 加密，不进入迁移、日志、异常或 Git。
6. 已有管理员时忽略所有 bootstrap 配置。
7. 测试空库创建、幂等跳过、冲突失败、多实例并发和日志脱敏。

### 阶段 8：文档与验证

文件：

- `docs/api/user/update-user-role.md`
- `docs/api/auth/login.md`
- 相关 OpenAPI 注解
- `src/test/resources/application-test.yml` 或现有测试配置，仅放非敏感测试值

执行：

1. 更新 Token Version MySQL 权威语义、最后管理员保护、错误码和 curl 示例。
2. 不在文档中保存 bootstrap 密码。
3. 运行格式化或项目现有检查。
4. 运行 `gradlew classes`。
5. 运行 `gradlew test`。
6. 运行 `gradlew check`。
7. 使用 Testcontainers 验证 MySQL 迁移、并发授权和启动初始化。

## 4. 测试矩阵

| 场景 | 期望 |
| --- | --- |
| 管理员正常修改其他用户角色 | 200，角色、版本和审计同事务 |
| 修改自己 | 403 `CANNOT_MODIFY_SELF_ROLE` |
| 普通用户调用 | 403 `FORBIDDEN` |
| 操作开始后操作者被降权 | 403 `ADMIN_PRIVILEGE_REVOKED` |
| 降级最后管理员 | 409 `LAST_ADMIN_CANNOT_BE_DEMOTED` |
| 两名管理员互相降级 | 最多一个成功，管理员数量大于零 |
| 相同角色请求 | 200 幂等，不递增版本、不写审计 |
| 外部写入绕过 guard | 409 `ROLE_UPDATE_CONFLICT`，无虚假审计 |
| 旧 Token 在角色变更后请求 | 401 `TOKEN_REVOKED` |
| JWT 登录角色为 `ADMIN`，数据库最高角色为 `USER` | 401 `TOKEN_REVOKED`，无管理员权限 |
| JWT 缺少 `role` | 401 `INVALID_TOKEN` |
| JWT 缺少 `sub` 或 `tokenVersion` | 401 `INVALID_TOKEN` |
| MySQL 授权状态不可用 | 503，禁止跳过校验 |
| Redis 清空或不可用 | 不影响认证正确性 |
| 非法 TraceId | 使用服务端 UUID，不产生 500 |
| 空管理员库 bootstrap | 创建一个 BCrypt 管理员 |
| 已有管理员 bootstrap | 忽略配置并保持数据不变 |
| bootstrap 配置冲突 | 启动失败，无密码日志 |

## 5. 验收条件

- `PATCH /api/v1/users/{id}/role` 的 OpenAPI、API 文档、curl、测试和验证全部完成。
- V5 至 V7 迁移可在空库执行；历史审计存在悬挂 ID 时安全失败。
- 角色变更、Token Version、审计具有数据库原子性。
- 每个认证请求使用 MySQL 单快照决定 Token Version 和权限。
- 最后管理员保护在并发下成立。
- bootstrap 不保存或打印明文密码。
- 无 TODO、假实现、敏感日志、硬编码密钥或 `ddl-auto=update`。
- `gradlew check` 通过。
