# 收藏歌曲

## 接口与用途

`PUT /api/v1/users/me/favorites/{trackId}`，将歌曲加入当前登录用户的收藏。

需要 `Authorization: Bearer <ACCESS_TOKEN>`。本人身份取自已验证的认证主体，歌曲ID取自路径参数。

| 当前登录角色 | DRAFT | PUBLISHED | ARCHIVED |
| --- | --- | --- | --- |
| USER | 404 TRACK_NOT_FOUND | 允许 | 404 TRACK_NOT_FOUND |
| ADMIN | 允许 | 允许 | 允许 |

管理员以USER角色登录时按USER规则处理，歌曲上传者本人也遵循该矩阵。每次请求检查当前可见性，已有收藏的USER在歌曲归档后再次请求仍返回404，收藏关系保留。

## 请求与校验

| 参数 | 位置 | 类型 | 约束 |
| --- | --- | --- | --- |
| trackId | path | Long | 必填，1至9223372036854775807的整数 |

无请求体、无业务查询参数。未知查询参数、附带请求体及自定义用户/角色头均不参与收藏业务和身份判定。

零、负数、小数、非数字和Long溢出返回400 VALIDATION_FAILED。认证先执行，未认证请求按既有401契约处理。

```http
PUT /api/v1/users/me/favorites/123
Authorization: Bearer <ACCESS_TOKEN>
```

## 成功响应与幂等

首次收藏和重复收藏均返回 `204 No Content`，`Cache-Control: no-store`，响应体为空。

```http
HTTP/1.1 204 No Content
Cache-Control: no-store
```

同一用户与歌曲以数据库联合主键保证唯一。重复请求保持首次收藏时间及全部审计字段，客户端可以在网络故障或提交结果不确定时重试。重试时仍需通过当前认证和歌曲可见性检查。

首版每用户收藏数量无业务上限，收藏接口暂不增加专用限流。

## 归档、删除及并发

- 歌曲归档保留已有收藏，后续访问继续受当前登录角色约束。
- 歌曲彻底删除时，在数据库删除事务内级联清除对应收藏关系。
- 用户记录删除时，在数据库删除事务内级联清除其收藏关系。
- 收藏事务按歌曲主键排他锁定当前状态，再验证角色与写入，同一歌曲上的收藏写入串行，其他歌曲可独立处理。
- 收藏先提交、归档后提交时保留收藏。归档先提交时USER请求返回404，ADMIN仍可收藏归档记录。
- 收藏先提交、删除后提交时自动清除关系。删除先提交时收藏返回404。
- 既有删除流程先清理媒体再删除数据库记录，ADMIN可在数据库删除完成前短暂收藏该归档记录，随后数据库级联清除。

收藏成功后不承诺该歌曲持续存在或保持可见。时间以UTC、DATETIME(6)微秒精度写入。

## 错误响应

业务错误为 `application/problem+json`，携带稳定code和 `urn:problem:music:*` 类型。收藏存储故障响应显式使用no-store，其他错误沿用现有安全链与统一异常处理。

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 路径歌曲ID格式、正数或范围校验失败 |
| 401 | UNAUTHORIZED | 未提供有效认证 |
| 401 | INVALID_TOKEN | 必要身份声明无效 |
| 401 | TOKEN_REVOKED | Token版本、用户存在性或登录角色失效 |
| 404 | TRACK_NOT_FOUND | 歌曲不存在或对当前登录角色不可见 |
| 503 | FAVORITE_STORE_UNAVAILABLE | 收藏数据库读取、写入、约束、锁等待、死锁或事务故障 |
| 503 | AUTH_STATE_STORE_UNAVAILABLE | 认证状态存储故障 |
| 500 | INTERNAL_ERROR | 未预期内部错误 |

JWT签名、过期、issuer或audience解码校验失败沿用资源服务器的401与WWW-Authenticate行为，部分响应没有业务ProblemDetail正文。

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

以下请求沿用当前项目路由与全局异常处理行为，已由真实SpringBootTest验证。它们均无法匹配收藏Controller，收藏Service无调用且数据库无新增。

| 请求路径，Method均为PUT | 有效认证 | 未认证 |
| --- | --- | --- |
| /api/v1/users/me/favorites | 500 INTERNAL_ERROR，application/problem+json | 401 UNAUTHORIZED |
| /api/v1/users/me/favorites/ | 500 INTERNAL_ERROR，application/problem+json | 401 UNAUTHORIZED |
| /api/v1/users/me/favorites/123/ | 500 INTERNAL_ERROR，application/problem+json | 401 UNAUTHORIZED |

当前全局兜底将未匹配路径的NoResourceFoundException转换为500。客户端应使用完整的数字ID路径。正常匹配接口后的歌曲不存在或不可见使用404 TRACK_NOT_FOUND。

## curl

POSIX shell，ACCESS_TOKEN由已有登录流程取得。歌曲123需对当前登录角色可见。

```sh
curl --request PUT 'http://localhost:8080/api/v1/users/me/favorites/123' \
  --header "Authorization: Bearer ${ACCESS_TOKEN}"
```
