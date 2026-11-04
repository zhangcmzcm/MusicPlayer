# 邮箱变更验证码申请部署说明

本文对应唯一接口POST /api/v1/users/me/email-change-requests。申请不更新用户邮箱；最终确认接口及refresh尚未实现。设计通过与本地check不代替生产邮件及网络验收。

## 运行依赖与容量

复用已有MySQL、Redis、SMTP和JWT配置，无新依赖、配置项或数据库迁移，V1—V8不变。Redis脚本使用TIME、PEXPIREAT及PEXPIRETIME，需Redis 7.0及以上；当前Compose Redis7.4满足，集成测试沿用Redis8.10.2。仅支持当前单Redis部署前提，七个键的原子准入不声称兼容Redis Cluster跨槽执行。

本用途使用独立SMTP客户端和执行器，现有JavaMailSender必须可隔离为JavaMailSenderImpl。复制既有host/port/protocol/用户名/密码/发件地址及TLS认证配置，私有Session中smtp/smtps连接、读、写各5000ms，debug关闭。不得为调试打印配置或凭据。

每实例最多4个发送任务、零等待队列，请求等待最多20秒。等待取消不代表底层任务退出，实际退出前继续占用槽位；容量不足返回503且准入后额度不退。多实例发送槽位各自独立，但Redis额度跨实例共享。关闭应用停止接单并请求取消底层任务。

SMTP接受仅表示邮件服务器接受，不保证收件箱送达。生产应验证SMTP/TLS、外网连接、发件授权、投递及超时行为；受控测试发送器只证明业务协议，不冒充真实外部送达。

## 限流、时钟和代理

私有前缀music:auth:email-change:，与登录/注册/再次验证隔离。每用户和每规范化目标邮箱各60秒一次、UTC自然日10次；每连接IP首次获准起固定60秒窗口5次。非全项目邮件总额度，不通过更换Token或设备重置。

目标及IP限流键只保存SHA-256摘要；目标按trim及Locale.ROOT小写统计，用户键保持Integer ID。当前邮箱与目标邮箱属于申请必要绑定，只在专用状态中保存，不进入日志；验证码为BCrypt摘要，challengeId仅存SHA-256摘要，日志不得打印摘要或状态对象内敏感字段。

IP来自连接remoteAddr，忽略客户端X-Forwarded-For。反向代理若使所有请求映射为同一连接IP，将共享5次/60秒额度。部署前确认代理连接地址含义与容量，当前不新增可信代理解析或绕过开关。

应用Clock负责JWT近期资格，Redis TIME负责UTC日界、冷却、租约和申请期限；两者应维持正常时钟同步，但不是可交换的时间源。申请从准入起300秒，到期不续命；pending租约60秒，发送、重发准备或再次验证不延长旧申请。

正文仅本接口限制16384字节，含尾随空白，超限400；不修改全局JSON限制或旧端点。网关超时需考虑业务20秒等待、哈希和两轮查库，客户端超时或断开不能证明申请未激活。

## 故障与运维边界

| 现象 | 含义及处理 |
| --- | --- |
| 401 REAUTHENTICATION_REQUIRED | 当前登录有效，前端保留输入并再次验证，换新Token继续 |
| 429 EMAIL_CHANGE_RATE_LIMITED | 按Retry-After等待；不通过删Redis键或重启重置额度 |
| 502 EMAIL_SEND_FAILED | 发送失败或等待超时；可能稍后收到无效验证码，不自动重试，不退额度 |
| 503 EMAIL_CHANGE_REQUEST_UNAVAILABLE | 查库/Redis故障、状态损坏、发送容量不足或激活确认丢失；查看带traceId的安全诊断 |
| 503 AUTH_STATE_STORE_UNAVAILABLE | 前置认证状态基础设施故障，业务尚未准入 |
| 409 EMAIL_CHANGE_REQUEST_CONFLICT | 发送中冲突、原绑定变化、租约失效或确定激活冲突，重新确认账户状态后重试 |

明确激活前失败时保留旧ACTIVE及原期限。激活执行后回包丢失可能已经替换旧申请，不能人工恢复旧ACTIVE、返还额度或重发相同码来掩盖不确定性。响应丢失不提供申请查询恢复，等待冷却后重新申请。

状态TYPE、字段、计数及绝对TTL异常时关闭放行并返回503，不自动删除损坏状态后发送。清理只删除当前归属pending，不能删除别的申请、恢复旧码或改变额度；清理失败依赖原租约过期。不要在生产使用测试中人为过期额度/状态的夹具。

邮件任务没有数据库或Redis能力，超时迟到结果不能激活。MySQL复核和Redis激活不是跨存储原子快照，最终确认必须另行核验绑定、认证版本及数据库唯一约束。

## 验证与观察

使用既有health/info/metrics及带traceId日志定位故障，不增加Actuator暴露端点。日志不含邮件内容、邮箱、验证码、Token、challengeId、摘要或原SMTP/SQL/Redis异常链。

实现验证执行：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

MySQL/Redis真实容器及既有MinIO回归不得跳过，聚合JUnit XML如实报告通过/失败/错误/跳过。生产SMTP投递、代理IP、容量和网关超时作为实际部署验收，不能用单元测试替代。
