# 歌曲详情接口实现计划

基线：main @ e057c6b。设计已 accepted；用户允许直接编写计划并实现。

1. 新增 TrackDetailController、TrackDetailService、TrackDetailResponse。只读事务内读取歌曲、校验可信主体角色，再查询可选封面并映射 DTO。成功响应 no-store。
2. 复用现有 Repository、安全配置和异常；无迁移、无新依赖、新错误码。
3. 单测覆盖角色与状态矩阵、元信息和空值、资产一致性及数据库异常；WebMvcTest 验证参数、认证和 application/problem+json 错误响应。
4. MySQL、Redis Testcontainers 集成测试使用真实签名 JWT，覆盖上传者本人限制、管理员普通角色登录、token version 撤销、重复读取无写入。
5. 完整 OpenAPI、中文 get-track-detail.md，列出具体错误码、权限矩阵与尚未实现的封面读取限制。
6. 设置 MINIO_INTEGRATION_TESTS=true 执行 .\gradlew.bat check，统计通过、失败、跳过，准备审核材料。

默认不新增缓存、专用限流或条件请求；本次只实现详情接口。
