# 注册密码边界修复首轮审核材料

日期：2026-11-02。审核状态：Claude 独立审核第 1/3 轮 accepted，零阻断、两条非阻断意见；处置记录见文末。

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

## 第 1 轮审核回执与处置

用户提供 Claude 报告：accepted，基线 main @ 2902e07；独立运行 `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain`，BUILD SUCCESSFUL，8 分 25 秒；802 通过、0 失败、0 错误、0 跳过，81 类。实现 29d3f3e、37c294f 确认通过，不需要第 2 轮阻断项复核。用户已授权核实无误后推送。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断，AdminBootstrapInitializer.java:57,69，启动密码缺少 BCrypt 边界校验 | 成立，但仅在初始化已启用、无现有 ADMIN、用户名/邮箱可用且实际走到哈希时触发；requiredValue 仅拒绝 null/空白，hash 前无长度约束 | 认可非阻断；设计明确排除初始化，不影响已确认的注册入口规则 | 认可独立任务；后续需确认是完整对齐 ASCII 创建规则还是仅明确 BCrypt 边界，不在此处默认改变管理员密码政策 | 排期到任务书 O15，未修改初始化代码 | 静态复核 requiredValue、初始化提前退出与 hash 调用路径；本轮无需为未修改代码新增测试 |
| NOTE-002 非阻断，register-user.md:55-67，缺少 password 字段错误示例 | 成立；原示例仅展示 emailCodePairValid，虽然字段表和错误表已说明密码规则，但缺少对应 errors 示例 | 认可非阻断，属于客户端文档便利性 | 认可补充示例，复用现有实际约束消息，不更改响应契约 | 已补充 errors.password 示例，并说明多个约束失败时不保证优先顺序 | 文案核对 UserRegisterRequest 的 @Pattern 消息；git diff --check 通过。审核后仅改文档，未重复运行 8 分钟完整测试 |

审核回执之后仅更新 API 示例、处置/计划记录和任务书；无运行时代码变化。完整测试证据继续适用于实现 37c294f，不能将文档更新描述成新的独立测试运行。
