# ============================================================
# img-service 运行镜像
# 前提：jar 已在本地用 gradlew bootJar 打好，与 Dockerfile 一起上传
# 因此这里不装 Gradle/JDK，只装 JRE，镜像小、构建快、不依赖服务器网络拉依赖
# ============================================================
FROM eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8

# ------------------------------------------------------------
# 缩略图功能（ImageThumbnailUtil）依赖 JDK 的 AWT / ImageIO。
# Spring Boot 默认 java.awt.headless=true，纯 BufferedImage 缩放不需要字体，
# 理论上开箱可用（已在本地以 headless 模式验证 4 个用例通过）。
#
# 若部署后 /thumbs/ 接口报 500，且日志出现：
#     libfontconfig.so.1: cannot open shared object file
# 说明基础镜像缺字体库，取消下面这行的注释后重新构建镜像即可：
# RUN apt-get update && apt-get install -y --no-install-recommends fontconfig \
#     && rm -rf /var/lib/apt/lists/*
# ------------------------------------------------------------

# JVM 参数：可在 1Panel 环境变量里覆盖（改这个不用重新构建镜像）
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

WORKDIR /app

# 产物由 build.gradle 的 bootJar.archiveFileName 固定为 img-service.jar
COPY img-service.jar /app/img-service.jar

# 挂载点：堆转储 / 应用日志（1Panel 里把 /app/logs 映射到宿主机磁盘）
RUN mkdir -p /app/logs

# 非 root 运行，降低容器逃逸风险。
#   · UID/GID 固定为 10001，不用 useradd -r 的动态号段（100-999）。
#     理由与 excel-import-service 一致：宿主机 bind mount 的 /opt/<服务>/logs
#     要用确定的 chown 授权；动态 UID 一旦因基础镜像变化而换号，宿主目录的属主
#     就对不上，日志会静默变空（不报错，只能从「文件一直是空的」看出来）。
#   · 不用 1000：基础镜像 eclipse-temurin:21-jre 里已被占用，
#     groupadd -g 1000 会报 "GID '1000' already exists" 直接构建失败。
RUN groupadd -g 10001 app && useradd -u 10001 -g 10001 -M -d /app -s /bin/sh app && chown -R app:app /app
USER app

EXPOSE 8082

# 用 exec 让 java 直接成为 PID 1：
#   1) docker stop 的 SIGTERM 能被 JVM 收到，走 Spring 优雅停机
#   2) 不带 exec 时 SIGTERM 只发给 sh，JVM 收不到，容器要等 10s 被 SIGKILL 强杀
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/img-service.jar --spring.profiles.active=prod"]
