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
