# 本人再次验证身份接口设计

日期：2026-11-03。

状态：设计2a69c48已获独立第1/3轮accepted，零阻断、一条非阻断；NOTE-001按认可方向删除未使用username投影字段，签发使用最小身份。用户已确认[实施计划](../plans/2026-11-03-reauthentication-plan.md)并授权实施；实现及文档已编写，带MinIO的cleanTest check为1285通过/0失败/0错误/0跳过、103类，新增180项。[首次实现送审材料](../../reviews/2026-11-03-reauthentication-review.md)已整理，等待独立实现审核；[设计意见处置](../../reviews/2026-11-03-reauthentication-design-review.md)待实现审核复核。设计accepted与Codex自验不替代实现accepted。

基线：main @ f20c9ec，与origin/main同步，开始本设计时工作区干净。此前发送接口POST /api/v1/users/me/reauthentication-email-requests已获独立第1/3轮accepted，零阻断、零非阻断；独立带MinIO的cleanTest check为1105通过/0失败/0错误/0跳过、96类，其中发送接口8类106项。这些是既有基线证明，不是本接口测试结果。本机.env及Redis端口排障不纳入Git设计变更或生产部署验收。

## 1. 目标、顺序与单接口范围

仅设计 `POST /api/v1/users/me/reauthentications`：有效登录本人通过当前密码或当前绑定邮箱的专用验证码再次证明身份，签发带新auth_time的访问Token，供后续修改邮箱等敏感操作使用。

已确认路线为：认证时间绑定加固 → 当前邮箱专用验证码发送（已完成）→ 本次统一再次验证 → 独立新邮箱变更请求 → 独立最终确认更新。一次仅实现一个Method＋Path。

本次不修改邮箱、密码、用户角色、token_version、updated_at或last_login_at，不实现新邮箱发送、最终改邮箱、refresh、注销、找回密码或任何新读取/调试接口。普通登录、注册、公开验证码用途及发送流程保持原业务契约。

## 2. 已核对的现有事实与方案选择

- AuthenticatedUserPrincipal提供Integer userId、loginRole。TokenVersionAuthenticationFilter核对JWT签名后的ID/角色/版本和auth_time，并发布AuthenticatedSessionContext(tokenVersion, authenticatedAt)；旧Token缺auth_time时上下文时间可为null。
- UserRole只有USER/ADMIN；角色是否仍允许按既有isAllowedBy语义判断。JWT role表示本次登录身份，不等同于数据库账户最高角色。
- UserRepository已有分别读取授权和密码的投影，但没有一次读取密码摘要、邮箱、角色与版本的完整再次验证快照。实体属性passwordHash映射SQL列password_token；不存在名为passwordHash的数据库列。
- users.email可为空，password_token、token_version及user_role沿用既有约束。ID保持Integer，不统一全项目ID类型、不改V1—V8。
- ChangePasswordRequest对原密码允许非全空白1—128个Java String字符，不新增ASCII或72字节限制；新密码创建规则与原凭据验证不同。本次沿用原凭据兼容范围。
- 邮件发送端已维护music:auth:reauth-email:active:<userId>，精确九字段：state、challengeDigest、userId、email、tokenVersion、reservedAtMs、expiresAtMs、codeHash、attempts；ACTIVE初始attempts=0。请求ID为32随机字节Base64URL无填充，存储只保留SHA256/Base64URL摘要。
- 发送端common.lua校验固定schema、规范整数文本、BCrypt形态及PEXPIRETIME；有效期为Redis准入后300000ms，pending租约60000ms。其读取/消费能力尚未实现，本任务补齐消费者。
- JwtTokenService现有issue显式接收验证时间；私有encode写issuer/audience/sub/jti/iat/exp/role/tokenVersion/loginType/auth_time，role会再次检查。现有过滤链不把loginType解析为公开LoginType枚举；公开枚举还被登录请求及角色选择Ticket使用，不能为本接口直接扩展它。

比较方案：A为独立再次验证Service与专用存储操作，复用PasswordHasher、既有ACTIVE协议、JWT基础签发和可信上下文；B为抽取登录/再次验证共用凭据层，能集中维护逻辑，但需同步修改已审核登录流程，回归范围更大。用户选择A，不重构登录业务。

## 3. HTTP、身份与请求契约

`POST /api/v1/users/me/reauthentications`，请求为application/json对象，使用UTF-8，charset可省略或显式指定UTF-8。普通有效Bearer JWT即可，USER/ADMIN操作本人，不要求原auth_time存在或在300秒内，避免再次验证循环；缺失/过期/签名错误的访问Token仍须先恢复有效登录。

主体、登录角色和认证版本只取服务端认证结果。无用户ID、邮箱、角色、版本、认证时间等业务参数；JSON额外字段拒绝，查询参数和自定义头不能覆盖身份、方式或时间。可信上下文缺失、类型错误或版本非法返回401 INVALID_TOKEN。

密码方式严格两个字段：

```json
{
  "method": "PASSWORD",
  "password": "example-password"
}
```

验证码方式严格三个字段：

```json
{
  "method": "EMAIL_CODE",
  "challengeId": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "code": "123456"
}
```

| 字段 | 规则 |
| --- | --- |
| method | 必填字符串，精确PASSWORD或EMAIL_CODE，区分大小写，不trim或容错转换 |
| password | 仅PASSWORD允许且必须存在；字符串、非全空白、Java String长度1—128，首尾空格原样保留；不套用新密码ASCII/72字节规则，不重新hash |
| challengeId | 仅EMAIL_CODE允许且必须存在；43位Base64URL无填充，解码32字节并重新编码必须一致；不接受补齐符、非规范编码或数字类型 |
| code | 仅EMAIL_CODE允许且必须存在；字符串、精确六位ASCII数字[0-9]，保留前导零，不接受数字/Unicode数字/空白归一化 |

缺体、null、空对象、非法JSON、非对象根、多个根、重复键（含解码后同名）、未知字段、两种方式混用、字段缺失/null、数组/对象/布尔/数字强制转字符串均400 VALIDATION_FAILED。

采用本请求局部绑定组件，先校验媒体类型，再流式识别字段、类型和重复键，构造DTO并执行Bean Validation等价入口校验。缺失、非法、非application/json或声明非UTF-8字符集的Content-Type也按本接口400 VALIDATION_FAILED处理；媒体类型及字符集名称按HTTP规则不区分大小写。不使用先于局部组件拒绝请求的consumes限制，不新增全局Converter或改变ObjectMapper/Jackson/其他接口的HTTP错误映射。控制器只调用绑定组件，不承载JSON解析或业务规则。局部字符串token和字段名读取上限均为128个Java字符（已覆盖所有合法字段），仅允许一层对象及字符串值，拒绝未知字段时不读取无界子结构；不为全项目增加JSON限制。

凭据、challengeId均只用于本请求；请求record的toString脱敏，敏感字段标注只写，不经校验错误回显。

## 4. 成功响应与现有登录的有效性

200 OK，application/json，Cache-Control:no-store，无Location。严格四字段，不复用带user资料的登录响应DTO：

```json
{
  "accessToken": "<新访问Token>",
  "tokenType": "Bearer",
  "expiresAt": "2026-11-04T06:00:00Z",
  "authenticatedAt": "2026-11-03T06:00:00Z"
}
```

accessToken为新签名JWT；tokenType固定Bearer；expiresAt为新访问Token到期，沿用现有app.jwt.ttl-seconds，示例日期不承诺固定24小时；authenticatedAt为UTC Instant，与JWT整数秒auth_time对应，不是签发时间或邮件准入时间。响应toString隐藏Token，不返回邮箱、验证码、摘要、认证版本或账户资料。

前端原子替换使用新的访问Token。原Token及其他登录不撤销，保留各自原到期和原auth_time，不会同步获得新近期资格。USER登录的ADMIN账户验证后仍为USER；ADMIN登录角色仍被允许时保持ADMIN，不产生角色选择Ticket。

后续敏感操作按已确认共同契约判断0 <= now-authenticatedAt <= 300秒（含300秒端点），缺失/窗口外返回401 REAUTHENTICATION_REQUIRED；本接口不实施该门禁，也不新增假敏感接口证明它。验证后耗时计入窗口，不保证响应后尚有完整300秒。前端不能把所有业务401统一跳登录页。

## 5. 共享额度与准入顺序

通过既有认证过滤链、可信上下文检查和请求格式校验后，先Redis原子准入，再做本接口数据库/凭据验证。

- 每用户从首次准入起算固定900000ms，最多10次；PASSWORD/EMAIL_CODE、全部Token、设备共享，不按认证版本拆桶。
- 独立key为music:auth:reauth:user:<userId>，hash精确count/resetAtMs两字段；count为规范十进制1—10，绝对到期与resetAtMs一致，首次使用Redis TIME确定窗口末端。后续准入只加计数，不续期。
- TYPE/HLEN/字段/数值/期限先检查后写；无TTL、异常延长/缩短、未知类型/多余字段/非规范数字及未知回包均503，不自动修复或放行。
- 同一Lua检查并计数；第11次及以后429，Retry-After为剩余毫秒向上取整秒，范围1—900；拒绝不增加计数、不延长窗口、不触碰邮箱ACTIVE/pending。
- 已准入后成功、密码/码错误、无绑定邮箱、绑定变化、查询/消费/签发故障均计数，不退款、不因成功或新验证码重置。入口400和认证过滤阶段的失败不进入本额度。
- 两种方式均依赖此限流；Redis不可用不降级到只验证密码。限流结果确认丢失503，可能已经扣额，不重试或补偿。
- 不新增IP桶，不更改既有登录、改密、邮件发送额度；本接口每用户额度与发送端60秒/UTC日/IP三个桶相互独立。

限流Lua固定回包：[0,0]准入、[1,retryAfterSeconds]超限、[2,0]损坏。Java严格检查数组长度、Long类型及取值；null、错类型/状态、序列化/Redis访问故障均503 REAUTHENTICATION_UNAVAILABLE。

## 6. 本人数据库快照与密码验证

新增一次主键投影读取的内部快照，精确包含userId、email、passwordHash（映射password_token）、userRole、tokenVersion，不查询未用于本流程的username，也不为保留字段新增用户名日志。Entity或密码摘要不传到Controller，快照toString隐藏摘要和邮箱。既有UserAuthorizationSnapshot不修改；本接口签发时只传递用户ID、账户角色、验证版本的最小身份数据，不构造含空占位字段的既有授权快照。

每次读取是独立只读短事务，事务的启动/查询/完成故障均译为503；不将BCrypt、Redis或JWT签发包进数据库事务，不加长期行锁、不写表。

首轮与最终复核均检查：账户不存在401 TOKEN_REVOKED；版本必须等于可信上下文的验证版本，登录角色仍被允许，否则同码401。快照中的ID/版本/角色等必需结构异常按存储故障503，不用默认值补齐。

PASSWORD流程：

1. 准入后读取本人快照，固定待验证的摘要和版本。
2. 在事务外调用PasswordHasher.matches(password, snapshot.passwordHash)，保留原输入；不限制历史字符范围/72字节，不调用hash。匹配失败401 REAUTHENTICATION_FAILED。
3. matches成功返回后立即用authenticationClock采样并截断到秒；该时间此时仍为候选，只有后续复核和签发成功才对外授予资格。
4. 独立读取最终本人快照，先检查账户/版本/角色，再按Java字符串精确比较原摘要。版本/角色失效401 TOKEN_REVOKED；摘要变化却未变版本时401 REAUTHENTICATION_FAILED，不能用旧密码验证结果继续签发。
5. 仅用与验证版本一致的最终快照及原loginRole调用专用签发入口。用户名并发变化不影响身份；密码方式不要求邮箱绑定或邮箱不变。

密码成功不消费或取消现有ACTIVE/pending邮件请求，不重置任何额度。

## 7. 邮箱验证码快照、错误次数与一次性消费

仅使用既有music:auth:reauth-email:active:<本人ID>；不访问别人的key，不接受公开REGISTER/LOGIN或未来新邮箱验证码。PREPARING/PREPARED属于发送端pending，不具有本接口验证资格。

首轮数据库检查后，EMAIL_CODE需要当前邮箱。null/空白返回409 CURRENT_EMAIL_NOT_BOUND，可改用PASSWORD；不退款。非空值沿发送端trim＋Locale.ROOT小写及严格InternetAddress校验，异常存储地址503，不回显地址。版本、邮箱及用户绑定都必须与ACTIVE一致；首次缺少邮箱和消费后的邮箱变化按不同阶段区分。

### 7.1 只读获取

将原challengeId计算SHA256/Base64URL摘要，以本人ID定位ACTIVE，Lua沿用common.lua的TYPE、精确九字段、非负规范版本（支持Long.MAX_VALUE文本）、BCrypt形态、预留/准入时间、300000ms差值和真实PEXPIRETIME校验。tokenVersion不得经Lua double转换后比较。

绑定userId/email/version/challengeDigest须与本次服务端上下文一致；缺失、已到期、标识/绑定不匹配返回凭据无效，不透露他人数据，不改状态。本人key内userId与key归属矛盾等结构损坏503。attempts=0—4可验证，5视为已耗尽、不授予资格；超过5或非规范数值视为异常503，不自动修复。

成功回包固定为[0,state,challengeDigest,userId,email,tokenVersion,reservedAtMs,expiresAtMs,codeHash,attempts]：第一个为Long，后九项为字符串，并在Java再次按schema/范围解析；[1]无效或耗尽，[2]损坏。其余、null、错类型503。返回内部脱敏快照，不新增对外读取接口。

### 7.2 验证失败

事务外BCrypt比较六位ASCII code与读取的codeHash。失败后调用原子错误计数Lua，重新验证当前ACTIVE的结构、绑定、同一challenge摘要、同一codeHash及原reservedAt/expiresAt快照，且原到期仍有效。attempts允许在读取后被其他失败请求增加，不要求计数值仍等于旧读值。

同一快照0—3次错误增加一次，不延长到期；当前为4时，第5次错误使本ACTIVE删除，在同一Lua内完成。错请求/他人请求/旧快照/已被替换或消费不扣当前新请求的次数。错误计数不超过5，不取消发送端pending。

错误回包[0,newCount]表示本次已计错（1—5，5已删除）、[1,0]表示请求已无效、[2,0]表示损坏。前两类最终统一401 REAUTHENTICATION_FAILED，不返回剩余次数；第三类及无法确认结果503，不伪称本次未计错。

### 7.3 验证成功及消费

BCrypt匹配后立即采样authenticationClock并截断秒；使用消费Lua重新检查同一绑定、challengeDigest、codeHash、原reservedAt/expiresAt、ACTIVE阶段、实际到期及当前attempts<5，确认后原子删除该ACTIVE。

消费回包[0]只有实际删除成功才成立；[1]缺失/耗尽/过期/归属或快照失配，401 REAUTHENTICATION_FAILED；[2]损坏以及其他/未知回包503。不得以读到正确码就签发、不得恢复已消费记录、不重置有效期、不删除新ACTIVE或任何pending。

并发正确验证同一请求最多一个获准消费。若匹配期间其他错误请求先耗尽五次，本正确请求也不能消费；若重发激活了新ACTIVE，旧读取、错误计数和消费均不能影响它。新旧验证码数值偶然相同也必须通过challenge摘要隔离。

消费确认成功后再次短事务读取数据库：账户/版本/角色失效401 TOKEN_REVOKED；当前邮箱解绑、变更或规范化后不等于本次绑定邮箱，401 REAUTHENTICATION_FAILED，而非恢复旧记录。复核确认后才签发。

邮箱方式不要求密码摘要保持不变，但依然要求认证版本和角色有效。成功只消费匹配ACTIVE，发送pending及发送额度保持原状态。

## 8. JWT签发、认证时间和refresh兼容

在JwtTokenService新增专用再次验证签发入口，参数为已复核的最小签发身份（userId、accountRole、tokenVersion）、原loginRole、受控再次验证方式和显式verifiedAt；不读取数据库、不接收前端提供的角色/版本/时间，不提供自动now回填或缺auth_time降级入口。该身份不含username、email或摘要；既有登录入口和UserAuthorizationSnapshot契约保持原样。

新Token：

- issuer/audience、HS256、subject本人ID、随机jti及app.jwt.ttl-seconds沿用既有规则；iat为签发时钟截断秒，exp=iat＋配置有效期。
- role严格等于原认证主体loginRole，仍需被最终快照账户角色允许；tokenVersion严格等于验证上下文绑定版本，不改为验证后出现的新版本。
- auth_time来自真正密码匹配或验证码匹配完成后立即采样的候选时间；验证码还须消费成功，所有方式还须最终复核成功才能签出。响应authenticatedAt与它一致。
- PASSWORD的loginType固定REAUTHENTICATION_PASSWORD，EMAIL_CODE固定REAUTHENTICATION_EMAIL_CODE，记录本次验证来源，不伪装成首次用户名/邮箱登录，也不从已被过滤链替换掉的原JWT猜测来源。

现有公开LoginType枚举、登录请求/响应、Ticket发布/消费和登录Service不增加分支。原issue和显式legacy测试入口保留原签名及原声明值；如复用底层encode，来源字符串映射仅在私有层受控，不允许前端指定任意字符串。实际过滤链无需扩展loginType解析，必须用真实新Token验证兼容。

沿用auth_time零未来容差：不得用当前签发时间替换旧验证时间，不因时钟异常夹取或补时。签发时发现认证时间非法或位于未来，拒绝签发，以安全内部错误处理；部署保持既有时钟同步要求。Lua Redis TIME只负责额度和验证码绝对期限，不取代JWT认证时钟。

若验证后流程超过300秒，Token仍可作为普通有效登录，但后续敏感接口不得授予已过期近期资格。未来refresh必须继承可信auth_time和原验证版本，不能把刷新当重新验证，本任务不实现refresh或新会话模型。

## 9. 失败、确认丢失与事务边界

业务错误ProblemDetail、application/problem+json，携带稳定code；type沿现有urn:problem:music:加code小写规则，保留下划线。成功与错误均no-store。无403业务分支，角色失效按既有撤销语义401。

| 场景 | 状态与code | type后缀 |
| --- | --- | --- |
| 本请求绑定/校验失败 | 400 VALIDATION_FAILED | validation_failed |
| 未认证 | 401 UNAUTHORIZED | unauthorized |
| 声明/可信上下文无效 | 401 INVALID_TOKEN | invalid_token |
| 账户消失、版本撤销、登录角色失效 | 401 TOKEN_REVOKED | token_revoked |
| 密码错误/摘要失配，验证码错误/耗尽/过期/消费/替换/绑定失效 | 401 REAUTHENTICATION_FAILED | reauthentication_failed |
| 首轮EMAIL_CODE查询时未绑定邮箱 | 409 CURRENT_EMAIL_NOT_BOUND | current_email_not_bound |
| 共享用户额度耗尽，附Retry-After | 429 REAUTHENTICATION_RATE_LIMITED | reauthentication_rate_limited |
| 本流程DB/Redis故障、异常存储结构、未知/损坏回包 | 503 REAUTHENTICATION_UNAVAILABLE | reauthentication_unavailable |
| 既有认证过滤链读取账户状态故障 | 503 AUTH_STATE_STORE_UNAVAILABLE | auth_state_store_unavailable |
| 非预期哈希校验/签发/内部故障 | 500 INTERNAL_ERROR | internal_error |

JWT过期、签名错误等沿用资源服务器现有401行为，可能无ProblemDetail正文；不在本任务统一改写。REAUTHENTICATION_FAILED使用固定安全文案，如“身份验证失败，请检查凭据或重新申请验证码”，不返回占用者、当前邮箱或凭据值。REAUTHENTICATION_REQUIRED属于后续敏感门禁，不从本接口触发。

Redis脚本保证操作间原子可见，不保证错误时回滚或跨MySQL/Redis/JWT事务。额度计数未知可能已扣；错误计数未知可能已增加/耗尽；消费未知可能已删除。统一fail-closed、不签Token、不自动重试/退额度/恢复ACTIVE。

消费后数据库或JWT签发失败，验证码保持已消费，重新申请受原发送额度限制。Token已生成但HTTP响应失败/丢失时，不能承诺未签发或恢复验证码；PASSWORD可以在剩余额度内再次真实验证，EMAIL_CODE须重新申请或改用密码，服务端不缓存可重放成功响应。

最终DB复核与签发不是原子快照。复核之后并发撤销可能使刚签出的原版本Token立即无效；只签原验证版本，随后真实认证过滤链应TOKEN_REVOKED，不能把旧证明升级为新版本。这一边界必须写入API文档并用竞态集成测试证明。

## 10. 分层、变更边界与隐私

- Controller仅调用请求局部绑定组件、读取可信主体/上下文、调用Service和构造200/no-store。
- ReauthenticationService协调两种验证流程、候选认证时间及最终复核；可将两种验证分成聚焦的内部组件，但不抽取或改造已审核登录业务。
- UserRepository仅新增主键快照投影；读取事务在Service边界封装，不把Entity或摘要暴露给Controller。
- 新的再次验证存储组件负责独立额度和既有ACTIVE的读取/失败/消费Lua。可复用发送端common.lua严格验证助手，不改变既有发送四操作、激活/替换协议或pending schema，不重置任何TTL。
- JwtTokenService仅增加专用入口及必要的私有签发复用；既有调用契约保持，既有登录/Token测试作为回归。
- DTO/record优先，请求、响应、数据库及ACTIVE快照的字符串表示隐藏密码、码、摘要、完整邮箱、challengeId及Token。
- 新业务异常由GlobalExceptionHandler统一映射，复用已存在的CurrentEmailNotBound/InvalidToken/CurrentUserRevoked语义。新错误及日志使用安全诊断副本，保留traceId、异常类型和调用位置，剥离底层原消息/cause/suppressed；不将原SQL、Redis参数、JWT编码异常链直接传给兜底日志。

不新增依赖、配置、迁移、公共验证码用途、SMTP发送能力、安全公开路径、代理信任或Actuator暴露；沿用anyRequest().authenticated()。不停止或重配本地服务，不把本机.env纳入本接口实现提交。

## 11. 验证与独立审核

实现阶段必须覆盖：

- 请求：两种成功形态；缺体/null/空对象/非对象/多个根、方法值、重复转义键、未知字段、字段混用、数字或其他类型强制转换、Content-Type不符合契约均400且不扣额；首尾空白密码原样验证，1/7/8/128及越界、历史非ASCII密码兼容，验证码ASCII与前导零。
- 身份：USER/ADMIN、ADMIN账户以USER登录、缺auth_time的旧Token；未认证、坏签名/过期/声明错误、版本撤销；伪造ID/邮箱/角色/版本/auth_time的头、查询、字段不能覆盖本人，可信上下文无效不进入业务。
- 额度：两种方式/多个Token共享首个900000ms固定窗口，10次与第11次、并发边界、成功/失败/无邮箱/故障均计数；429不续期、不消费码，不因成功/重发重置；类型/字段/数值/TTL损坏及未知回包503，Redis不可用时密码同样拒绝。
- ACTIVE：真实发送接口先生成，再由本接口消费，不只手工造成功记录。九字段/schema、实际PEXPIRETIME、原300000ms期限、规范Long.MAX_VALUE版本、过期、PREPARING/PREPARED、无TTL/期限漂移、码Hash/字段损坏；次数0—4、第五次删除、耗尽与异常次数。
- 并发：同一正确码最多一次消费；多个错误请求最多累计到5；正确匹配后遇第五错先完成则不签发；重发替换与旧正确/错误请求交错不能消费/计错新请求，强制同码值也按challenge区分；PASSWORD成功不影响ACTIVE/pending，EMAIL_CODE成功不影响pending。
- 绑定复核：验证期间真实MySQL更新密码/版本/角色/邮箱或删除账户，按各方式及阶段得到401/409而不发Token；密码摘要变化未变版本也拒绝；消费后最终邮箱解绑返回REAUTHENTICATION_FAILED。整行比较users不变，不写活动字段、不递增版本、不产生登录Ticket。
- 时间/签发：密码或码匹配后立即记录；消费/复核/签发延迟不刷新auth_time；响应authenticatedAt与JWT一致，新来源声明正确、原角色/版本保留，新Token走实际过滤链；超过300秒仍普通有效但不回填近期时间；零未来容差不放宽，无legacy签发调用。
- 故障：DB事务启动/查询/完成、额度/读取/计错/消费Redis故障、实际执行后丢回包与未执行区分、消费后DB/签发失败、响应丢失边界；均不伪造成功、不恢复验证码、不退款，隐私日志不含嵌套/抑制异常中的敏感内容。
- 最后复核后真实并发版本递增，证明签出的仍是原版本且在实际过滤链TOKEN_REVOKED；原Token/其他登录在版本未变时继续可用并保留原auth_time，新Token不提升角色。

使用MySQL8.4、Redis及真实BCrypt、实际签名JWT/过滤链的Testcontainers集成测试，不以H2或mock外部状态替代。MVC主体注入测试只证明HTTP契约，不能替代真实链路。时间测试推进认证Clock可验证JWT时间；Redis期限使用真实过期指令/合法夹具与Redis TIME，不能靠Java Clock模拟。并发用限时闩锁/future并在finally清理，无sleep或生产测试时间入口。

因复用JWT签发及跨接口认证，实施基准执行MINIO_INTEGRATION_TESTS=true的cleanTest check --console=plain，包含既有登录/版本/认证时间、改密、邮件请求及MinIO回归；如实报告通过/失败/错误/跳过及逐类数量，必要容器跳过不算通过。当前文档提交不重复跑基线或声称新接口验证通过。

实现时新增docs/api/user/reauthenticate-current-user.md，包含Method/Path、权限、两种严格请求、四字段响应、角色/版本/旧Token语义、固定额度、五次错误、所有稳定错误码与下划线urn、消费/响应不确定性、JSON双平台curl及部署条件。OpenAPI同步，示例无真实Token、密码或验证码。按AGENTS完整送审，设计/自验不替代独立实现accepted。

## 12. 示例与自审门禁

以下为后续实现完成时的调用契约示例，当前仅设计，不表示端点已可用。ACCESS_TOKEN为原有效登录Token，示例密码/验证码/标识为虚构值，需替换为本人实际凭据且不记录到日志。

```sh
curl --request POST 'http://localhost:8080/api/v1/users/me/reauthentications' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data '{"method":"PASSWORD","password":"example-password"}'
```

```powershell
'{"method":"EMAIL_CODE","challengeId":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","code":"123456"}' | curl.exe --request POST 'http://localhost:8080/api/v1/users/me/reauthentications' --header "Authorization: Bearer $env:ACCESS_TOKEN" --header 'Content-Type: application/json' --data-binary '@-'
```

自审已核对：单接口范围、与既有发送协议兼容、两种请求严格互斥、历史密码验证范围、10次固定额度与5次验证码错误相互独立、ACTIVE归属及一次性消费、版本不升级、角色不提升、验证时间不被签发刷新、旧登录保持、失败与确认丢失不恢复、真实测试及部署条件。无待决定的业务分支、未完成条款或授权外接口。

用户审阅本文件后，进入writing-plans制定本接口实施计划；书面设计和计划均获确认后才编码。生产代理/IP桶、真实SMTP、Redis7+及ACL、既有认证加固全实例与时钟同步验收仍按既有部署文档单独执行；不以本设计或本机调通代替生产环境验收。
