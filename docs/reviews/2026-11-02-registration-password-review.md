# 注册密码边界修复首轮审核材料

日期：2026-11-02。审核状态：待 Claude 独立审核，第 1/3 轮；Codex 自验不等于 accepted。

## 本次接口与权限

- 现有 `POST /api/v1/auth/register`，仅修复密码创建规则，不新增接口。
- 公开注册，无需认证；固定创建 USER，不能指定账户角色。
- password 非全空格、8—72 个 ASCII 可打印字符（U+0020—U+007E）。英文大小写、数字、英文符号和普通空格允许，不要求组合；首尾空格保留。

## 声称实现的行为

- DTO 使用 @NotBlank、@Size 与 @Pattern，在既有 @Valid 入口拒绝非法密码；BCrypt 超长输入不再作为合法注册参数进入哈希。
- 中文、emoji、其他非 ASCII 字符、控制字符、全空格及长度非法统一返回 400 VALIDATION_FAILED、urn:problem:music:validation_failed，字段错误不回显密码。
- 无效密码不调用注册 Service，不执行其唯一性查询、验证码验证/消费、密码哈希或用户写入。
- 不 trim、不规范化、不截断、不预哈希；继续真实 BCrypt 存储。
- 不修改登录或已有摘要。历史非 ASCII 密码仍可经真实 HTTP 密码登录；该兼容性由集成测试确认。
- 201、响应 DTO、USER 权限、邮箱配对、验证码事务和限流策略保持现状。

## 基线与提交

- 设计 aa45d12，书面设计经用户确认；计划 87b6e16，用户确认后开始编码。
- 实现：29d3f3e（DTO/HTTP 契约/API 文档）与 37c294f（真实依赖验证、补充边界、OpenAPI 全串匹配）。
- 审核范围以 aa45d12 之后的本任务提交为准，包含计划与本材料；设计已独立提交，不含其他接口实现。

## 变更文件（含新增文件）

1. src/main/java/org/example/music/user/dto/UserRegisterRequest.java
2. src/test/java/org/example/music/user/controller/AuthControllerTest.java
3. src/test/java/org/example/music/user/UserRegistrationIntegrationTest.java
4. docs/api/auth/register-user.md
5. docs/superpowers/plans/2026-11-02-registration-password-plan.md
6. docs/reviews/2026-11-02-registration-password-review.md（本材料）

数据库迁移：无，V1—V8 未动。依赖、配置、SecurityConfig、Controller、Service、Repository、PasswordHasher、登录实现和全局 JSON 绑定未修改。

## API 与 OpenAPI

- API 文档：docs/api/auth/register-user.md。
- DTO @Schema 明确 minLength=8、maxLength=72 及 ASCII 范围；服务端 @Pattern 使用完整匹配，OpenAPI pattern 显式约束起点与严格终点，避免搜索匹配或尾部换行绕过描述。
- 不新建错误码，400 复用既有统一映射。

## 测试与实际执行

契约测试先运行于旧 DTO：34 项中 15 项失败，证实旧规则放过超长及非 ASCII 输入；该阶段使用 mock Service，仅证明校验缺失，不作为真实 BCrypt 故障复现。

初次定向运行时 Docker 未运行，注册集成类 10 项被标记跳过，未当作通过。启动 Docker 后强制重跑：

```powershell
cmd /c gradlew.bat test --tests "org.example.music.user.UserRegistrationIntegrationTest" --tests "org.example.music.user.controller.AuthControllerTest" --rerun-tasks
```

结果：BUILD SUCCESSFUL，54 秒；通过 47 / 失败 0 / 错误 0 / 跳过 0。其中 HTTP 契约 35、真实 MySQL 8.4/Redis 7.4/BCrypt 集成 12。随后仅收紧了 OpenAPI pattern 的全串匹配表达，最终完整基准验证覆盖最终版本。

完整基准验证（最终代码 37c294f）：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check
```

结果：BUILD SUCCESSFUL，8 分 28 秒；通过 802 / 失败 0 / 错误 0 / 跳过 0，共 81 类。数量汇总自 build/test-results/test/TEST-*.xml；跳过项：无。本任务不涉及存储，但本次完整回归额外启用既有 MinIO 集成测试，未改变相关生产代码或测试。

覆盖重点：

- 缺失/null/空串、8/72 个空格、长度 7/8/71/72/73/128，首尾空格和可打印符号。
- 中文、emoji/组合序列、非 ASCII 字母、全角符号、NBSP、DEL、NUL、TAB、CR、LF；24 个汉字虽为 72 UTF-8 字节仍拒绝。
- 错误 code/type/字段、无密码回显、无注册 Service 调用。
- 真实 HTTP 创建边界账户及原文 BCrypt 匹配；带首尾空格的摘要不匹配 trim 后字符串。
- 非法密码带有效验证码：无账户、Redis hash 全字段不变、TTL 保持正值且仅允许自然递减。
- 已有非 ASCII 摘要账户真实 HTTP 登录仍成功；既有注册事务、验证码消费与并发冲突回归保留。

## curl 示例

以下文件使用虚构密码，无真实凭据。

```json
{"username":"ascii_demo","password":"example-pass-2026"}
```

将其保存为 register-example.json 后执行（公开接口不需要 Token）：

```bash
curl --request POST 'http://localhost:8080/api/v1/auth/register' \
  --header 'Content-Type: application/json' \
  --data-binary '@register-example.json'
```

```powershell
curl.exe --request POST 'http://localhost:8080/api/v1/auth/register' `
  --header 'Content-Type: application/json' `
  --data-binary '@register-example.json'
```

## 未决问题与默认项

无待定业务规则。仅限制新注册创建密码，既有密码验证兼容；改密 newPassword 的相同规则已在设计 aa45d12 中同步，未实施改密接口。公开注册不产生“已认证但无权限”或“本人资源不存在”分支，本次无需增加此类行为。无迁移或部署配置要求；客户端需要遵守新的创建限制。

## 审核反馈复核与处置

首次提交：不涉及。等待 Claude 独立审核，不自行将自验通过记为审核通过。
