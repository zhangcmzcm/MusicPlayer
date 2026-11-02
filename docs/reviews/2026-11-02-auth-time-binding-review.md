# 登录认证时间绑定加固首轮实现审核材料

日期：2026-11-02。状态：实现、文档、定向与完整基准验证完成；待Claude独立实现审核，第1/3轮。设计accepted不等于实现accepted。

## 本次任务与权限

现有 `POST /api/v1/auth/login` 的auth_time绑定加固，不新增HTTP接口。登录仍公开，USERNAME_PASSWORD、EMAIL_PASSWORD、EMAIL_CODE及ROLE_SELECTION请求/成功DTO不变；角色选择权限、限流/失败计数和认证版本撤销语义不变。

本次同步受保护JWT的可选auth_time解析，主体仍为AuthenticatedUserPrincipal(Integer userId, UserRole loginRole)。不实现再次验证、邮箱变更、refresh或300秒业务门禁，不新增REAUTHENTICATION_REQUIRED映射。

## 声称实现的行为

1. PasswordLoginService先复制账户身份/角色/版本，matches成功后立即按命名UTC Clock捕获时间；EmailCodeLoginService在validate成功后捕获，读取用户、consume成功才返回。时间不因后续工作重置。
2. 不可变LoginPrincipal和LoginTicketContext增加显式非空Instant；无默认时间构造器，不从数据库快照、iat或now回填缺失时间。UserLoginService保留版本/角色复核，沿原principal/Ticket时间签发。
3. Ticket Lua一次保存userId/maximumRole/loginType/tokenVersion/authTime五字段和300秒TTL。精确字段集、规范非负ASCII十进制、Long/Instant范围与未来时间严格解析；旧四字段、非法/损坏/过期票据401 LOGIN_TICKET_INVALID，消费仍一次性、不恢复、不自动重试。
4. 实际登录调用显式四参issue，将原验证时间按秒写入JWT整数auth_time，iat/exp表示当前签发时间；null/负数/未来时间拒绝签发。旧三参issue移除，显式issueLegacyWithoutAuthTime没有生产调用，仅保留明确旧式兼容；无now默认重载。
5. 受保护链兼容字段缺失的旧JWT；存在但null/字符串/小数/布尔/数组/对象/负数/溢出/未来/晚于iat/无真实iat均401 INVALID_TOKEN。仅允许整数类型，不用String.valueOf或任意Number.longValue强制归一化。
6. 本地依赖源码证明MappedJwtClaimSetConverter会在缺iat时以exp-1推算；SecurityConfig仅在auth_time存在且原iat缺失/null时移除该推算值，使过滤器拒绝。旧JWT默认转换和HS256/issuer/audience/其他时间容差不变。
7. 身份、角色、版本、时间全部通过后发布AuthenticatedSessionContext至Authentication.details，保存已校验版本及可缺失时间；类型化from读取，仅接受已认证主体和对应details，不继承伪造details/参数/头部，不保留原始Token，不扩大公开DTO。
8. 零未来时间容差保持，时钟运维验收/告警见部署文档；旧JWT不主动撤销，老验证时间仍可普通访问，角色选择或未来refresh不能刷新其近期资格。

## 基线与提交

- 运行时代码基线3a27229，925/0/0/0已由上一审核独立确认。
- 书面设计8e652e4；设计审核回执、计划提交2da59c4，用户确认后编码。实施审核范围以 `git diff 2da59c4..HEAD` 为准，设计阶段的纯文档提交单独说明，不混成业务实现。
- 63a9d92：验证记时、五字段Ticket、显式签发/严格过滤、上下文及必要测试夹具迁移；任务1—3因签名依赖合并为一个可编译提交。
- c4c93ef：真实原始JWT载荷、登录/角色选择/上下文/Redis并发集成。
- 5c4fe17：登录OpenAPI描述、API及部署文档。

## 文件清单（含新增与未跟踪）

生产文件以src/main/java/org/example/music为前缀，共11个：

- config/SecurityConfig.java（Clock bean及限定的iat转换保护）
- security/AuthenticatedSessionContext.java（新增）
- security/JwtTokenService.java
- security/TokenVersionAuthenticationFilter.java
- user/domain/LoginPrincipal.java
- user/domain/LoginTicketContext.java
- user/service/PasswordLoginService.java
- user/service/EmailCodeLoginService.java
- user/service/LoginTicketService.java
- user/service/UserLoginService.java
- user/controller/AuthController.java（仅登录OpenAPI描述）

测试以src/test/java/org/example/music为前缀，共21个：

- security/AuthenticatedSessionContextTest.java（新增）
- security/JwtTokenServiceTest.java
- security/TokenVersionAuthenticationFilterTest.java
- user/service/PasswordLoginServiceTest.java
- user/service/EmailCodeLoginServiceTest.java
- user/service/LoginTicketServiceTest.java
- user/service/UserLoginServiceTest.java
- user/AuthTimeBindingIntegrationTest.java（新增）
- user/AuthVersionBindingIntegrationTest.java（四参spy与五字段夹具适配，版本等原断言保留；旧四字段拒绝在新集成类显式验证）
- user/PasswordChangeIntegrationTest.java（旧式fixture改名、实际登录四参spy适配，改密断言保留）
- user/CurrentUserIntegrationTest.java、user/CurrentUserUpdateIntegrationTest.java、user/UserRoleAuthorizationIntegrationTest.java（仅旧式fixture改名）
- track/TrackStreamIntegrationTest.java、track/TrackPublishIntegrationTest.java、track/TrackListIntegrationTest.java、track/TrackDetailIntegrationTest.java、track/TrackDeleteIntegrationTest.java、track/TrackCoverIntegrationTest.java、track/TrackArchiveIntegrationTest.java、track/TrackAdminAuthenticationIntegrationTest.java（每个仅一处旧式fixture方法名适配，无歌曲断言修改）

实施文档共5个：

- docs/api/auth/login.md（修改）
- docs/deployment/auth-time-binding.md（新增）
- docs/superpowers/plans/2026-11-02-auth-time-binding-plan.md（执行进度）
- docs/reviews/2026-11-02-auth-time-binding-design-review.md（设计NOTE处置验证记录）
- docs/reviews/2026-11-02-auth-time-binding-review.md（新增，本材料）

合计37个文件。最终提交前按git逐项核对。无迁移、配置项、新依赖、全局ObjectMapper变更，无歌曲生产逻辑或其他接口实现变更；users表、V1—V8、密码哈希器和共享主体不变。

## API与错误兼容

API文档docs/api/auth/login.md；部署条件docs/deployment/auth-time-binding.md；原版本绑定部署条件继续有效，旧文档的四字段Ticket描述是历史基线，新发布以五字段为准。

登录成功DTO和状态码不变。版本/角色初始复核失败仍INVALID_CREDENTIALS，Ticket格式/版本/时间失效LOGIN_TICKET_INVALID，存储故障AUTH_STATE_STORE_UNAVAILABLE，角色选择限制403保留；非法auth_time仅在已有受保护链复用INVALID_TOKEN。错误/缓存/WWW-Authenticate及trace行为沿用现有实现，不顺手统一资源服务器错误。

## 实际验证

联合单元命令：

```powershell
cmd /c gradlew.bat test --tests "org.example.music.security.*" --tests "org.example.music.user.service.PasswordLoginServiceTest" --tests "org.example.music.user.service.EmailCodeLoginServiceTest" --tests "org.example.music.user.service.LoginTicketServiceTest" --tests "org.example.music.user.service.UserLoginServiceTest"
```

结果：115通过、0失败/错误/跳过，BUILD SUCCESSFUL，8秒。初期类型缺失为预期编译红灯；签名适配和Mock时钟的未使用stub问题已修正，未削弱断言。未对每个新增断言单独保存修改前失败记录，不将编译失败冒充业务失败。

真实依赖定向命令：

```powershell
cmd /c gradlew.bat test --tests "org.example.music.user.AuthTimeBindingIntegrationTest" --tests "org.example.music.user.AuthVersionBindingIntegrationTest" --tests "org.example.music.user.PasswordChangeIntegrationTest"
```

结果：135通过、0失败/错误/跳过，BUILD SUCCESSFUL，1分41秒。新认证时间38项、原版本绑定46项、改密51项；真实MySQL8.4、Redis8.10.2、BCrypt、JWT和过滤链，SMTP仅捕获邮件。

完整基准：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat cleanTest check --console=plain
```

完整结果：BUILD SUCCESSFUL，9分钟，999通过、0失败、0错误、0跳过，88类；汇总自本次cleanTest生成的build/test-results/test/TEST-*.xml。跳过项：无。相对925基线净增74项；涉及track测试夹具适配，已实际执行MinIO全量回归。

本次重点八类：AuthenticatedSessionContextTest 1、JwtTokenServiceTest 4、TokenVersionAuthenticationFilterTest 19、PasswordLoginServiceTest 7、EmailCodeLoginServiceTest 6、LoginTicketServiceTest 48、UserLoginServiceTest 28、AuthTimeBindingIntegrationTest 38，共151项。原版本绑定46、改密51及所有歌曲回归也实际执行。基准后仅更新计划/审核文档，无运行时代码或测试变化，不额外记成新测试运行。

关键证据：

- 原始签名载荷使用Nimbus JWSObject＋JSON Payload，明确包含null/字符串/1.0/1e0/缺iat等，不用提前丢null或归一化的构造器替代测试。
- 新Token的auth_time为整数，多个登录模式/角色、600秒签发或角色选择延迟仍保留原验证时间；未给现有接口新增300秒门禁。
- 缺字段/0/旧/当前时间的真实过滤链上下文、参数和头部防覆盖、相同Token撤销后401均验证；单位测试另外证明伪造details被替换。
- 可控时钟重现毫秒级跨节点偏差在秒边界造成未来时间拒绝；内部签发也拒绝亚秒级未来输入，不用截断掩盖未来值。部署文档另以20ms偏差说明原理。
- Ticket旧四字段、未来、额外字段、无TTL、错误类型以及并发恰一消费；既有版本/角色/改密撤销与最终复核后旧版本Token拒绝回归保留。

## 设计反馈复核与处置（沿用原编号/分级）

| 问题编号与原分级 | 问题核实及证据 | 分级判断 | 修改方向判断 | 实际处置 | 验证结果或待复核事项 |
| --- | --- | --- | --- | --- | --- |
| NOTE-001 非阻断，零未来容差与跨节点时钟 | 成立；固定Clock验证秒边界误拒；用户已明确选择零容差 | 认可，不将非阻断变成隐含合并门槛 | 认可运维验收与告警，不能承诺正偏差阈值保证零误拒 | 部署文档规定15分钟观测、100ms目标/告警、500ms或失同步摘除、跨节点烟测及留档；应用容差不改 | 时钟边界单测已通过；实际部署验收尚未执行，不能用代码自验代替 |
| NOTE-002 非阻断，旧式issue静默降级 | 成立；旧名称确易误用，多处fixture依赖 | 认可，降级方向安全，但可明确命名 | 采用显式issueLegacyWithoutAuthTime并移除旧三参issue | 实际登录四参传原时间；其他fixture显式旧式签发，不填now；生产无legacy调用 | 用户登录Service与真实登录verify never、源码调用检查及定向回归通过；完整回归结果另见上节 |

本轮为首次实现送审，没有实现审核反馈需要修复；上表仅回应已有设计审核建议，不将设计accepted或非阻断落实等同于实现accepted。

## curl示例

公开登录，以下全为虚构数据；实际环境使用HTTPS，避免将真实密码/Token写入命令历史。

```sh
curl --request POST 'https://music.example.com/api/v1/auth/login' \
  --header 'Content-Type: application/json' \
  --data '{"loginType":"USERNAME_PASSWORD","account":"example_user","password":"example-password"}'
```

```powershell
curl.exe --request POST 'https://music.example.com/api/v1/auth/login' `
  --header 'Content-Type: application/json' `
  --data-binary '@login-example.json'
```

login-example.json内容同上虚构JSON；真实凭据文件由调用者安全管理，不进入Git。不提供尚未实现的再次验证或邮箱变更curl。

## 未决事项与部署前置

无未定业务规则。零未来容差会因时钟偏差拒绝新Token，部署目标不是数学零误拒保证。旧JWT不主动撤销，四/五字段Ticket互不兼容需重新登录；混合部署不能宣告全站生效，需全实例升级、旧登录请求排空、时钟验收，回滚不得用iat兜底。

未来refresh只继承可信时间与有效版本，独立设计轮换/重放保护；本轮不做refresh或会话存储。部署/监控验收由实际环境负责，本次未操作生产环境，也未开始后续接口。
