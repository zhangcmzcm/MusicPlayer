# 找回密码历史密码摘要兼容实现审核请求

## 本次接口

唯一改造接口为POST /api/v1/users/password-resets。本轮首次提交独立实现审核。设计f0f2abe已获第1/3轮设计审核accepted，实施计划b344720已由用户确认。当前实现及自验完成，独立实现审核待进行。

## 需求与权限

用户zhangcm为项目开始前的存量账号，提交重置时因旧摘要未通过BCrypt格式校验返回503。用户选择方案A，完整邮箱验证票据授权后，非空历史摘要可替换为当前BCrypt摘要，null或String.isBlank为空白的旧值503。

公开接口，USER/ADMIN同规则。目标取服务端票据绑定，经当前指针、邮箱、角色、认证版本及prepared复核，主动Bearer按现有安全链处理。请求为resetTicket/newPassword，成功204空正文及no-store。

## 声称实现的行为

- Rules新增isSupportedPasswordHash识别现有60字符BCrypt格式，前缀2a/2b/2y、成本04至31及现有字符范围。requirePasswordHash复用该识别，继续严格校验新摘要和验证码摘要。
- requireStoredPasswordHash校验旧值非null且非空白。SnapshotReader读取非空存量原文，保留大小写及首尾字符。
- Service仅在当前支持的BCrypt旧值上调用matches，匹配时400 NEW_PASSWORD_SAME_AS_OLD并保留票期限。非空历史值直接生成当前BCrypt摘要，历史分支matches为0。
- 所有重置分支执行hasher.hash及严格新摘要校验，再单Lua明确消费票据，之后使用原prepared单次MySQL CAS。旧值原文参与既有binary匹配，成功更新密码、版本加1及UTC更新时间。
- null/空白、支持格式比较异常、新摘要生成或校验故障、版本上限发生在消费前，503及原票期限保留，已扣额度保留。
- 消费未知零写事务、消费后故障保持消费、提交确认丢失可能已改密、重复请求和并发冲突均按已确认策略处理。
- 合成历史账号通过真实三步申请/邮件处理/兑换/提交获得204，当前BCrypt摘要验证成功，正常登录入口可用，旧Token401。覆盖USER/ADMIN、32字符十六进制大小写、首尾空格、broken、非法BCrypt成本03/32、短格式及255字符旧值。
- 消费后并发修改旧值大小写或首尾字符，真实CAS一次且0行400，保留并发写入的原始值。
- API/OpenAPI/部署文档补充历史值兼容、同密码适用范围及503边界，真实OpenAPI测试验证说明。

## 数据库迁移与依赖

数据库迁移为无，现有password_token VARCHAR(255) NOT NULL及专用resetPasswordIfSnapshotMatches投影/SQL/事务组件作为依赖复用。生产变更集中在Rules、SnapshotReader、Service、Controller四份文件。现有Redis协议与额度、安全链及全局错误映射供当前接口使用。

null为应用防御单测注入，真实MySQL集成使用可存储的空串、空格、制表/换行及Java空白字符，验证503、票状态/TTL及已扣两层额度。

## API、设计与计划

- API文档为docs/api/user/confirm-password-reset.md。
- 部署文档为docs/deployment/password-reset-confirmations.md。
- 设计为docs/superpowers/specs/2026-11-11-password-reset-legacy-hash-design.md。
- 计划为docs/superpowers/plans/2026-11-11-password-reset-legacy-hash-plan.md。

## 基线与源码冻结

功能基线main @ ff191ac，设计f0f2abe，计划b344720。任务提交为c277612（规则）、778bae9（读取和分支）、5f940a1（真实恢复及CAS）、3a4ffdc（API/OpenAPI/部署及定向验证）。

源码/测试冻结为3a4ffdc。完整check后仅登记本审核材料、设计及计划状态，源码/测试相对冻结点零差异。交审对象为main上本审核材料所在提交，以git rev-parse HEAD取得实际对象。

## 实际测试

### 最终完整验证

源码/测试冻结3a4ffdc，Windows PowerShell实际执行如下。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

退出码0，BUILD SUCCESSFUL，用时16分39秒。cleanTest清除原结果，test实际运行，新鲜JUnit XML汇总172类2034通过、0失败、0错误、0跳过。MySQL8.4、Redis8.10.2、MinIO均实跑，S3AudioStorageIntegrationTest为5通过/0失败/0错误/0跳过，跳过项为无。

找回密码模块46类435项全部通过。相对原1973项基线，当前六个测试类净增61项，套件数172。

容器关闭期间出现既有Hikari连接关闭告警，构建结果依据实际退出码及新鲜XML统计。

### 最终定向验证

```powershell
cmd /c gradlew.bat test --tests "org.example.music.user.*PasswordResetConfirmation*Test" --tests "org.example.music.user.*PasswordResetVerification*Test" --tests "org.example.music.user.*PasswordResetTicket*Test" --console=plain
```

退出0，BUILD SUCCESSFUL，用时3分27秒，24类303通过/0失败/0错误/0跳过。此前任务3同命令3分35秒亦303通过，本次结果包括新增真实OpenAPI说明断言。

### 本轮测试变更

所有下列测试均在本次完整check实际运行且0失败/0错误/0跳过。

| 类 | 本轮项数 | 基线项数 | 证据 |
| --- | --- | --- | --- |
| PasswordResetConfirmationRulesTest | 28 | 2 | 支持三前缀/成本边界、未知/非法样本、存量非空及严格新/验证码入口 |
| PasswordResetConfirmationSnapshotReaderTest | 13 | 5 | 非空原文保留、null/空白防御、绑定及事务异常 |
| PasswordResetConfirmationServiceTest | 17 | 13 | 分支调用顺序、历史0次matches、同密码保护、新摘要故障零消费 |
| PasswordResetConfirmationIntegrationTest | 34 | 18 | 两角色各8种历史值真实闭环、新密码登录、旧Token失效及OpenAPI |
| PasswordResetConfirmationFailureIntegrationTest | 22 | 17 | 旧broken用例迁移，4个真实空白边界，2个历史新摘要故障保票 |
| PasswordResetConfirmationConcurrencyIntegrationTest | 13 | 11 | 历史原文大小写/首尾字符改写后的真实binary CAS冲突 |

原CodeVerifier、Verification、Ticket、数据库事务及普通登录/密码修改等回归均在完整check中复跑。严格验证码测试使用requirePasswordHash与validateChallenge拒绝非法格式，历史兼容仅在prepared旧值路径应用。

### TDD与用例迁移记录

任务1新方法缺失时编译失败，补齐实现后规则与验证码调用方回归通过。任务2新增测试在旧逻辑下30项中7项失败，分别揭示非空历史值被拒、错误matches调用及缺少分类顺序，修复后30项全部通过。任务4真实OpenAPI说明断言先有1项失败，注解更新后最终303项及完整2034项均通过。

原should_preserve_ticket_when_current_password_hash_is_damaged用broken期望503的断言，按用户确认的新兼容语义迁移。broken成为两角色历史成功恢复样本，另增真实空串/空白503保票、原TTL及计额证据。null由单测注入，数据库约束保持作为实际可达性依据。

## 变更文件清单，含新增文件

以下相对仓库根，A新增、M修改，共15份，包含本审核材料新增文件。提交材料前该文件为untracked，提交后由git status核对。

- M `docs/api/user/confirm-password-reset.md`
- M `docs/deployment/password-reset-confirmations.md`
- A `docs/reviews/2026-11-11-password-reset-legacy-hash-review.md`
- A `docs/superpowers/plans/2026-11-11-password-reset-legacy-hash-plan.md`
- A `docs/superpowers/specs/2026-11-11-password-reset-legacy-hash-design.md`
- M `src/main/java/org/example/music/user/controller/PasswordResetConfirmationController.java`
- M `src/main/java/org/example/music/user/domain/PasswordResetConfirmationRules.java`
- M `src/main/java/org/example/music/user/service/PasswordResetConfirmationService.java`
- M `src/main/java/org/example/music/user/service/PasswordResetConfirmationSnapshotReader.java`
- M `src/test/java/org/example/music/user/domain/PasswordResetConfirmationRulesTest.java`
- M `src/test/java/org/example/music/user/PasswordResetConfirmationConcurrencyIntegrationTest.java`
- M `src/test/java/org/example/music/user/PasswordResetConfirmationFailureIntegrationTest.java`
- M `src/test/java/org/example/music/user/PasswordResetConfirmationIntegrationTest.java`
- M `src/test/java/org/example/music/user/service/PasswordResetConfirmationServiceTest.java`
- M `src/test/java/org/example/music/user/service/PasswordResetConfirmationSnapshotReaderTest.java`

## curl示例

先通过前两步取得resetTicket，安全本地文件写入resetTicket/newPassword两个字段，路径放入PASSWORD_RESET_BODY_FILE。实际凭据及请求文件不入Git，生产HTTPS。

```bash
curl -i --request POST 'https://music.example.com/api/v1/users/password-resets' \
  --header 'Content-Type: application/json' \
  --data-binary "@${PASSWORD_RESET_BODY_FILE}"
```

成功204空正文，随后使用新密码走正常登录。实际账号恢复由更新后的服务通过正常三步入口完成，当前测试使用合成历史数据。

## 未决问题与取舍

业务未决问题为无。按已批准方案A，非空未知或格式异常存量值经有效票据授权可恢复，当前BCrypt值执行同密码保护。String.isBlank为空白的旧值503。历史值原文参与CAS，所有新摘要按当前编码器生成并严格校验。

本轮保留历史分支直接生成新摘要的顺序，当前BCrypt分支先比较后编码，响应路径存在耗时差异。该差异针对持有有效邮箱验证票据的账户持有人，按设计审核NOTE-002处置，测试断言历史matches次数为0。

## 审核反馈复核与处置

首次实现审核反馈为不涉及。设计第1/3轮accepted、零阻断项，非阻断意见沿原编号登记，独立实现审核待复核。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断 | 成立，V1第5行password_token为NOT NULL，null防御须单测注入，真实库可存空串/空白 | 认可，测试可达性建议 | 认可，单测/真实MySQL分配可验证同一边界 | Rules和SnapshotReader使用null/空白单测，Failure用真实空串/空白验证503/保票/原TTL/计额 | 完整2034通过/0失败/0错误/0跳过，待独立实现审核复核 |
| NOTE-002 非阻断 | 成立，历史分支省去matches，持有效票据者可观察自身旧摘要格式相关的低信息量耗时差异 | 认可，报告允许不采纳补时建议 | 伪比较为可选方案，已确认直接编码的额外BCrypt开销收益低 | 本轮不采纳补时伪比较，保持已确认分支顺序 | Service单测及16个真实历史用例验证matches为0，完整2034通过，待独立实现审核复核 |

当前无已收到且未处理的实现阻断项或争议。本材料供Claude独立只读实现审核，反馈后由Codex按AGENTS.md第3.1节核实及处理。
