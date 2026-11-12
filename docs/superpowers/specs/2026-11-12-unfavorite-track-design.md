# F02 取消收藏歌曲接口设计

日期为2026-11-12，时区为Asia/Shanghai。

状态为用户于2026-11-12批准书面设计与实施计划，代码及完整验证已完成，当前提交独立实现审核。

设计核对基线为 `main @ ae7331d`，工作目录为 `D:\code\Java-code\projects\music`，设计开始时Git工作树干净。本机AGENTS.md第8节第7步已配置由Codex自动调用Claude Code独立审核，该文件受.gitignore规则管理。

## 1. 目标与已确认契约

当前登录用户取消本人对指定歌曲的收藏。

| 项目 | 已确认规则 |
| --- | --- |
| HTTP接口 | `DELETE /api/v1/users/me/favorites/{trackId}` |
| 认证要求 | 有效Bearer JWT，沿用既有认证链 |
| 身份来源 | AuthenticatedUserPrincipal.userId |
| 请求体 | 无 |
| 路径歌曲ID | 正整数Long，1至9223372036854775807 |
| 成功状态 | 204 No Content，空正文，Cache-Control为no-store |
| 重复取消 | 幂等返回204 |
| 本人未收藏 | 幂等返回204 |
| 歌曲已彻底删除或不存在 | 幂等返回204 |
| 可取消的歌曲状态 | DRAFT、PUBLISHED、ARCHIVED均允许 |
| ADMIN操作范围 | 当前登录用户本人收藏 |
| 再次收藏 | 创建新记录，以该次收藏时间写入审计字段，权限遵循F01 |
| 专用限流 | 首版沿用F01决策，暂不增加取消收藏专用限流 |

## 2. 方案比较与选择

| 方案 | 特点 | 选择结果 |
| --- | --- | --- |
| DELETE本人收藏资源 | 与F01的PUT使用同一资源路径，无请求体，直接表达移除与幂等状态 | 用户选定 |
| POST动作路径 `/api/v1/users/me/favorites/{trackId}/remove` | 通过动作路径与业务规则表达取消及重复请求语义 | 比较后选定DELETE方案 |

持久化复用V9创建的user_favorites表、联合主键及外键级联。当前单接口范围为F02，F03收藏列表仍按任务书独立设计。

## 3. 本人归属与歌曲可见性

USER和ADMIN都可取消本人收藏，包含当前角色已不可见的歌曲。管理员以USER角色登录也可取消本人对DRAFT或ARCHIVED歌曲的已有收藏。

用户ID取自服务端验证后的认证主体。即使其他用户已收藏该歌曲，本人没有收藏时也返回204，其他用户的记录保持原值。请求中的用户ID、角色、管理员标记和自定义身份头均不参与归属判断。

取消操作的歌曲锁定读用于协调并发。歌曲的DRAFT、PUBLISHED、ARCHIVED状态均允许进入本人收藏删除流程。歌曲记录缺失时，既有外键级联已保证其收藏关系清理，返回幂等成功。

Service公开入口使用 `@PreAuthorize("isAuthenticated()")`，路由受现有SecurityConfig默认认证规则覆盖。JWT的签名、issuer、audience、有效期、subject、token version和登录角色校验沿用既有实现。

## 4. HTTP校验、响应与错误

Controller沿用Spring MVC的Long类型转换和Bean Validation正数校验。零、负数、小数、非数字和Long溢出均返回400 VALIDATION_FAILED。ID为1和Long.MAX_VALUE时合法绑定，资源或关系缺失均按204处理。

认证过滤链先执行，已认证后进入路径参数校验。请求无业务查询参数，未知查询参数、附带正文和自定义身份头沿用F01忽略规则。成功响应固定为204、空正文和Cache-Control no-store。

| HTTP | 稳定code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 正常匹配路由的歌曲ID格式、正数或范围校验失败 |
| 401 | UNAUTHORIZED | 未提供有效认证 |
| 401 | INVALID_TOKEN | 认证必要声明无效 |
| 401 | TOKEN_REVOKED | 用户不存在、token version失效或登录角色失效 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态存储故障 |
| 503 | FAVORITE_STORE_UNAVAILABLE | 收藏数据访问、锁等待、死锁或事务故障 |
| 500 | INTERNAL_ERROR | 未预期内部错误，沿用统一处理 |

正常匹配本接口后的歌曲不存在、收藏不存在和重复取消，均使用204语义。USER与ADMIN的正常已认证调用遵循相同的本人删除规则。

业务错误使用ProblemDetail及application/problem+json，类型遵循 `urn:problem:music:*`。收藏存储故障复用现有异常和映射，其type为 `urn:problem:music:favorite_store_unavailable`，title为“收藏存储不可用”，detail为“歌曲收藏暂时无法完成”，响应显式携带no-store。

JWT解码失败沿用既有资源服务器401与WWW-Authenticate行为，部分响应没有业务ProblemDetail正文。其他认证错误与缓存头沿用既有认证组件。

缺ID、空路径段和尾斜杠属于未匹配路由的输入，按当前路由与全局异常处理执行。F01已有此类输入转500的实测记录，F02实施时对DELETE请求逐路径验证实际状态、媒体类型和处理器，并将结果写入API文档。验证同时断言取消收藏业务无调用、数据库无修改。

## 5. 存储与事务

user_favorites的联合主键为(user_id,track_id)，user_id为INT，track_id为BIGINT。两条既有外键分别级联用户删除与歌曲删除，四个审计字段保存当前收藏记录的首次值。

取消操作物理删除本人收藏行。删除范围固定包含两个主键字段，SQL参数取认证用户ID和已校验的歌曲ID。其他用户与歌曲的记录及审计字段保持原值。

事务步骤如下。

1. 在短写事务中调用现有FavoriteRepository.lockTrackStatus(trackId)，按主键执行SELECT FOR UPDATE。
2. 歌曲不存在时结束事务并返回成功。
3. 歌曲存在时按user_id和track_id执行条件DELETE，任意现存状态均允许。
4. 删除影响0行或1行均完成事务提交，外层Service返回后Controller构造204。

取消后通过F01再次收藏时生成新的收藏记录。新记录审计值由F01现有UTC Clock及微秒时间规则生成，使用已验证的UTC文本与MySQL DATETIME(6)转换。验证时以可控的不同时间点确认取新时间。

外层Service通过Spring代理调用事务Service，在事务开始和提交边界之外捕获DataAccessException、TransactionException，复用FavoriteStoreUnavailableException。数据库故障、锁等待超时和死锁返回503，事务回滚。客户端可在认证有效时重试同一DELETE。

提交前失败时，删除回滚，原收藏及审计字段保留。实际提交成功但提交确认丢失时，调用方可能收到503，后续重试因记录已缺失返回204。数据库中收藏关系不存在时，幂等成功仍以本次事务正常完成为条件。

## 6. 并发规则

### 收藏与取消收藏

F01和F02均先取得同一歌曲的排他行锁，并持有至事务结束。同一用户对同一现存歌曲同时收藏与取消，两个操作都提交成功时，后取得歌曲锁的操作决定最终收藏状态。

收藏先提交、取消后提交，最终关系缺失。取消先提交、收藏后提交，最终关系存在，并以新收藏时刻生成审计字段。并发排序依据数据库锁与提交，HTTP响应到达顺序可受网络影响。

同一歌曲上不同用户的收藏与取消也会串行执行，延续F01已确认取舍。事务内只执行歌曲主键锁定读和本人收藏条件删除，其他歌曲的操作可独立完成。

### 重复取消与用户隔离

同一用户对同一歌曲并发取消，正常完成的请求均返回204。不同用户操作同一歌曲，各自只删除自己的记录。同一歌曲上其他用户的收藏时间及审计字段保持原值。

### 歌曲归档与彻底删除

取消与归档通过歌曲行锁协调，两种提交顺序均可删除本人收藏。歌曲归档不改变取消资格。

取消先提交、歌曲删除后提交，收藏关系保持缺失。歌曲删除先提交，取消锁定读取得歌曲不存在，返回204。既有外键级联保持歌曲删除与所有对应收藏关系清理的事务一致性。

既有歌曲删除流程先处理媒体对象再删除数据库记录。取消可在该流程尚有歌曲记录时移除本人收藏，也可在歌曲记录删除后返回幂等成功。

## 7. 组件与文件范围

| 组件 | 职责与预期变更 |
| --- | --- |
| FavoriteTrackController | 复用现有类与资源前缀，新增DELETE方法、参数校验、204及OpenAPI注解 |
| UnfavoriteTrackService | 新增外层入口，方法级认证、事务调用及存储故障转换 |
| UnfavoriteTrackTransactionService | 新增短事务，歌曲锁定读、缺失成功、本人收藏条件删除 |
| FavoriteRepository | 复用lockTrackStatus，新增按userId与trackId删除的方法 |
| FavoriteStoreUnavailableException及统一503映射 | 复用现有实现 |
| favorite测试目录 | 新增取消的HTTP、Service、代理及真实数据库并发测试，复用现有集成准备 |
| FavoriteTrackControllerTest | 按Controller新增注入补充取消Service测试依赖，保留F01 PUT契约断言 |
| docs/api/favorite/unfavorite-track.md | 新增中文API文档、请求响应、错误、并发与curl |
| docs/superpowers/plans及docs/reviews | 后续实施计划、审核材料和反馈处置 |

当前数据结构满足F02，数据库迁移为无。实现依赖现有JPA、Validation、安全框架及测试starter，接口路径与身份使用现有类型和约定。

## 8. 测试与验证

### HTTP、安全与幂等

- USER、ADMIN、管理员以USER登录均可取消本人三种歌曲状态的已有收藏。
- 本人未收藏、重复取消、歌曲不存在或已删除时，均为204空正文及no-store。
- 其他用户收藏保持原值，伪造query、正文、用户头或角色头只能按当前认证用户处理。
- 零、负数、非数字、小数及Long溢出返回400，合法边界1和Long.MAX_VALUE进入业务语义。
- 无认证、过期、错误签名、声明无效、版本失效和角色失效经过实际认证链验证，业务无调用。
- 错误媒体类型、code、type、固定detail与缓存头符合现有契约，故障详情得到屏蔽。
- 缺ID、空路径段、尾斜杠使用真实DELETE路由验证，记录已认证与未认证场景。
- OpenAPI包含DELETE参数和准确的204、400、401、500、503，204无content和requestBody；既有PUT定义与响应契约有回归断言。

### 真实数据库与事务并发

- Testcontainers MySQL 8.4真实执行条件DELETE，断言0行和1行结果、用户与歌曲双主键范围及其他记录完整性。
- 复用既有F01收藏流程，再取消，再次收藏，断言旧记录删除及新审计时间正确。
- 真实Spring代理验证认证、事务启动、回滚、提交及故障转换，成功响应发生在提交完成后。
- 定点制造删除后的提交前故障，验证原收藏及首次审计字段回滚保留；模拟实际提交后确认丢失，验证重试204且关系缺失。
- 真实同用户并发取消、不同用户同歌操作及不同歌曲独立执行，验证本人隔离和幂等结果。
- 使用真实事务同步点与数据库锁等待观察，分别驱动收藏先提交、取消先提交、归档先后提交及歌曲删除先后提交。
- 真实锁等待超时返回503，释放锁后重试正常完成，测试连接会话设置和线程资源在结束时恢复与回收。
- 使用既有歌曲删除完整入口及真实MinIO验证删除级联后再取消仍为204，其他歌曲与用户的收藏保持正确。

正常持久化及并发路径使用真实数据库。故障注入限于验证异常分支，认证链沿用现有真实JWT与Redis测试环境。

完整验证命令为以下PowerShell命令，需要强制重跑时使用cleanTest check。

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

按XML报告记录实际通过、失败、错误和跳过数量，必要容器测试需实际执行。F01最近基线的179套件2097通过记录见[独立审核](../../reviews/2026-11-12-favorite-track-review.md)，F02实现后另行验证并登记实际数量。

## 9. curl示例

调用者预先通过现有登录流程设置ACCESS_TOKEN，123为本次取消的歌曲ID。

```sh
curl --request DELETE 'http://localhost:8080/api/v1/users/me/favorites/123' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```

成功返回HTTP 204，无响应正文。合法ID对应的歌曲或本人收藏缺失时，重复执行仍为204。

## 10. 设计自检与后续流程

已核对单接口范围、本人归属、全状态允许、缺失资源与关系的幂等语义、锁顺序、删除与再收藏时间、事务故障转换、复用范围、真实测试及文档契约。

书面设计及[中文实施计划](../plans/2026-11-12-unfavorite-track-plan.md)已获用户批准，完整check为185套件2169通过、0失败、0错误、0跳过，MySQL/Redis/MinIO实际运行。当前按AGENTS.md第8节第7步由Codex自动调用本机Claude Code独立只读审核，记录实际模型、session_id、验证结果及标准结论。反馈按原编号与轮次逐项复核，accepted后登记F02完成，escalated时交由用户决策。
