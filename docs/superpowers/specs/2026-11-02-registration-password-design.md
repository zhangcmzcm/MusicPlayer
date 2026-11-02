# 注册密码与 BCrypt 边界对齐设计

日期：2026-11-02。

状态：对话设计已确认，书面设计待用户审阅；尚未进入实现。

基线：main @ 0b997bf。设计开始时工作区干净。

## 1. 目标与范围

仅修复现有 `POST /api/v1/auth/register` 的密码校验。公开注册、固定创建 USER、邮箱与验证码配对规则、成功 201 和响应 DTO 均保持现状。

现有 UserRegisterRequest.password 使用 @NotBlank 和 @Size(min = 8, max = 128)，但 BCrypt 创建摘要的 UTF-8 输入上限为 72 字节。因此部分通过请求校验的输入会在哈希时抛异常，最终返回 500。修复后，超界及不允许的字符在请求校验阶段返回 400，不进入注册业务流程。

用户进一步确认：新注册密码仅允许 ASCII 可打印字符，禁止中文、emoji 和控制字符。这是明确的创建规则收紧，合法范围比单纯限制 UTF-8 字节数更小。

不更换 BCrypt、不截断、不预哈希、不修改 PasswordHasher、不迁移已有摘要；不修改登录、管理员初始化或实现改密接口。不新增依赖、数据库迁移、配置、限流策略或错误码，不改全局 JSON 绑定规则。

## 2. 已核实的项目事实

- AuthController.register 通过 @Valid @RequestBody 接收 UserRegisterRequest，校验成功后才调用 UserRegistrationService。
- UserRegistrationService 委托 UserRegistrationTransactionService；事务服务检查唯一性，验证可选邮箱验证码，再调用 PasswordHasher.hash，保存用户，最后登记提交后消费验证码。
- BCryptPasswordHasher 委托 BCryptPasswordEncoder；本次通过约束输入解决长度问题，不捕获所有哈希异常并统一改成参数错误。
- 现有统一校验异常响应为 400 VALIDATION_FAILED，type 为 urn:problem:music:validation_failed。
- 现有注册集成测试使用真实 MySQL 与 Redis，但直接调用 Service。它不能独自证明 MVC 请求校验生效，须补充真实 HTTP 入口的集成覆盖。
- docs/api/auth/register-user.md 和 DTO OpenAPI 当前声明 8—128 字符，需要同步更新。

## 3. 方案与决策

| 方案 | 收益 | 代价与结论 |
| --- | --- | --- |
| 限制创建输入到 BCrypt 可处理范围（选定） | 保留现有摘要和验证体系，在哈希前给出明确 400 | 收紧新密码创建契约；按用户选择进一步限定为可打印 ASCII |
| 引入支持更长密码的摘要方案 | 可支持更长或不同字符范围 | 需额外设计算法标识、存量兼容与迁移，超出本次修复范围 |

可打印 ASCII 下，Java String 字符数、Unicode 码点数与 UTF-8 字节数相同。因此采用已有 Bean Validation 组合约束即可，无须新增通用 UTF-8 校验器。

## 4. 密码请求契约

password 必须同时满足：

1. 存在、非 null、非空、非全空格。
2. 长度 8—72，包含两个端点。
3. 每个字符均位于 U+0020 至 U+007E，含英文大小写字母、数字、英文符号和普通空格。

采用 @NotBlank、@Size(min = 8, max = 72) 和完整匹配 `[\x20-\x7E]+` 的 @Pattern 组合。正则需按 Java 字符串规则转义，使用完整匹配语义，不允许尾部换行穿透；不使用范围更宽的 Unicode 空白或图形字符分类。

不要求同时包含字母、数字或符号，不额外引入复杂度规则。合法首尾空格原样保留；不 trim、不折叠大小写、不规范化、不截断。全空格即使长度合法也拒绝。

禁止中文、emoji（含组合序列）、非 ASCII 字母、全角符号、DEL、换行、回车、制表符、NUL 等范围外字符。JSON 转义先按原有绑定规则解码，再按实际字符串校验；转义不绕过字符限制。

其他字段和 JSON 绑定行为沿用现状，本次不为注册增加严格重复键或未知字段解析。

## 5. 数据流与错误边界

请求经既有安全链和 JSON 绑定后，由 Bean Validation 校验 DTO。密码不合法时统一经 GlobalExceptionHandler 返回 application/problem+json、400 VALIDATION_FAILED 与下划线 urn；字段信息只描述规则，不包含密码原文、摘要或内部异常。

非法密码不会调用注册 Service，因此不执行注册唯一性查询、验证码验证、验证码消费、密码哈希或用户写入。此保证针对注册业务流程，不声称整个 HTTP 请求完全没有认证或基础设施活动。

合法请求仍进入既有注册事务，不调整验证码提交后消费机制、唯一冲突处理、事务边界或公开权限。非长度原因的意外哈希故障仍按现有内部错误处理，不将服务器故障伪装为 400。

## 6. 文档与存量兼容

DTO OpenAPI 同步 minLength=8、maxLength=72、允许字符模式及中文说明；注册 API 文档明确只接受可打印 ASCII、禁止全空格、首尾空格保留，以及非法字符/长度返回 400。

已有账户无需迁移，已有密码的登录验证不加本次字符限制。不能推断所有存量凭据都符合新的创建规则。

用户同时确认后续 U03 改密的 newPassword 采用相同创建规则。本次仅同步修订既有改密设计中的新密码契约与测试描述，不实现该接口；oldPassword 的 1—128 字符、非全空白及历史兼容规则保持原状，不增加 ASCII 或 72 字节限制。

## 7. 验证设计

- DTO/HTTP 契约：覆盖缺失、null、空串、全空格，长度 7/8/71/72/73/128，以及允许范围端点空格与波浪号。
- 字符拒绝：覆盖中文、emoji、组合序列、非 ASCII 文字、不换行空格、DEL、NUL、制表符、回车和换行，包括 JSON 转义后的控制字符。24 个普通汉字即使只有 72 UTF-8 字节也必须拒绝。
- HTTP 校验失败断言 400、code、type 和字段错误，不回显原密码，且注册 Service 未调用。
- 真实 MySQL/Redis/BCrypt 的 HTTP 集成：合法 8/72 字符可创建账户，摘要可验证原密码；合法首尾空格保留，用去空格后的不同密码验证不匹配。
- 非法密码带有效邮箱验证码：HTTP 返回 400，无新增账户；验证码 hash 内容及业务状态保持不变，不验证、不消费、不增加错误尝试。TTL 允许自然递减，不能要求前后毫秒值完全相等。
- 不以直接调用未启用方法校验的 Service 来证明 DTO 的 HTTP 边界；保留已有注册事务与认证回归测试。

基准命令为 Windows PowerShell `.\gradlew.bat check`。本次不涉及 storage/track、音频或封面，无须新增 MinIO 测试要求；必要 MySQL/Redis Testcontainers 不得跳过后当作通过。审核材料报告实际通过、失败和跳过数量。

## 8. 实现边界与交付

预期修改 UserRegisterRequest、注册 HTTP 契约及集成测试、docs/api/auth/register-user.md；必要时补充 DTO 校验测试。Controller 的 @Valid 和统一错误映射已经具备能力，不预设需要修改。最终文件清单在实现计划中确定。

本设计确认后进入 writing-plans，书面实现计划确认后再编码。代码、测试、OpenAPI、API 文档及 check 完成后，按单接口审核协议提交 Claude 独立审核。

## 9. 自审记录

已核对单接口范围、ASCII 端点、字符数与字节数一致性、空格保留、全空格拒绝、校验执行时机、错误不泄漏、真实 HTTP 与外部依赖验证、存量兼容及与改密设计的衔接。无待定业务规则、算法迁移或额外接口实现。
