# 本人再次验证身份接口设计审核处置

日期：2026-11-03。审核对象：POST /api/v1/users/me/reauthentications书面设计2a69c48，第1/3轮设计审核accepted，零阻断、一条非阻断。原提交相对f20c9ec只新增244行设计文档，工作区干净，未推送。

用户提供Claude独立验证：MINIO_INTEGRATION_TESTS=true，gradlew.bat -p D:\code\Java-code\projects\music cleanTest check --console=plain，BUILD SUCCESSFUL，10分19秒，1105通过/0失败/0错误/0跳过、96类，MySQL8.4/Redis8.10.2/MinIO均实跑。首次后台调用未找到Wrapper、未执行构建，重跑成功；此为既有代码基线证明，不是本接口实现验证，也不冒称Codex本轮执行。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断，设计§6原103行username投影无用途 | 成立：JwtTokenService.encode只从授权快照使用userId/userRole/tokenVersion；本接口四字段响应不含username，身份/密码/邮箱绑定和诊断也无需用户名 | 认可，属于设计数据最小化建议，不影响原accepted | 采纳删除字段；不通过增加用户名日志制造用途，避免额外个人资料记录；原建议也允许删除且声明微调无需重审 | 设计§6删除新投影username，§8签发改用仅ID/账户角色/版本的最小身份；实施计划固定五字段数据库投影及三字段JwtTokenSubject，既有授权快照/登录响应不改，不用null填充旧record | 已源码核实，无运行时代码变更；实际JPQL五字段、签发字段及无用户表写入由计划任务3/4/7验证，待实现审核复核 |

报告C7列举502属于描述笔误：本设计§9及发送之外的验证流程无502业务分支，实施计划沿已确认错误表，不引入发送错误码。这不构成新的阻断或业务争议。

按原协作约定由Codex唯一实现、Claude独立只读审核；现进入writing-plans，计划确认后才编码。不自动推送或开始后续邮箱变更接口；本轮只更新设计、处置记录和实施计划，无需为文档重跑全量基线。
