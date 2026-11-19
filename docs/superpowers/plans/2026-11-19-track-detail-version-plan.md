# 歌曲详情补充编辑版本号实现计划

> **供执行 Agent 使用：** 按任务逐项执行并勾选进度。writing-plans 推荐的执行子技能本会话未提供；沿用 AGENTS.md 和既定 Native 方式，由 Codex 实现全部变更，本机 Claude Code CLI 完成独立只读审核。

**目标：** 为 `GET /api/v1/tracks/{trackId}` 补充当前版本，使管理员能取得有效的草稿编辑前置条件。

**架构：** 在既有详情 DTO 中增加 `Long version`，Service 从同一个已加载实体读取版本并映射。复用现有认证、可见性、Repository 和只读事务；用现有 PATCH 验证读取结果可用于编辑。

**技术栈：** Java 21、Spring Boot 4.1.1、JPA、MockMvc、JUnit 5、Testcontainers MySQL 8.4 / Redis 7.4、Gradle Wrapper。

**设计：** [2026-11-19-track-detail-version-design.md](../specs/2026-11-19-track-detail-version-design.md)，用户已审阅同意。

**基线与状态：** 设计提交 `af8aaf9d8709639a8738ec91ca85fb0e546d5e88`；计划待用户审阅。实现及全量验证尚未开始。

## 全局约束

- 本次唯一变更接口为 `GET /api/v1/tracks/{trackId}`。
- `version` 为非空 JSON integer、Java Long、OpenAPI int64，范围 0 至 9223372036854775807。
- 所有获授权的成功详情统一返回实际版本；重复读取不递增版本，不修改审计字段。
- 需要有效 Bearer JWT；ADMIN 可读三种状态，USER 只读 PUBLISHED；隐藏及不存在统一 404。
- 保持 `Cache-Control: no-store`；无需数据库迁移、新依赖、新错误码或安全配置。
- 现有 PATCH 继续要求版本并拒绝过期版本；前端功能、列表和导入生产代码不纳入本次变更。
- 保留并在审核材料中披露用户既有 `.gitignore` 修改，任务提交只暂存本次明确文件。

## 重点检查

1. 新导入草稿版本为 0：详情必须返回数值 0，不能视为缺失。
2. 已编辑草稿版本非零：首次进入及重新打开时必须读取实际版本，不能填默认值。
3. 超出 JavaScript 安全整数及 Long 上界附近的版本：响应保持 JSON 整数精度，文档说明消费端无损解析。
4. USER 请求隐藏歌曲及管理员以 USER 登录：404 不披露版本；可见状态的成功响应都有版本。
5. GET 后被其他编辑更新：旧版本提交继续 409；重新读取获得最新资料和版本，读取不改变版本或审计字段。

## 文件映射

| 文件 | 变更职责 |
| --- | --- |
| src/main/java/org/example/music/track/dto/TrackDetailResponse.java | 新增 version 及非空 int64 OpenAPI 定义 |
| src/main/java/org/example/music/track/service/TrackDetailService.java | 从已加载实体映射版本 |
| src/main/java/org/example/music/track/controller/TrackDetailController.java | 说明详情提供编辑所需的当前版本 |
| src/test/java/org/example/music/track/controller/TrackDetailControllerTest.java | 严格 JSON、零值和完整 int64 范围 |
| src/test/java/org/example/music/track/service/TrackDetailServiceTest.java | 非零值透传及可见性矩阵 |
| src/test/java/org/example/music/track/TrackDetailIntegrationTest.java | 真实 GET/PATCH 链路、权限及只读验证 |
| docs/api/track/get-track-detail.md | 响应字段、示例、版本读取与精度说明 |
| docs/api/track/update-track-draft.md | 补充版本来源和冲突重读链接 |
| docs/reviews/2026-11-19-track-detail-version-review-request.md | 单接口范围、基线、测试实数及反馈记录 |
| docs/reviews/2026-11-19-track-detail-version-review.md | 保存带来源的 Claude 审核原文及 Codex 处置 |
| docs/superpowers/specs/2026-11-19-track-detail-version-design.md | 登记设计与交付状态 |
| docs/superpowers/plans/2026-11-19-track-detail-version-plan.md | 执行勾选与实际验证记录 |
| docs/tasks/backend-roadmap.md、PROJECT_STATE.md | accepted 后登记完成和必要后续信息 |

## 任务 1：补齐版本读取契约和有效回归

**使用与产出接口：** 沿用 `TrackDetailService.getDetail(Long trackId, AuthenticatedUserPrincipal actor): TrackDetailResponse`。在响应 record 的 `discNumber` 与 `status` 之间增加 `Long version` 及 `version(): Long` 访问器；Service 直接使用 `track.getVersion()`。既有 `PATCH /api/v1/tracks/{trackId}/draft` 的请求、响应及实现不改。

- [ ] **步骤 1：先补 Controller 失败断言。** 将 `should_return_detail_with_explicit_nulls_and_no_store` 参数化为 0、7、9007199254740993、9223372036854775807；先仅要求严格 JSON 包含对应数值 `version`，保留旧 DTO 构造，以得到字段缺失的断言失败。
- [ ] **步骤 2：运行红灯。** `.\gradlew.bat test --tests 'org.example.music.track.controller.TrackDetailControllerTest'`，确认失败原因是响应没有 `version`，保存日志，不把编译或环境故障作为有效红灯。
- [ ] **步骤 3：最小实现。** 按上述 record 位置增加字段，Schema 声明 `type="integer"`、`format="int64"`、`minimum="0"`、`maximum="9223372036854775807"`、`requiredMode=REQUIRED`；Service 和 Controller 测试构造器传实际版本，更新 Operation 说明。
- [ ] **步骤 4：补 Service 透传断言。** 内存实体夹具显式设非零版本 7；ADMIN 三种状态和 USER 已发布成功分支断言 `response.version()` 等于 7，隐藏状态继续走原拒绝分支。
- [ ] **步骤 5：补真实读写链路。** 在 `TrackDetailIntegrationTest` 新增 `should_read_current_version_for_draft_editing_and_reject_stale_updates`：真实 ADMIN JWT GET 新草稿取得版本 0，使用该 JSON 整数 PATCH 修改标题成功且版本为 1，再 GET 校验资料和版本均更新；用旧版本再次 PATCH 得到 409 / TRACK_VERSION_CONFLICT，最终 GET 保持成功修改的资料及版本。
- [ ] **步骤 6：补范围、权限及只读证据。** 在现有权限矩阵中用非零版本 7 校验所有成功响应及隐藏响应无 version；新增 `should_preserve_full_int64_versions_in_detail_response` 用测试数据库版本 9007199254740993 和 Long.MAX_VALUE 校验返回 JSON 的整数值；重复 GET 测试比较读取前后的 version、createdAt、createdBy、updatedAt、updatedBy。JSON 解析复用已有 `tools.jackson.databind.json.JsonMapper.builder().build()`，使用 `isIntegralNumber()` 和 `asLong()` 校验整数及精确值，不增加依赖。
- [ ] **步骤 7：运行聚焦绿灯。** 设置 `$env:MINIO_INTEGRATION_TESTS='true'` 后执行 `.\gradlew.bat test --tests 'org.example.music.track.controller.TrackDetailControllerTest' --tests 'org.example.music.track.service.TrackDetailServiceTest' --tests 'org.example.music.track.TrackDetailIntegrationTest'`，要求相关测试全通过、必要容器测试无跳过；将 XML 和日志另存 build 下。
- [ ] **步骤 8：同步 API 文档。** 详情示例及字段表增加 version，明确所有可见状态返回非空当前版本、JavaScript 整数精度、首次读取及冲突恢复；草稿编辑文档链接 GET 详情，继续说明 409 和保留输入后合并重试。
- [ ] **步骤 9：范围自查并提交。** 检查全部构造点、严格 JSON、OpenAPI、文档与单接口边界；只暂存文件映射中的已改实现、测试和 API 文档，`git diff --cached --check` 后中文提交 `fix: 在歌曲详情中返回草稿编辑版本号`。

## 任务 2：完整验证、独立审核与交付

**使用与产出接口：** 消费任务 1 的 GET 详情契约；产出真实测试证据、Claude 标准审核结论和 accepted 后的完成登记。

- [ ] **步骤 1：完整自验。** `$env:MINIO_INTEGRATION_TESTS='true'; .\gradlew.bat cleanTest check`。汇总 `build/test-results/test/TEST-*.xml` 的 tests、failures、errors、skipped，计算通过数，归档至 `build/reports/track-detail-version-self-check-xml` 并保存日志。必要容器测试跳过或构建失败时不得送审完成。
- [ ] **步骤 2：准备中文审核请求。** 写明唯一 GET 接口、权限与新增行为、实际全部文件（含 untracked 和用户既有 .gitignore）、设计/实现基线、API 文档、无迁移、实际命令及数量、可执行占位 Token curl；首次反馈处置写“不涉及”。提交实现证据与请求后记录准确送审 HEAD。
- [ ] **步骤 3：调用本机 Claude CLI。** 使用 `claude --print` 和 `--output-format json`，只开放 Read/Glob/Grep/Bash，禁用 Write/Edit/NotebookEdit；提示先读 AGENTS.md、CLAUDE.md、当前请求，核对范围并独立执行带 MinIO 的 `cleanTest check`。分别保存 `build/reports/track-detail-version-review-round1.json`、stderr 和验证日志/原始 XML。
- [ ] **步骤 4：监控并核实审核证据。** 等待进程实际退出，核对退出码、可解析 JSON、is_error、permission_denials、result、session_id、实际 modelUsage 和独立 XML 数量；不以 CLI 退出 0 代替 accepted。
- [ ] **步骤 5：消费结论。** 原样保存有来源的审核 result，另列 Codex 处置；needs_revision 按原编号逐项核实后范围内修复、验证并 `--resume <session_id>` 复审，最多三轮；escalated 停止修改交由用户裁定。
- [ ] **步骤 6：accepted 后完成登记。** 更新计划勾选和设计状态、任务书已完成区、PROJECT_STATE.md，记录测试实数、审核会话与范围；中文提交完成材料，检查最终工作树并向用户说明前端应从 GET 详情取得 version。推送依用户后续指示执行。

## 计划自审

- 设计中的响应、权限、读取来源、无损精度、冲突恢复及只读要求均有对应实现或测试步骤。
- 生产变更限于 GET 详情；PATCH 仅作为集成回归调用和既有文档交叉引用。
- 任务 1 交付可独立验证的响应契约；任务 2 交付完整回归与项目要求的独立审核结论。
- 签名和字段位置在任务 1 统一定义，测试仅复用现有组件，不引入执行环境或依赖变化。
