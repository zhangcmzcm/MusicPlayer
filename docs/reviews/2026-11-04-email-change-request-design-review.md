# 新邮箱变更验证码申请接口设计审核处置

日期：2026-11-04。对象：POST /api/v1/users/me/email-change-requests，设计提交6f29752，设计前代码基线36cf271，main分支。用户提供Claude第1/3轮设计审核结论accepted，零阻断、一条非阻断；允许继续编写实施计划。设计accepted不等于实现accepted，后续仍须独立实现审核。

审核者独立执行 `$env:MINIO_INTEGRATION_TESTS = "true"; .\gradlew.bat cleanTest check --console=plain` 并聚合JUnit XML：BUILD SUCCESSFUL，10分24秒，1285通过、0失败、0错误、0跳过，103类，MySQL/Redis/MinIO实跑。此为用户提供的既有代码基线证据，不是Codex本轮执行，也不是新接口实现验证。

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断，设计原§3第44行及§9第138行 | 成立：GlobalExceptionHandler无HttpMediaTypeNotSupportedException处理，1013行Exception兜底返回500 INTERNAL_ERROR；519—531行415仅对应音频UNSUPPORTED_AUDIO_FORMAT；ReauthenticationRequestReader的媒体类型分支抛400校验异常。原“沿用现有媒体类型错误契约”无事实依据 | 认可，415状态本身已确认，缺少稳定code及落地映射的准确说明，不改变原accepted结论 | 采纳UNSUPPORTED_MEDIA_TYPE及urn:problem:music:unsupported_media_type。采用当前接口专用EmailChangeUnsupportedMediaTypeException与advice映射，避免新增全局HttpMediaTypeNotSupportedException处理改变其他端点行为；符合审核建议的专用映射方向 | 已修正设计§3/§9，明确缺失或非法Content-Type、非application/json、显式非UTF-8均415；同步修正type构造为现有code.toLowerCase()保留下划线。实施计划任务1/5/6固定异常、映射和HTTP/OpenAPI/文档验证 | 415专用异常/advice映射、真实HTTP及OpenAPI契约测试已实现并自验通过；旧再次验证非JSON仍400。任务1/5及最终48项真实HTTP链路测试提供证据；带MinIO的最终全量1445通过/0失败/0错误/0跳过、114类。已实现待Claude独立实现审核复核，不自行标记审核方已关闭 |

计划提交076415d阶段仅修改文档，未改生产/测试代码、迁移、配置或依赖。设计声明微调依审核报告允许免设计重审，沿用该设计轮次，不重置审核循环；随后用户授权实现，实施证据单独登记于实现审核材料。

审核报告C13“验证码/challengeId不返回”与同报告A7及设计§6存在文字冲突：验证码不返回，challengeId必须随201响应返回，二者均不入日志。按已确认设计实施，不把该概述误作取消challengeId响应的新要求。

执行方式继续采用项目既定约定：Codex唯一实现，Claude独立只读审核，不派发实现子代理。用户已批准计划并以“开始吧”授权编码，现已完成实现全量自验及材料整理，待Claude独立实现审核；不自动推送。
