# 本人再次验证身份接口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.
>
> 沿用用户及AGENTS约定：Codex在本会话独立实现，Claude独立只读审核，不派发实现或审核子代理。用户已确认本计划并授权执行，不声称已调用未提供的执行子技能，不因技能建议改变实现方或审查责任。

**Goal:** 实现POST /api/v1/users/me/reauthentications，支持PASSWORD/EMAIL_CODE再次验证本人，签发新auth_time的Token并保留登录角色与验证版本，不改变账户或其他登录。

**Architecture:** 请求局部流式绑定及Bean Validation后，独立Service先原子限流，再读取本人五字段快照并验证密码或既有ACTIVE验证码。验证码计错/消费按归属和原期限CAS；匹配后立即采样认证Clock，最终DB复核通过才用三字段签发身份调用JWT专用入口；不改造登录业务。

**Tech Stack:** Java21、Gradle Wrapper、Spring Boot4.1.1、既有Web/Validation/Security/JPA/Redis、Jackson3、BCrypt/JWT、MySQL8.4/Redis8.10.2及MinIO Testcontainers、JUnit5/MockMvc；不新增依赖或配置。

**Spec:** [书面设计](../specs/2026-11-03-reauthentication-design.md)，设计2a69c48独立第1/3轮accepted，零阻断、一非阻断。[反馈处置](../../reviews/2026-11-03-reauthentication-design-review.md)采纳NOTE-001删除username。代码基线f20c9ec，设计前工作区干净、与origin/main同步；独立基线1105/0/0/0、96类不是新接口测试证明。

## 全局约束

- 单接口POST /api/v1/users/me/reauthentications；普通有效Bearer JWT、USER/ADMIN本人，旧Token缺auth_time也允许；ID/登录角色/版本只取主体与可信上下文，Integer ID保持。
- UTF-8 application/json，charset省略或UTF-8；局部绑定，不加consumes前置拦截/全局Jackson配置。精确两种字段集：PASSWORD＋password，或EMAIL_CODE＋challengeId＋code；非对象/多根/重复解码键/未知字段/类型强制/非法Content-Type均400。
- password非全空白1—128个Java字符、不trim/规范化、不套用ASCII/72字节创建规则、不hash；challengeId规范43字符Base64URL解码32字节且回编码一致；code精确六位ASCII数字、保留前导零；字段名及字符串token局部上限128，根对象深度1。
- 每用户首次准入起900000ms最多10次、两方式/所有Token设备共享，key为music:auth:reauth:user:<id>。先准入再查业务DB；已准入所有结果计数，拒绝不续期/计数、不退额度、不重置、不加IP桶；Redis故障两方式都fail-closed。
- ACTIVE沿既有九字段、SHA256/Base64URL摘要及绝对expiresAt=reservedAt+300000ms；attempts0—4可验证、5耗尽、超过5损坏503；第5次错误原子删除，正确消费最多一次，任何操作不改pending/发送额度、不续TTL、不恢复。
- 匹配后立即用authenticationClock采样截断秒，非消费/DB完成/签发时间；候选时间只有消费（邮箱方式）及最终复核通过才可签发，不回填now、不放宽零未来容差。
- 200/no-store严格accessToken/tokenType/expiresAt/authenticatedAt，无Location/user资料；原Token及其他登录保持原期限和auth_time；本次只保留原角色/验证版本，不提升角色/版本、不写users任何列或审计/活动字段。
- 错误沿设计§9：400 VALIDATION_FAILED，401 UNAUTHORIZED/INVALID_TOKEN/TOKEN_REVOKED/REAUTHENTICATION_FAILED，409 CURRENT_EMAIL_NOT_BOUND，429 REAUTHENTICATION_RATE_LIMITED＋Retry-After，503 REAUTHENTICATION_UNAVAILABLE/既有AUTH_STATE_STORE_UNAVAILABLE，500 INTERNAL_ERROR；无新403/502或REAUTHENTICATION_REQUIRED门禁。
- 无迁移/新依赖/配置/公开验证码用途/SMTP/安全开放路径/Actuator变化，V1—V8不动；不实现refresh、新邮箱变更、确认或调试接口，不重配本地服务，不提交.env。
- 中文文档/提交；请求/响应/快照toString及日志隐藏密码、码、摘要、邮箱、challengeId、Token；意外异常剥离消息/cause/suppressed，保留traceId、类型和安全调用位置。

## 审核关注点

1. Jackson局部128上限不能在转成字符串前默许超长/数字，也不能遗漏转义同名键、多根或Content-Type缺失；任务1/6有实际HTTP与流式边界断言。
2. BCrypt完成后才推进mock Clock的测试不能掩盖认证时间采样过晚；任务5分别在consume/最终DB复核中推进Clock，断言仍是匹配后原时间。
3. 旧验证码值恰好与新值相同时，也必须按challenge摘要隔离；任务2/7强制同码重发，旧失败/消费不改新ACTIVE及pending。
4. 实际Redis执行成功但确认丢失与未执行是不同故障；任务7调用真实Lua后故意丢确认，验证额度/错误次数/消费可能已生效且不恢复、不发Token。
5. 最终复核后版本变化可能让新Token立即撤销；任务7在真实签发前更新MySQL，断言签出原版本且实际过滤链TOKEN_REVOKED，不把复核写成原子快照。

---

## 文件结构与接口归属

以下Java路径以src/main/java/org/example/music为前缀；标“修改”者是全部既有生产文件改动范围，其余新增。

| 路径 | 职责/归属任务 |
| --- | --- |
| user/domain/ReauthenticationMethod.java | PASSWORD/EMAIL_CODE专用枚举，不扩展公开LoginType；任务1 |
| user/dto/ReauthenticationRequest.java、ReauthenticationResponse.java | 四项可空请求字段及条件约束；四字段响应；字符串脱敏；任务1 |
| user/controller/ReauthenticationRequestReader.java | UTF-8、媒体类型、局部流式严格绑定及Validator；任务1 |
| user/exception/ReauthenticationValidationException.java | 固定“再次验证请求格式不正确”；任务1 |
| user/domain/ReauthenticationChallengeSnapshot.java | record(binding, challengeDigest, codeHash, reservedAt, expiresAt, attempts)，复用ReauthenticationEmailBinding；任务2 |
| user/repository/ReauthenticationStore.java | admit/read/recordFailure/consume及严格回包；任务2 |
| user/exception/ReauthenticationFailedException.java | 固定“身份验证失败，请检查凭据或重新申请验证码”；任务2 |
| user/exception/ReauthenticationRateLimitedException.java | 固定“再次验证请求过于频繁，请稍后重试”，retryAfterSeconds；任务2 |
| user/exception/ReauthenticationUnavailableException.java | 固定“再次验证暂不可用，请稍后重试”，可携带原cause但日志不透传；任务2 |
| user/exception/ReauthenticationDiagnostics.java | sanitized(Throwable)复制安全栈，不留原消息/链；任务2 |
| user/domain/UserReauthenticationSnapshot.java | record(Integer userId,String email,String passwordHash,UserRole userRole,long tokenVersion)，不含username；任务3 |
| user/repository/UserRepository.java（修改） | 仅新增findReauthenticationSnapshot(Integer userId)五字段JPQL投影；任务3 |
| user/service/ReauthenticationSnapshotReader.java | 独立只读TransactionTemplate及启动/查询/完成故障翻译；任务3 |
| security/JwtTokenSubject.java | record(Integer userId,UserRole accountRole,long tokenVersion)，不含用户名/邮箱/摘要；任务4 |
| security/JwtTokenService.java（修改） | 专用签发入口及必要的私有encode复用，保留原公开签名/声明；任务4 |
| user/service/ReauthenticationService.java | 可信上下文、准入、两方式、候选时间/最终复核/签发协调；任务5 |
| user/controller/ReauthenticationController.java | 唯一HTTP端点与OpenAPI；任务6 |
| common/exception/GlobalExceptionHandler.java（修改） | 仅四种新异常映射，安全诊断；任务6 |

资源新增src/main/resources/redis/reauthentication/{admit,read,fail,consume}.lua。ReauthenticationStore加载既有redis/reauthentication-email/common.lua作为前缀，不修改既有common/发送四脚本或其协议；资源缺失启动失败，不能留下空脚本。

测试路径以src/test/java/org/example/music为前缀：新增user/controller/ReauthenticationRequestReaderTest.java（任务1）、user/repository/ReauthenticationStoreTest.java及user/ReauthenticationStoreIntegrationTest.java（任务2）、user/service/ReauthenticationSnapshotReaderTest.java及user/ReauthenticationIntegrationTest.java（任务3创建真DB基础设施、任务7扩展）、user/service/ReauthenticationServiceTest.java（任务5）、user/controller/ReauthenticationControllerTest.java（任务6）；修改security/JwtTokenServiceTest.java仅新增本入口用例（任务4）。不改无关测试或旧夹具。

文档：实现时新增docs/api/user/reauthenticate-current-user.md、docs/reviews/2026-11-03-reauthentication-review.md；本计划、设计及设计处置记录随实施更新，无新部署文档，引用既有认证时间和邮件部署验收边界。

### 任务1：严格请求绑定与脱敏DTO

**Files:** 创建上述任务1的枚举、两个DTO、Reader、Validation异常与ReaderTest。

**Interfaces:** `ReauthenticationRequestReader(Validator validator)`；`ReauthenticationRequest read(HttpServletRequest request)`；请求record为(method,password,challengeId,code)，响应record为(accessToken,tokenType,expiresAt,authenticatedAt)，后两者Instant。

- [x] **1. 写失败测试。** ReaderTest先构造MockHttpServletRequest和真实Validator，定义should_reject_duplicate_decoded_keys_and_multiple_roots_before_business及媒体类型/字段矩阵；例如：
  ```java
  request.setContentType("application/json");
  request.setContent("{\"method\":\"PASSWORD\",\"password\":\"secret123\",\"password\":\"other123\"}".getBytes(UTF_8));
  assertThrows(ReauthenticationValidationException.class, () -> reader.read(request));
  ```
- [x] **2. 跑红灯。** `cmd /c gradlew.bat test --tests "*ReauthenticationRequestReaderTest" --console=plain`；记录缺类编译失败或断言失败的真实类型，不冒称行为断言先红。
- [x] **3. 实现局部Reader与DTO。** 仅局部JsonFactory/解析约束（字符串/name128、深度1），用严格UTF-8解码Reader（非法字节REPORT）、逐token匹配一层字符串对象、已解码名称Set查重、EOF检查多根；字段集按method精确判断并调用Validator，缺体/IO/解析/媒体类型失败固定400异常，不暴露原cause。DTO条件约束保留历史密码及规范Base64URL，secret字段只写、toString全遮蔽，响应Bearer固定由Service赋值。
- [x] **4. 验证绿灯。** 同命令必须全通过；覆盖1/7/8/128密码及129拒绝、非ASCII/首尾空白/全空白、数字码/Unicode码/前导零、43字符但回编码不一致的ID、null/空对象/转义重复/混用、128和129字段名/token、非法UTF-8和非UTF-8 charset、缺失/非法Content-Type、未知字段后不读取无界子对象；断言DTO字符串不含输入秘密。
- [x] **5. 提交。** 精确暂存本任务文件，中文提交`feat: 增加再次验证请求局部严格绑定`。

### 任务2：Redis固定额度与ACTIVE消费协议

**Files:** 创建任务2的Store/ChallengeSnapshot/异常/Diagnostics、四Lua、StoreTest及StoreIntegrationTest。

**Interfaces:** 构造器StringRedisTemplate；`void admit(Integer userId)`；`ReauthenticationChallengeSnapshot read(ReauthenticationEmailBinding binding,String challengeDigest)`；`void recordFailure(ReauthenticationChallengeSnapshot snapshot)`；`void consume(ReauthenticationChallengeSnapshot snapshot)`。无效read/consume抛Failed；recordFailure已确认计错或请求已失效均正常返回，由Service统一抛Failed；不确认/损坏抛Unavailable，限额抛RateLimited。

- [x] **1. 写失败测试。** Mockito回包矩阵与真实Redis8.10.2测试先定义；should_delete_active_on_fifth_failure_without_touching_pending的核心为：
  ```java
  for (int i = 0; i < 5; i++) store.recordFailure(snapshot);
  assertFalse(Boolean.TRUE.equals(redis.hasKey(activeKey)));
  assertEquals(pendingBefore, redis.opsForHash().entries(pendingKey));
  ```
- [x] **2. 跑红灯。** `cmd /c gradlew.bat test --tests "*ReauthenticationStoreTest" --tests "*ReauthenticationStoreIntegrationTest" --console=plain`；Docker跳过如实记录，不当通过。
- [x] **3. 实现原子脚本和严格Java解码。** admit只用新额度key，Lua TIME建立count/resetAtMs及PEXPIREAT，count1—10/字段/类型/实际到期逐项校验，准入[0,0]、超限[1,1..900]、损坏[2,0]。read/fail/consume只用本人active key；ARGV前四项固定userId/email/tokenVersion/challengeDigest，fail/consume再附codeHash/reservedAtMs/expiresAtMs，使用文本精确比较，不经double转换版本。read返回[0,九项String]或[1]/[2]；fail返回[0,1..5]、[1,0]、[2,0]；consume返回[0]、[1]、[2]且0必须实际DEL成功。先验schema/本人key内ID一致性，再绑定/快照/CAS；0—4可用、5耗尽、>5损坏；错误计数允许并发增长而不比较旧attempts，达到5原子删除。所有Java回包精确长度/类型/范围，Redis/序列化/错类型故障503，不重试或补偿。
- [x] **4. 验证绿灯。** 同命令全实跑；次数10/11及并发、原窗口和额度不退款、admit拒绝不写ACTIVE；TYPE/HLEN/多余字段/TTL缺失或漂移/版本Unicode及Long溢出/未知回包；原300000ms与Long.MAX_VALUE文本；耗尽5、损坏6；并发消费恰一、5个错与正确交错、旧snapshot遇同码新ACTIVE不改新次数/消费/pending。用真实过期命令或合法夹具，不改生产TIME、不sleep；Diagnostics测试在StoreTest中断言无原消息/cause/suppressed。
- [x] **5. 提交。** `feat: 增加再次验证额度与验证码原子消费`，不改发送四脚本或其测试。

### 任务3：五字段本人投影与只读事务边界

**Files:** 创建UserReauthenticationSnapshot、ReauthenticationSnapshotReader、SnapshotReaderTest；UserRepository只增加新查询；创建ReauthenticationIntegrationTest的真实MySQL/Redis基础设施及直接读取用例。

**Interfaces:** `Optional<UserReauthenticationSnapshot> UserRepository.findReauthenticationSnapshot(Integer userId)`；`ReauthenticationSnapshotReader(UserRepository,PlatformTransactionManager)`；`UserReauthenticationSnapshot load(Integer userId)`，缺账户抛CurrentUserRevoked，数据/事务故障抛Unavailable。

- [x] **1. 写失败测试。** 单测should_translate_start_query_and_commit_failure_outside_template及真实DB should_project_only_security_fields_without_writing_user：
  ```java
  assertEquals(5, UserReauthenticationSnapshot.class.getRecordComponents().length);
  assertEquals(expectedHash, reader.load(user.getId()).passwordHash());
  assertEquals(beforeRow, jdbc.queryForMap("select * from users where id=?", user.getId()));
  ```
- [x] **2. 跑红灯。** `cmd /c gradlew.bat test --tests "*ReauthenticationSnapshotReaderTest" --tests "*ReauthenticationIntegrationTest" --console=plain`。
- [x] **3. 实现。** JPQL精确顺序user.id/email/passwordHash/userRole/tokenVersion，映射新record；不改旧UserAuthorizationSnapshot/查询，不增加username日志或填null旧快照。独立只读TransactionTemplate包围执行与完成；验证必需ID/版本/角色/摘要结构，nullable email保留；catch DataAccessException/TransactionException翻译，缺记录401保持。新快照隐藏email/hash。
- [x] **4. 验证绿灯。** 单测证实readOnly、启动/查询/提交故障均503、缺记录401、结构异常503、快照字符串不泄漏。SpringBootTest用MySQL8.4及Redis8.10.2实际迁移/投影，test profile禁管理员初始化；表整行不变，JPQL不含username。这是NOTE-001实施证据。
- [x] **5. 提交。** `feat: 增加本人再次验证只读安全快照`。

### 任务4：最小身份与JWT专用签发

**Files:** 新建security/JwtTokenSubject.java；修改JwtTokenService.java和JwtTokenServiceTest.java，其他登录/安全文件不动。

**Interfaces:** `JwtTokenResult issueReauthenticated(JwtTokenSubject subject,UserRole loginRole,ReauthenticationMethod method,Instant verifiedAt)`；Subject仅(Integer userId,UserRole accountRole,long tokenVersion)。旧issue与issueLegacyWithoutAuthTime签名不变，旧UserAuthorizationSnapshot不变。

- [x] **1. 写失败测试。** should_issue_reauthentication_source_with_original_role_version_and_verified_time：
  ```java
  var result = service.issueReauthenticated(new JwtTokenSubject(42, UserRole.ADMIN, 7), UserRole.USER, ReauthenticationMethod.EMAIL_CODE, verified);
  assertEquals("REAUTHENTICATION_EMAIL_CODE", payload(result).path("loginType").asString());
  assertEquals(verified.getEpochSecond(), payload(result).path("auth_time").asLong());
  ```
  在现有测试类新增`JsonNode payload(JwtTokenResult result)`测试助手，仅Base64URL解码载荷供断言，真实签名/过滤链另由任务7证明。
- [x] **2. 跑红灯。** `cmd /c gradlew.bat test --tests "org.example.music.security.JwtTokenServiceTest" --console=plain`。
- [x] **3. 实现。** 新入口验证非null/正ID/非负版本/账户角色/方式/verifiedAt，包含负/亚秒未来拒绝；角色不得超过账户角色。私有encode改收最小Subject及受控来源字符串，旧入口只提取原快照三项，保留旧声明与校验行为；不要在Subject构造器给旧入口引入额外校验。新来源只映射PASSWORD→REAUTHENTICATION_PASSWORD、EMAIL_CODE→REAUTHENTICATION_EMAIL_CODE；不扩展公开LoginType、不读DB、不调用legacy、不回填now。iat/exp/jti/issuer/audience/HS256保持。
- [x] **4. 验证绿灯。** 两来源、USER身份的ADMIN账户、不提权、0/Long.MAX_VALUE版本、缺失/负/未来含1ns时间、metadata空值/错误均覆盖；iat与auth_time不同、TTL仍配置值。原全部测试通过，旧正常入口与显式legacy格式原样；不删旧断言，不把payload解码当签名验证。
- [x] **5. 提交。** `feat: 增加再次验证专用Token签发入口`。

### 任务5：独立再次验证协调与时间采样

**Files:** 新建user/service/ReauthenticationService.java及ReauthenticationServiceTest.java。

**Interfaces:** 构造器注入SnapshotReader、Store、PasswordHasher、JwtTokenService、@Qualifier("authenticationClock") Clock；`ReauthenticationResponse reauthenticate(AuthenticatedUserPrincipal actor,AuthenticatedSessionContext context,ReauthenticationRequest request)`。

- [x] **1. 写失败测试。** should_capture_email_verification_time_before_consumption_and_final_lookup：
  ```java
  assertEquals(verified.truncatedTo(SECONDS), result.authenticatedAt());
  assertEquals(verified.truncatedTo(SECONDS), signedTime.getValue());
  verify(tokens, never()).issueLegacyWithoutAuthTime(any(), any(), any());
  ```
  测试中用可变Clock，在成功matches返回时固定verified，再在consume及最终load内分别推进Clock，ArgumentCaptor<Instant> signedTime捕获issueReauthenticated末参；PASSWORD分支同样在最终load推进。
- [x] **2. 跑红灯。** `cmd /c gradlew.bat test --tests "*ReauthenticationServiceTest" --console=plain`。
- [x] **3. 实现。** 先检查可信actor/context，再admit、load首快照、版本与角色核对。PASSWORD原值matches，成功立即采样、最终load、先版本角色再摘要精确比较，失配按设计401；EMAIL首轮严格规范邮箱/null409、digest ID、store.read、matches失败recordFailure后统一Failed，成功立即采样、consume、最终load，末轮先版本角色再判邮箱变化/解绑Failed，不误用首轮的409分支。最终Subject只用相同已验证版本/ID/账户角色，loginRole取actor，调用专用issuer并组四字段响应。无方法级长事务、不写DB、不hash、不取消/恢复ACTIVE或pending、不退额。已知异常透传，意外RuntimeException用通用消息和sanitized副本包装保持500。
- [x] **4. 验证绿灯。** 顺序admit→load→matches→clock→consume（仅码）→load→issue；上下文失败无准入，限额/存储失败不继续，合法无邮箱也已扣额；短/非ASCII/首尾空白密码、第一/最后快照账户/版本/角色/摘要/邮箱变化、消费未知不发Token、最终DB/JWT故障不恢复、PASSWORD不碰挑战，码不碰pending。所有异常嵌套与suppressed含敏感值的情形都不能留原链。
- [x] **5. 提交。** `feat: 协调本人再次验证与固定版本签发`。

### 任务6：唯一HTTP入口、OpenAPI及统一错误

**Files:** 新建ReauthenticationController和ControllerTest；GlobalExceptionHandler仅加四类新异常映射及安全诊断，不重构其他处理器。

**Interfaces:** Controller构造器Reader＋Service；`ResponseEntity<ReauthenticationResponse> reauthenticate(HttpServletRequest request,Authentication authentication)`。映射无consumes，成功显式application/json/no-store，无Location；可信context从AuthenticatedSessionContext.from取得。

- [x] **1. 写失败MVC测试。** should_return_exact_four_fields_and_ignore_forged_identity_headers：
  ```java
  mvc.perform(post(PATH).contentType("application/json").content(body).with(authentication(auth)))
      .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(4)))
      .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Location"));
  ```
  PATH固定本接口，auth含实际类型主体及context；mock Service只证明HTTP契约。
- [x] **2. 跑红灯。** `cmd /c gradlew.bat test --tests "*ReauthenticationControllerTest" --console=plain`。
- [x] **3. 实现。** Controller仅HTTP绑定/主体/Service/响应，@Tag/@Operation/@ApiResponse/@Schema列全契约，普通旧auth_time不门禁。Global新Validation400、Failed401、RateLimited429/Retry-After、Unavailable503，复用已有CurrentEmailNotBound/InvalidToken/Revoked；Unavailable日志保留traceId/异常类型和安全副本，不记录原cause/secret，未知500由Service安全包装。
- [x] **4. 验证绿灯。** 真Reader处理媒体类型/缺体/重复键/混用，坏请求Service无调用；未认证/错误details401，头/查询不能覆盖本人/版本/time/method；所有稳定code/type下划线、no-store、无剩余次数/邮箱/secret；ListAppender验证底层及抑制异常含Token/邮箱/码/hash/ID不进入日志。包含未知Service安全包装500，确认HTTP401不会重定向登录页。
- [x] **5. 提交。** `feat: 暴露本人再次验证身份接口`。

### 任务7：真实发送到兑换、并发与故障集成

**Files:** 扩展任务3创建的ReauthenticationIntegrationTest；必要的本接口StoreIntegrationTest补充夹具，不改发送/登录代码或旧夹具。

**Interfaces:** 使用真实MySQL8.4/Redis8.10.2/BCrypt/JwtTokenService/实际过滤链及已有发送HTTP接口；只用受控ReauthenticationEmailSender捕获传输边界。对故障用窄spy执行真实操作后丢确认，不以mock成功替代正常外部服务。

- [x] **1. 写失败用例。** should_reject_old_snapshot_after_same_code_replacement与should_issue_old_version_after_final_lookup_race核心断言：
  ```java
  assertEquals(newActiveBefore, activeState(userId));
  assertEquals(originalVersion, jwtPayload(newToken).path("tokenVersion").asLong());
  mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + newToken))
      .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
  ```
  在本测试类定义`Map<Object,Object> activeState(Integer userId)`读取真实私有hash、`JsonNode jwtPayload(String token)`仅用于载荷断言；新Token兼容必须另走真实过滤链，不把载荷解码视作认证。
- [x] **2. 跑红灯。** `cmd /c gradlew.bat test --tests "*ReauthenticationIntegrationTest" --tests "*ReauthenticationStoreIntegrationTest" --console=plain`。
- [x] **3. 完成真实矩阵。** PASSWORD/EMAIL_CODE、多角色/USER身份ADMIN、旧缺auth_time Token均成功，HTTP发送产生ACTIVE再兑换，旧Token/其他登录GET继续有效且原auth_time不变，users整行不变、投影不含username、无活动写入/Ticket。真实坏签名/过期/subject溢出/缺版本/非法角色/issuer或audience不匹配/版本撤销均401且无业务准入；真实链路下伪造头/查询不能覆盖身份和auth_time，额外JSON身份字段400不扣额。额度10/11跨方式/Token共享，五错删除/重发不重置额度，码规范与真实BCrypt匹配。真实并发正确最多一成功、第五错先完成后正确失败、同码新旧ID隔离、旧计错不扣新次数/pending，限时闩锁/future与finally释放，不sleep。
- [x] **4. 固定故障/时钟用例并验证绿灯。** 实际MySQL在matches/consume后改摘要/版本/角色/邮箱/删除，观察确切401/409；无额外查询username且并发改名不影响ID身份。真实Luaadmit/fail/consume执行后故意丢回包，返回503且可能已扣/计错/删除，不恢复或签Token；未执行故障另测。最终查询后在专用签发spy中执行MySQL版本递增再真实签发，验证原版本与上述过滤链撤销。test-only响应写失败Filter在实际消费/签发后抛IO，断言异常或写出失败、issuer已调用一次、ACTIVE仍删除，不制造生产假接口。认证Clock推进仅验证JWT采样，不假装Redis到期；两新来源、零未来容差、TTL原值、真实过滤链及安全日志均覆盖。
- [x] **5. 相关回归与提交。** `cmd /c gradlew.bat test --tests "*Reauthentication*" --tests "*JwtTokenServiceTest" --tests "*AuthTimeBindingIntegrationTest" --tests "*AuthVersionBindingIntegrationTest" --tests "*PasswordChangeIntegrationTest" --tests "*EmailSendCodeIntegrationTest" --console=plain`，全实跑后提交`test: 验证再次验证真实链路与并发故障边界`。该定向回归不代替任务8全量。

### 任务8：API文档、完整验证与首次实现送审

**Files:** 新增docs/api/user/reauthenticate-current-user.md、docs/reviews/2026-11-03-reauthentication-review.md；更新本计划、设计和设计反馈记录。无部署配置改动。

**Interfaces:** 按AGENTS第2/14节完整材料交用户提供Claude只读审核，不自动联系审核方或推送。

- [x] **1. 写API与OpenAPI核对。** 固定两请求/四响应、UTF-8局部400、1—128历史密码、规范43 ID/ASCII码、10次900秒固定窗/第5错删除、角色版本不升级、auth_time不重计/旧登录保留、全稳定码与下划线urn、无全局REAUTHENTICATION_REQUIRED门禁。写清消费/签发/响应未知和复核后竞态、业务401保留弹窗、TOKEN_REVOKED再恢复登录。提供POSIX及PowerShell stdin curl，无真实secret，部署引用既有验收条件。
- [x] **2. 做范围检查。** git diff --check，列全部含untracked文件，既有生产只改Repository/JwtTokenService/GlobalExceptionHandler；无V1—V8、公开LoginType、安全链、登录/发送流程、共享Hasher、.env、依赖/Compose变更。NOTE-001五字段投影/三字段Subject已落实，未增加用户名诊断日志。
- [x] **3. 执行唯一完整基准。** PowerShell先`$env:MINIO_INTEGRATION_TESTS = "true"`，再`cmd /c gradlew.bat cleanTest check --console=plain`。必须BUILD SUCCESSFUL；汇总本次新XML的通过/失败/错误/跳过、类数及新任务逐类数，列所有跳过及原因，必要MySQL/Redis/MinIO跳过不声称完成；不虚构质量插件命令。
- [x] **4. 完成送审材料与反馈处置。** Method/Path、权限、声称行为、完整文件/迁移/API路径、实际命令/结果、基线/实现提交、双平台curl、默认项/非原子边界、首次实现反馈“不涉及”。沿用NOTE-001原非阻断编号，写问题/分级/方向判断、删除字段证据和实际测试，保留待实现审核复核状态；独立基线1105不作新接口证明。
- [x] **5. 提交并交接。** 更新勾选仅表示实际完成步骤，红灯/编译错误与断言失败如实记录，中文提交`docs: 登记再次验证身份验证与审核材料`。自验不等于accepted，不自动push或开始改邮箱端点，等待独立首次实现审核。

## 计划自审与确认

已按设计逐节映射：HTTP/隐私到任务1/6，额度与ACTIVE四协议到任务2，本人快照/事务到任务3，最小签发身份/时间/来源到任务4，绑定协调到任务5，真实消费/并发/不确定性到任务7，完整文档/MinIO基准及NOTE处置到任务8。全文件、类型、签名、错误文案、回包与审核关注点均有归属；不要求改动发送脚本/公开枚举或增加生产调试入口。

NOTE-001删除字段是独立审核明确允许的设计微调，不改变请求/响应/权限/版本或业务选择。计划保留Codex同会话唯一实现、Claude独立审核方式。用户已确认并授权实施；实现及完整验证已完成，等待首次独立实现审核，不自动推送或推进后续接口。

## 实施结果（2026-11-03）

实现0837dfb、fb94e89、c6670dc、fc413d8、ac31ab9、96aaea8、bbc0084。带MINIO_INTEGRATION_TESTS=true的cleanTest check --console=plain实际BUILD SUCCESSFUL，10分36秒；1285通过/0失败/0错误/0跳过、103类，新增180项。任务8类184项含原JWT四项回归。相关定向回归20类428项也通过，完整命令与文件清单见[首次实现送审材料](../../reviews/2026-11-03-reauthentication-review.md)。

过程偏差：首次红灯多为缺类编译失败，不冒称业务断言先红；Service测试泛型表达式编译问题已修正；真实Redis与部分集成测试同阶段补齐。首轮真实集成61项中2项因Spring Data接口spy的callRealMethod失败，按现有默认Answer委托方式修正测试夹具，保留真实查询和原时间断言，后续全量通过。checkbox标记最终交付/验证完成，不表示过程顺序逐字执行。未决实现审核结果不能以自验替代。
