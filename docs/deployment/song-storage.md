# 歌曲存储基础部署

## 1. 组件部署位置

- 无公网服务器：Spring Boot、MySQL、Redis、MinIO。
- 阿里云服务器：HTTPS 反向代理或安全隧道入口。
- 本地电脑：开发、测试和本地 Docker 依赖。

MinIO bucket 必须保持私有，不通过阿里云反向代理暴露 MinIO API 或管理控制台。

## 2. 配置

复制环境变量模板并填写真实值：

```powershell
Copy-Item .env.example .env
```

关键变量：

```text
DB_URL
DB_USERNAME
DB_PASSWORD
REDIS_PASSWORD
JWT_SECRET
MINIO_ROOT_USER
MINIO_ROOT_PASSWORD
S3_ENDPOINT
S3_ACCESS_KEY
S3_SECRET_KEY
S3_AUDIO_BUCKET
S3_COVERS_BUCKET
```

生产环境不得提交 `.env`，也不得在代码中保存 MinIO、数据库或 JWT 密钥。

Docker Compose 默认使用已固定摘要的 `bitnamilegacy/minio` 镜像。需要升级时通过 `MINIO_IMAGE` 设置经过验证的固定镜像标签或镜像摘要，不使用浮动 `latest`。

## 3. 本地开发流程

启动 MySQL、Redis、MinIO 和 bucket 初始化容器：

```powershell
docker compose up -d mysql redis minio minio-init
```

确认依赖健康：

```powershell
docker compose ps
```

运行完整构建验证：

```powershell
.\gradlew.bat check
```

启动应用：

```powershell
.\gradlew.bat bootRun
```

Flyway 在应用启动时自动执行 `classpath:db/migration` 下的迁移。

MinIO 集成测试默认跳过。准备好 MinIO Docker 镜像后运行：

```powershell
$env:MINIO_INTEGRATION_TESTS = "true"
.\gradlew.bat test --tests "org.example.music.storage.S3AudioStorageIntegrationTest"
```

## 4. 无公网服务器部署流程

安装 Docker 和 Docker Compose，上传项目或构建制品，配置 `.env`，然后启动依赖：

```bash
docker compose up -d mysql redis minio minio-init
```

构建 Spring Boot 制品：

```powershell
.\gradlew.bat clean bootJar
```

将 `build/libs/music-0.0.1-SNAPSHOT.jar` 部署到服务器，并使用环境变量启动：

```bash
java -jar music-0.0.1-SNAPSHOT.jar
```

应用启动时执行 Flyway 迁移，创建：

- `users`
- `media_assets`
- `tracks`
- `media_imports`

## 5. 阿里云代理要求

- 仅代理 Spring Boot API。
- 使用 HTTPS。
- 关闭歌曲流响应缓冲。
- 设置足够长的读取超时。
- 不代理 MinIO 9000/9001 端口。
- 仅开放必要的 HTTP/HTTPS 入口。

### 歌曲播放流专项配置

`GET /api/v1/tracks/{trackId}/stream` 返回原始 MP3/FLAC，代理必须保留 Authorization、Range、If-Range 及其他条件请求头，并保留 206/304/412/416、Content-Range、Content-Length、ETag、Last-Modified 和 Vary。不暴露私有 bucket，不将 Token 放入 URL。

播放专用同步 S3 读取客户端使用显式依赖 `software.amazon.awssdk:apache-client:2.25.60`（由既有 BOM 固定），与封面、导入、删除使用的默认 S3Client 分离：

| 配置 / 环境变量 | 默认 | 播放读取含义 |
| --- | --- | --- |
| app.storage.connect-timeout / S3_CONNECT_TIMEOUT | 2s | 建立连接与等待池连接的超时 |
| app.storage.read-timeout / S3_READ_TIMEOUT | 30s | 上游 socket 读取空闲超时 |
| app.storage.stream.max-connections / S3_STREAM_MAX_CONNECTIONS | 50 | 专用连接池上限，必须为正整数 |

专用客户端不设置整次 SDK API 调用/尝试时限且不自动重试，持续有数据传输可超过 2s/30s。连接池等待和无数据空闲仍有界。既有默认客户端的调用预算和其他消费者行为不变。客户端取消或传输失败会 abort 未读完的源流，避免排空整首音频。

同步 Servlet 的线程会被播放占用。部署前按并发量设置并核对 `server.tomcat.threads.max`、`server.tomcat.max-connections`、`server.tomcat.accept-count` 和有限的 `server.tomcat.connection-timeout`；不得使用无限连接/写入等待。可在调试器读取实际 Tomcat ProtocolHandler 的 maxThreads、maxConnections、connectionTimeout 核对环境覆盖，不能只检查配置文件。播放集成测试将 connection-timeout 设为 1s（仅测试），反射确认线程/连接/超时上限有效，并让客户端停止读取验证写入超时后释放 S3 源流；生产值应按网络与压测选择，不直接照搬 1s。

Nginx 流路径配置示意（端口按实际部署调整；并发限额应在部署时另外配置）：

```nginx
location ~ ^/api/v1/tracks/[0-9]+/stream$ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Authorization $http_authorization;
    proxy_set_header Range $http_range;
    proxy_set_header If-Range $http_if_range;
    proxy_buffering off;
    proxy_cache off;
    gzip off;
    proxy_ignore_client_abort off;
    proxy_connect_timeout 5s;
    proxy_read_timeout 60s;
    send_timeout 60s;
}
```

不得对该路径做动态 gzip、音频变换或额外缓存，否则范围偏移和长度失效。proxy_read_timeout 是两次从应用读取之间的空闲时限，send_timeout 是向客户端发送的空闲时限，都不是歌曲总播放时长。慢客户端、网络阻塞和连接池满需有有限退出路径；中间如还有隧道/负载均衡，逐跳核对 buffering、压缩、空闲超时和断连传播。使用真实大音频测试拖动续传、下载、停止读取及中途断开，并观察应用线程、S3 池占用和 traceId 日志；避免通过提高所有超时掩盖连接泄漏。

请求必须携带 Bearer 请求头，原生 audio.src 无法直接附加该头的问题需后续媒体授权设计。当前不新增公开音频链接、URL Token 或 Cookie 登录。

## 6. 健康检查与备份

- 检查 Spring Boot Actuator `/actuator/health`。
- 检查 MySQL、Redis、MinIO Docker 健康状态。
- 定期备份 MySQL 数据。
- 定期备份 MinIO 数据卷。
- 恢复后使用 `media_assets.sha256` 核对对象完整性。

## 7. 数据库迁移文件

- `src/main/resources/db/migration/V1__create_users_table.sql`
- `src/main/resources/db/migration/V2__create_song_storage_tables.sql`

已执行的迁移不得修改；结构变化必须新增 `V3` 及以后的迁移。
