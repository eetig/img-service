# ============================================================
# img-service 运行镜像
# 前提：jar 已在本地用 gradlew bootJar 打好，与 Dockerfile 一起上传
# 因此这里不装 Gradle/JDK，只装 JRE，镜像小、构建快、不依赖服务器网络拉依赖
# ============================================================
FROM eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8

# JVM 参数：可在 1Panel 环境变量里覆盖（改这个不用重新构建镜像）
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

WORKDIR /app

# 产物由 build.gradle 的 bootJar.archiveFileName 固定为 img-service.jar
COPY img-service.jar /app/img-service.jar

# 挂载点：堆转储 / 应用日志（1Panel 里把 /app/logs 映射到宿主机磁盘）
RUN mkdir -p /app/logs

# 非 root 运行，降低容器逃逸风险
RUN groupadd -r app && useradd -r -g app -d /app app && chown -R app:app /app
USER app

EXPOSE 8080

# 用 exec 让 java 直接成为 PID 1：
#   1) docker stop 的 SIGTERM 能被 JVM 收到，走 Spring 优雅停机
#   2) 不带 exec 时 SIGTERM 只发给 sh，JVM 收不到，容器要等 10s 被 SIGKILL 强杀
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/img-service.jar --spring.profiles.active=prod"]
