# F01 收藏歌曲设计审核记录与反馈复核

登记日期为2026-11-12，时区为Asia/Shanghai。

## 审核来源与范围

- 用户转交Claude独立设计审核报告，任务为F01收藏歌曲书面设计，对应 `PUT /api/v1/users/me/favorites/{trackId}`。
- 报告结论为accepted，第1/3轮，阻断项为0，非阻断项为NOTE-001、NOTE-002。
- 审核基线为main @ 08c78bb，提交内容为184行设计文档。
- 设计文件为 `docs/superpowers/specs/2026-11-11-favorite-track-design.md`，后续实施计划为 `docs/superpowers/plans/2026-11-12-favorite-track-plan.md`。
- 原文来自用户附件 `C:\Users\zhangcm\.codex\attachments\8322023d-380b-4a4c-8c25-feb7f6cc7b90\已粘贴的文本.txt`，该原文路径仅本机可访问，本记录保存审核结论、验证摘要和两项意见的处置证据。

## 审核方报告的独立验证

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

报告记录172套件，2034通过、0失败、0错误、0跳过，MySQL、Redis与MinIO实际执行，BUILD SUCCESSFUL、退出码0。此结果由用户转交的Claude报告提供，针对08c78bb文档提交的代码基线。

当前收藏代码和V9迁移已实现，专项验证及完整cleanTest check通过，179套件2097通过、0失败、0错误、0跳过。该实现验证由Codex执行，详细证据见[实现审核请求](2026-11-12-favorite-track-review-request.md)。

## 审核反馈复核与处置

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001，非阻断 | 成立。08c78bb设计第4节只列出user_id与track_id两条外键，created_by与updated_by的策略存在实施歧义。V2中tracks的created_by、updated_by及media_assets的created_by为RESTRICT外键，直接套用将引入与用户删除清理规则的冲突风险 | 认可非阻断，属于实施前澄清，设计accepted保持原结论 | 认可显式决定审计列外键策略并验证用户级联的方向 | V9外键集合为user_id、track_id两条CASCADE，审计列使用普通INT，由服务端写入当前userId并保持首次值 | 真实MySQL专项通过。FavoriteRepositoryIntegrationTest核验恰有两条CASCADE外键，并通过只关联收藏的用户真实DELETE验证清理，提交独立实现审核核验 |
| NOTE-002，非阻断 | 成立。设计第3节对缺少路径段沿用路由行为作了说明，具体状态码和响应格式尚缺证据 | 认可非阻断，属于测试与API文档完整性 | 认可通过真实路由测试固定行为再补API文档，避免推测状态码 | FavoriteTrackIntegrationTest覆盖三种请求路径、已认证与未认证场景、Service无调用和数据库无新增断言，API文档已记录实测结果 | 真实路由专项通过。有效认证后三种路径均为500 INTERNAL_ERROR、application/problem+json，未认证为401。原因是现有全局兜底将未匹配资源异常转为500。正常匹配接口的资源不可见仍为404 TRACK_NOT_FOUND，提交独立实现审核核验 |

两项意见均按原非阻断分级处理。NOTE-001的约束决定沿用已确认的本人审计与用户删除级联规则。NOTE-002的实际状态已由测试固定并写入API文档。

## 当前验证与接续

用户已确认设计与实施计划。Codex已实现收藏接口并完成专项与完整测试，目前提交实现审核。

实现送审时附本记录中NOTE-001、NOTE-002的实际验证证据，保持原意见编号。
