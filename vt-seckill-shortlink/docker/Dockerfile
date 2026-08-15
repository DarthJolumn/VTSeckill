# =====================================================================
# vtsl-seckill — 秒杀服务 Dockerfile
# JVM 参数固化（简历口径：分代 ZGC + 虚拟线程）
#   分代 ZGC:  -XX:+UseZGC -XX:+ZGenerational
#   GC 日志:   落盘 ./gc-logs（压测取证 + 面试证据）
#   虚拟线程:  由 Spring Boot 配置 spring.threads.virtual.enabled=true 开启
# =====================================================================
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY . .
# 分层构建：先依赖后源码，利用 Maven 缓存
RUN ./mvnw -q -o clean package -DskipTests -pl vtsl-seckill -am \
    || ./mvnw -q clean package -DskipTests -pl vtsl-seckill -am

FROM eclipse-temurin:25-jdk
WORKDIR /app

# ====== JVM 参数（与 vtsl-docs/performance/02-g1-vs-zgc.md 压测口径一致） ======
# -XX:+UseZGC -XX:+ZGenerational   : 分代 ZGC（GC 停顿 3ms，Proactive 并发触发）
# -Xlog:gc                          : GC 日志落盘（取证）
# -XX:+HeapDumpOnOutOfMemoryError   : OOM 自动堆转储
# 虚拟线程由 Spring 配置开启（spring.threads.virtual.enabled=true）
ENV JAVA_OPTS="\
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heapdump.hprof \
    -Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=20m"

COPY --from=builder /app/vtsl-seckill/target/*.jar app.jar

# 优雅停机：等待在途虚拟线程任务完成（配合 spring.lifecycle.timeout-per-shutdown-phase=30s）
EXPOSE 8090

ENTRYPOINT ["sh", "-c", "mkdir -p /app/logs && java $JAVA_OPTS -jar app.jar"]
