# 邮箱变更确认部署与故障核实

接口：PUT /api/v1/users/me/email。复用现有MySQL、单Redis和JWT认证链，无迁移、依赖、配置项或额外后台任务。现有SMTP用于前置发码，确认阶段只协调Redis及MySQL。

## 状态与时间

消费者读取已有music:auth:email-change:active:<userId>，不改生产者schema和激活规则；pending不能兑换。Redis需支持TIME/PEXPIREAT/PEXPIRETIME，当前Compose Redis7.4及测试Redis8.10.2支持；不声称兼容Redis Cluster跨槽操作。

独立额度键music:auth:email-change-confirmation:user:<userId>，首次获准起900秒最多10次，跨Token/设备/角色。该额度不是发码额度或再次验证额度；成功/失败均不退款，429不续窗，重新登录/再次验证不重置。

应用Clock负责一次300秒近期门禁，Redis TIME负责原申请300秒期限、计错/消费时效及900秒额度。正常同步时钟，测试或运维不得通过修改应用Clock假装Redis已过期。服务不为准备或消费延长原期限。

## 数据库与撤销

SQL在Redis明确消费成功后才执行；写事务中无BCrypt、Redis、SMTP或Token签发。条件更新匹配本人ID、原始邮箱（NULL安全且二进制精确）、角色及版本，仅修改email、token_version加1和UTC updated_at。

原始数据库邮箱可有大小写/空白差异，绑定比较按原流程规范化，CAS仍使用原始值。不能以消费后新查到的快照重试写入，不能无条件保存旧Entity覆盖其他列。Long.MAX_VALUE拒绝递增，不能回绕认证版本。

事务提交后，现有认证链读到新版本便拒绝全部旧Token，包括当前操作，不签发新Token。204响应成功后前端清除旧Token并重新登录；已准入的其他请求不受本接口的跨请求回滚控制。

## 不可原子化的失败边界

| 情况 | 已知事实与处理 |
| --- | --- |
| Redis消费前拒绝 | 不主动删除申请；错码仍累计，额度不退款 |
| 第五错与正确码并发 | 第五错先失效则正确不能消费；正确先消费则迟到计错不能复活或影响新申请 |
| Redis消费实际执行但回包未知 | 返回503，码可能已消费；本次不能执行SQL |
| 消费后数据库明确失败/冲突/回滚 | 返回对应409/503，码不恢复，按原发送限流重新申请 |
| 数据库实际提交但回包未知 | 返回503，邮箱和版本可能已更新，不承诺回滚，不重试更新 |
| 204响应丢失 | 更新可能已成功；旧Token不能成功重放，不提供确认状态查询恢复接口 |

503或响应丢失后核实本人当前邮箱。若旧Token失效，恢复有效登录后读取本人资料，用户名不受改邮箱影响，可用于登录。旧Token被拒绝也可能因改密或角色变更，不能独立证明改邮箱成功。

重发与确认按Redis顺序处理：新申请先激活则旧码不能消费；旧码先消费则已获准的SQL仍可继续，提交后版本变化使同期旧版本申请失效。确认结束不按userId批量删除ACTIVE/pending，防止删除新申请；旧版本记录靠绑定核验及原TTL自然失效。

## 运维与验证

类型、字段、计数、摘要、地址规范和实际TTL损坏时503关闭放行；不要人工删键退额、恢复旧ACTIVE、重发同一码或开启自动SQL重试。不存在跨Redis/MySQL原子事务、持久化操作表或隐式补偿任务。

利用既有traceId及安全日志定位，区分前置AUTH_STATE_STORE_UNAVAILABLE与本接口EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE。日志不打印邮箱、码、challengeId、摘要、Token或原SQL/Redis异常链；不扩大Actuator暴露。

实现基准验证：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

按JUnit XML统计通过/失败/错误/跳过及类数。MySQL8.4和Redis真实容器必须运行；既有MinIO全量回归按开关执行，跳过不是通过。受控时序/提交确认丢失测试需核对真实行和状态，不以mock成功结果冒充持久化。
