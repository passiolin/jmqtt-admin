# jmqtt-admin 运行镜像。前端构建产物已打进 jar(Spring Boot 默认从
# classpath:/static 提供页面), 页面 / /api / actuator 同在一个端口。
# 构建方式: deploy/build.sh —— 它先构建前端, 连同后端一起 mvn package,
# 把可执行 jar 暂存为 deploy/app.jar, 再以 deploy/ 为构建上下文执行 docker build。
#
# 管理台是内网工具: 建议只绑本机或内网, 远程访问走反代并在反代上终结 TLS。
FROM azul/zulu-openjdk-debian:21

# 日志时间戳时区, 运行时可覆盖: docker run -e TZ=UTC ...
ENV TZ=UTC

# 非 root 运行; 端口大于 1024, 无需特权
RUN groupadd -r jmqtt && useradd -r -g jmqtt -d /app -s /usr/sbin/nologin jmqtt

WORKDIR /app
COPY --chown=jmqtt:jmqtt app.jar /app/app.jar
USER jmqtt

# 页面 + /api + actuator 共用这一端口
EXPOSE 9100

# 容器内存感知: 管理台很轻, 堆占容器内存的 75% 足够
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# 与 broker 同一思路: 端口能建立 TCP 连接即视为健康(liveness 语义)。
# 注意: 若运行时用 SERVER_PORT 改了端口, 这里要跟着改(compose 里覆盖 healthcheck)。
# Redis 依赖与否不该进 healthcheck —— Redis 挂了重启容器解决不了问题;
# 要判断「控制台可用」用外部探针打 /actuator/health(含 Redis 探活)。
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/9100' || exit 1

# sh -c 是为了运行时展开 $JAVA_OPTS
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
