# Spring Boot 项目容器化部署指南（Gradle + 1Panel + Docker）

> 本文以 img-service 为例总结，可**直接复制到其他项目**使用。换项目时只需改「第七节」列的 6 处。

---

## 核心方法：三层分离

这是整套方案最关键的思想 —— **把"不变的"和"会变的"分开**，做到换服务器、换域名、换密码都不用重新写代码。

| 层 | 文件 | 多久变一次 | 变更代价 |
|---|---|---|---|
| **① 构建层** | `Dockerfile` | 几乎不变 | 重新构建镜像 |
| **② 配置层** | `application-prod.yml` | 很少变 | 重新打 jar + 构建镜像 |
| **③ 运行层** | 1Panel 环境变量 / compose | 经常变（登录地址、密码、端口） | **只重建容器**，几十秒 |

**推论**：凡是"不同环境不一样"的值（数据库地址、账号密码、服务发现地址、对外域名、端口），**一律走第③层环境变量**，绝不写死在代码或 `application.yml` 里。

### 三个配套原则

1. **jar 在本地打，服务器只装 JRE**
   服务器不装 Gradle、不下依赖，镜像小（~280MB vs ~1.5GB）、构建快、几乎不会因网络失败。

2. **容器之间用「容器名」互访，靠同一 docker 网络**
   容器里的 `127.0.0.1` 指容器自己，务必用容器名（如 `nacos:8848`）。

3. **宿主机 Nginx 通过 `127.0.0.1:端口` 访问容器**
   Nginx 跑在宿主机（不在 docker 网络里），只能走宿主机端口。绑 `127.0.0.1` 而非 `0.0.0.0`，天然满足"微服务不暴露公网端口"。

---

## 一、改造项目（4 个文件）

### 1. `build.gradle`

```gradle
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    maven { url 'https://maven.aliyun.com/repository/central' }
    maven { url 'https://maven.aliyun.com/repository/spring' }
    mavenCentral()
}

// 中文源码必须 UTF-8
tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}

// 只留一个可执行 jar，避免 build/libs 下有俩 jar 导致 COPY 歧义
tasks.named('jar') { enabled = false }

// 固定产物名，Dockerfile 的 COPY 路径才稳定
bootJar {
    archiveFileName = 'xxx-service.jar'   // ← 换成你的项目名
    manifest {
        attributes('Build-Time': new Date().format("yyyy-MM-dd HH:mm:ss"))
    }
}
```

> **要点**：`archiveFileName` 固定产物名 + 禁用 plain jar。不固定的话产物名带版本号，Dockerfile 的 `COPY` 会随版本变化失效。

### 2. `Dockerfile`（放在项目根目录）

```dockerfile
FROM eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8

# JVM 参数走环境变量，调内存不用重新构建镜像
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

WORKDIR /app

# 产物名必须与 build.gradle 的 archiveFileName 一致
COPY xxx-service.jar /app/xxx-service.jar

RUN mkdir -p /app/logs

# 非 root 运行
RUN groupadd -r app && useradd -r -g app -d /app app && chown -R app:app /app
USER app

EXPOSE 8080

# exec 不能省：让 java 成为 PID 1，docker stop 的 SIGTERM 才能被 JVM 收到（优雅停机）
# 不加 exec 时 SIGTERM 只发给 sh，容器要等 10s 被 SIGKILL 强杀
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/xxx-service.jar --spring.profiles.active=prod"]
```

### 3. `src/main/resources/application-prod.yml`

```yaml
server:
  port: ${SERVER_PORT:8080}
  shutdown: graceful          # 优雅停机

spring:
  application:
    name: xxx-service
  lifecycle:
    timeout-per-shutdown-phase: 20s
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:nacos:8848}   # 容器名，不是 127.0.0.1
        namespace: ${NACOS_NAMESPACE:}                  # 必须填 ID 不是名称
        group: ${NACOS_GROUP:DEFAULT_GROUP}
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB

# 按项目实际依赖增删
xxx:
  endpoint: ${XXX_ENDPOINT:}

logging:
  level:
    root: info
  file:
    name: /app/logs/xxx-service.log
  logback:
    rollingpolicy:
      max-file-size: 20MB
      max-history: 15
      total-size-cap: 500MB
```

**所有值都写成 `${环境变量:默认值}` 形式**，这是第③层能生效的前提。

### 4. `.dockerignore`

```
.gradle
build
.idea
.git
*.iml
*.iws
*.ipr
out
.vscode
.DS_Store
```

---

## 二、打包与部署（5 步）

### ① 本地打包

IDEA 右侧 Gradle 面板 → `Tasks` → `build` → 双击 `bootJar`
或命令行：`gradlew.bat bootJar`（跳过测试：`-x test`）

产物：`build\libs\xxx-service.jar`

> 命令行报 `Gradle requires JVM 17 or later...` → `JAVA_HOME` 指向了 JDK 8，设成 JDK 21 的路径。IDEA 里跑没这问题。

### ② 准备上传目录 ⚠️

**不要用 `build/libs/` 当上传目录** —— `gradle clean` / `rebuild` 会把整个 `build/` 删掉，放在里面的文件会丢。

```bash
mkdir D:\deploy
copy build\libs\xxx-service.jar D:\deploy\
copy Dockerfile D:\deploy\
```

### ③ 上传到服务器

用 1Panel「文件」上传到 `/opt/xxx-service/`，**Dockerfile 和 jar 必须在同一目录**（`COPY` 是相对构建目录的路径）。

### ④ 1Panel 构建镜像

「容器」→「镜像」→「构建镜像」：
- 镜像名称：`xxx-service`，标签：`1.0` 或 `latest`
- 构建目录：`/opt/xxx-service`
- 构建

### ⑤ 1Panel 创建容器

「容器」→「编排」→「创建编排」→ 粘贴下节 compose → 创建。

---

## 三、compose 模板

```yaml
services:
  xxx-service:
    image: xxx-service:1.0
    container_name: xxx-service
    restart: unless-stopped
    environment:
      - "TZ=Asia/Shanghai"
      - "NACOS_SERVER_ADDR=nacos:8848"
      - "NACOS_NAMESPACE=你的命名空间ID"
      # 按项目增删
      # - "MYSQL_HOST=mysql"
      # - "REDIS_HOST=redis"
    volumes:
      - ./logs:/app/logs
    ports:
      # 只绑 127.0.0.1，宿主机 Nginx 才能连、公网连不上
      - "127.0.0.1:8080:8080"
    networks:
      - nacos_default

networks:
  # 复用 Nacos 所在网络，用 external 引用不创建
  nacos_default:
    external: true
    name: nacos_default
```

**同一个 docker 网络是容器名互访的前提**，网络名要填 Nacos/MySQL/Redis 实际所在的网络（用 `docker network ls` 查）。

---

## 四、Nginx 反代（1Panel「网站」→ 反向代理）

```nginx
location /api/xxx/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 10m;          # 有文件上传则必配
    proxy_read_timeout   60s;
    proxy_send_timeout   60s;
}
```

---

## 五、验证清单

```bash
# 1. 容器起来了
docker ps | grep xxx-service
docker logs xxx-service --tail 50
#   期望：The following 1 profile is active: "prod"
#        Tomcat started on port 8080 (http)
#        Started XxxApplication in x.xxx seconds

# 2. 容器内连通性（注意：JRE 精简镜像没有 nc/curl！用 bash 内置 /dev/tcp）
sudo docker exec -it xxx-service sh
  getent hosts nacos                                    # DNS 解析
  bash -c 'echo > /dev/tcp/nacos/8848' && echo "8848 OK"   # Nacos HTTP
  bash -c 'echo > /dev/tcp/nacos/9848' && echo "9848 OK"   # Nacos 2.x gRPC，必测
  exit

# 3. Nacos 控制台「服务管理 → 服务列表」→ 对应 namespace 下应出现服务名

# 4. 接口验证
curl -F "file=@test.jpg" https://你的域名/api/xxx/upload

# 5. 确认容器跑的是新镜像（重建镜像后必查）
docker inspect -f '{{.Image}}' xxx-service      # 容器用的镜像 ID
docker inspect -f '{{.Id}}' xxx-service:1.0     # 镜像当前 ID
#   两个 ID 不一致 = 容器还在跑旧镜像
```

---

## 六、踩坑清单 ⚠️

按踩到的概率排序，**每个新项目都会再遇到一遍**：

### 坑 1：容器内不能用 `127.0.0.1`
容器里的 `127.0.0.1` 指容器**自己**，不是宿主机。所有依赖服务（Nacos/MySQL/Redis/MinIO）必须用**容器名**或内网 IP。

### 坑 2：重建镜像 ≠ 容器更新
重新构建镜像后，**正在运行的容器仍抱着旧镜像 ID 在跑**。必须**删除容器重建**，否则改了等于没改。
用第五节第 5 条的两个 ID 对比来确认。

### 坑 3：`gradle clean` 会删掉 `build/` 下的一切
包括你手动放进去的 `Dockerfile`。上传目录要独立于 `build/`。

### 坑 4：返回给浏览器的 URL，host 必须是浏览器能解析的
典型场景：对象存储（MinIO/OSS）的**预签名 URL** 里 host 就是配置的 endpoint。
如果 endpoint 填容器名 → 接口返回 200，但前端 `<img src>` 打不开。
**规则：只要这个 url 要给浏览器，endpoint 就得是浏览器可达的地址。**

### 坑 5：Nginx 反代对象存储必须传原始 Host
预签名 URL 用 SigV4 签名，**Host 头参与签名**。Nginx 默认会改 Host → 403 `SignatureDoesNotMatch`。
必须加 `proxy_set_header Host $host;`

### 坑 6：Nacos 2.x 需要 9848/9849 端口
客户端在 `server-addr` 端口基础上 **+1000** 走 gRPC。同 docker 网络内天然可达；跨主机/只映射 8848 会注册失败。

### 坑 7：Nacos namespace 要填 ID 不是名称
填名称会注册到 public，表现为"注册了但找不到服务"。

### 坑 8：`client_max_body_size`
Nginx 默认 1MB。有文件上传时大文件会返回 **413，且请求根本没到应用**，日志里查不到任何记录。

### 坑 9：JRE 精简镜像没有 `nc` / `curl` / `ping`
`eclipse-temurin:21-jre` 是精简 Ubuntu，直接 `nc -zv` 会报 `nc: not found`，**不代表网络不通**。
用 bash 内置：`bash -c 'echo > /dev/tcp/host/port'`
或起个临时调试容器：`docker run --rm -it --network <net> nicolaka/netshoot nc -zv host port`

### 坑 10：业务异常返回 HTTP 200
`@RestControllerAdvice` 返回 `Result` 时 HTTP 状态码是 200，错误码在 body 的 `code` 里。
前端和健康检查**不能只看 HTTP 状态码**。

### 坑 11：多网卡时 Nacos 注册错 IP
服务在列表里但调用方 502。显式指定：
```yaml
spring.cloud.nacos.discovery.ip: ${NACOS_REGISTER_IP:}
```

### 坑 12：时区
容器默认 UTC，日志时间差 8 小时。Dockerfile 里设 `TZ` + `-Duser.timezone`。

---

## 七、复用到新项目：只改这 6 处

| # | 位置 | 改成 |
|---|---|---|
| 1 | `build.gradle` 的 `archiveFileName` | 新 jar 名 |
| 2 | `Dockerfile` 的 `COPY` 和 `ENTRYPOINT` | 新 jar 名 |
| 3 | `Dockerfile` 的 `EXPOSE` | 新端口 |
| 4 | `application-prod.yml` 的 `application.name` 和依赖服务配置 | 新的 |
| 5 | `docker-compose.yml` 的镜像名/容器名/端口/环境变量 | 新的 |
| 6 | Nginx 的 `location` 前缀和 `proxy_pass` 端口 | 新的 |

**其余全部照抄。** SDK 层（Dockerfile 结构、三层分离思想、坑清单）完全通用。

---

## 附：日常运维速查

```bash
# 改环境变量（不重新构建镜像）
#   1Panel 面板改 → 删除容器 → 编排重新创建

# 更新代码版本
#   本地 bootJar → 上传 jar → 1Panel 重新构建镜像 → 删除容器重建

# 只看日志
docker logs -f xxx-service --tail 100

# 进容器排查
docker exec -it xxx-service sh

# 确认容器用了哪个镜像
docker inspect -f '{{.Image}}' xxx-service

# 镜像占空间了（清理悬空镜像）
docker image prune -f
```
