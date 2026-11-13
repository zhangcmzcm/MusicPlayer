# 取消收藏歌曲

## 接口与权限

`DELETE /api/v1/users/me/favorites/{trackId}`，取消当前登录用户本人对指定歌曲的收藏。

需要 `Authorization: Bearer <ACCESS_TOKEN>`，身份取服务端验证后的AuthenticatedUserPrincipal。

| 当前登录角色 | DRAFT | PUBLISHED | ARCHIVED | 操作归属 |
| --- | --- | --- | --- | --- |
| USER | 允许 | 允许 | 允许 | 本人收藏 |
| ADMIN | 允许 | 允许 | 允许 | 本人收藏 |

管理员以USER角色登录也可以取消本人对草稿或归档歌曲的已有收藏。其他用户对同一歌曲的收藏保持原值。

## 请求与校验

| 参数 | 位置 | 类型 | 校验 |
| --- | --- | --- | --- |
| trackId | path | Long | 正整数，1至9223372036854775807 |

无请求体、无业务查询参数。未知查询参数、附带正文、自定义用户ID和角色头均不参与业务与归属判断。

零、负数、非数字、小数和Long溢出返回400 VALIDATION_FAILED。认证先执行，未认证请求沿用401契约。

```http
DELETE /api/v1/users/me/favorites/123
Authorization: Bearer <ACCESS_TOKEN>
```

## 成功响应与幂等

以下情况统一返回204 No Content，响应正文为空，Cache-Control为no-store。

- 本人收藏存在且成功删除。
- 本人未收藏、已经取消或重复取消。
- 歌曲不存在或已彻底删除。
- 歌曲当前对本人不可见，但本人收藏关系可以清理。

```http
HTTP/1.1 204 No Content
Cache-Control: no-store
```

其他用户存在收藏时，本人重复取消仍为204，其记录及审计字段保持原值。取消后通过F01再次收藏会创建新记录，以新的收藏时刻保存UTC微秒审计时间，重新收藏的歌曲状态权限继续遵循F01。

首版暂不增加取消收藏专用限流。

## 并发与事务

取消操作在短写事务中按歌曲主键排他锁定当前记录，再按认证用户ID和歌曲ID执行条件删除。歌曲已缺失时，完成事务后返回204；既有歌曲删除级联保证收藏关系清理。

同一用户对同一现存歌曲并发收藏与取消，两个请求都提交成功时，后取得歌曲锁的操作决定最终状态。收藏先提交、取消后提交，最终关系缺失；取消先提交、收藏后提交，最终存在新收藏记录。HTTP响应到达顺序受网络影响。

同一歌曲上不同用户的写入也会串行执行，其他歌曲可独立操作。取消与归档的两种提交顺序均允许取消，歌曲归档不改变取消资格。取消与彻底删除的两种提交顺序均返回取消成功，最终收藏关系缺失。

提交前失败时回滚，原收藏及审计字段保留。实际提交成功但提交确认丢失时，调用方可能收到503，认证有效的后续重试可按幂等规则完成。

## 错误

业务错误使用application/problem+json、ProblemDetail、稳定code及 `urn:problem:music:*`。收藏存储故障响应显式携带no-store。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 正常匹配路由的歌曲ID格式、正数或范围校验失败 |
| 401 | UNAUTHORIZED | 未提供有效认证 |
| 401 | INVALID_TOKEN | 必要身份声明无效 |
| 401 | TOKEN_REVOKED | 账户不存在、token version失效或登录角色失效 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态存储故障 |
| 503 | FAVORITE_STORE_UNAVAILABLE | 收藏读取、删除、锁等待或事务故障 |
| 500 | INTERNAL_ERROR | 未预期内部错误 |

JWT签名、过期、issuer或audience解码校验失败沿用现有资源服务器401与WWW-Authenticate行为，部分响应没有业务ProblemDetail正文。

```json
{
  "type": "urn:problem:music:favorite_store_unavailable",
  "title": "收藏存储不可用",
  "status": 503,
  "detail": "歌曲收藏暂时无法完成",
  "instance": "/api/v1/users/me/favorites/123",
  "code": "FAVORITE_STORE_UNAVAILABLE"
}
```

## 缺失路径段与尾斜杠

真实DELETE路由测试记录当前全局行为如下。F03增加集合GET后，缺少ID的精确集合路径由方法不匹配异常处理；尾斜杠路径仍由ResourceHttpRequestHandler处理。取消收藏业务无调用且数据库无修改。

| DELETE请求路径 | 有效认证 | 未认证 |
| --- | --- | --- |
| /api/v1/users/me/favorites | 500 INTERNAL_ERROR，application/problem+json | 401 UNAUTHORIZED |
| /api/v1/users/me/favorites/ | 500 INTERNAL_ERROR，application/problem+json | 401 UNAUTHORIZED |
| /api/v1/users/me/favorites/123/ | 500 INTERNAL_ERROR，application/problem+json | 401 UNAUTHORIZED |

当前全局兜底会将方法不匹配及NoResourceFoundException均转为500，对外错误保持不变。正常匹配接口的合法ID，即使对应歌曲或本人收藏缺失，也使用204语义。

## curl

以下为POSIX shell示例，ACCESS_TOKEN由已有登录流程获得，123为本次取消的歌曲ID。

```sh
curl --request DELETE 'http://localhost:8080/api/v1/users/me/favorites/123' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```
