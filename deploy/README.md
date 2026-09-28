# 容器部署

镜像以 [azul/zulu-openjdk-debian:21](https://hub.docker.com/r/azul/zulu-openjdk-debian)(Zulu JDK 21,
amd64/arm64)为父镜像,非 root 运行,内置容器内存感知与端口健康检查。
**前端构建产物直接打进后端 jar**,由 Spring Boot 的默认静态资源处理(classpath:/static)提供,
页面 / `/api` / actuator 共用 9100 一个端口 —— 不需要单独的 Nginx 容器或 CDN。

## 构建

```bash
deploy/build.sh               # 前端构建 + mvn package(含测试) + docker build
deploy/build.sh --skip-tests  # 跳过后端测试
```

脚本流程:前端构建(frontend/dist)→ 拷入 `backend/src/main/resources/static` → `mvn package`
→ jar 暂存为 `deploy/app.jar` → `docker build`(构建上下文只有 deploy/)。
产出 `jmqtt-admin:<version>` 与 `jmqtt-admin:latest` 两个 tag。

受限环境下:

```bash
# 前端走备用构建路径(vite 在受限 Wasm 环境会以 WebAssembly.instantiate(): Out of memory 失败)
FRONTEND_BUILD=nowasm deploy/build.sh

# 受限网络(空 TLS 信任库)追加 Maven 参数
MAVEN_ARGS="-Dmaven.resolver.transport=wagon -Dmaven.wagon.http.ssl.insecure=true" deploy/build.sh
```

## 运行

```bash
deploy/run.sh                            # 单容器, 9100 只绑本机
deploy/run.sh -e JMQTT_ADMIN_REDIS_NODES=10.0.0.6:6379
```

或 compose(在本目录):

```bash
docker compose up -d
```

启动后访问 <http://127.0.0.1:9100>,默认账号 `jmqtt` / `jmqtt`。

## 端口

| 端口 | 用途 | 暴露建议 |
|---|---|---|
| 9100 | 管理台页面 + `/api` + actuator | 只绑本机或内网;远程走 SSH 隧道,或内网反代(反代上做 TLS) |

管理台能**驱逐客户端、发起节点排水**,永远不要直接暴露公网。

## 环境变量

application.yml 的值均可用环境变量覆盖(Spring Boot 松散绑定,
`jmqtt.admin.redis.nodes` → `JMQTT_ADMIN_REDIS_NODES`,规则是 `JMQTT_ADMIN_` + 大写下划线形式)。
常用变量:

| 环境变量 | 对应配置 | 默认值 |
|---|---|---|
| `SERVER_PORT` | 服务端口 | 9100 |
| `JMQTT_ADMIN_USERNAME` / `JMQTT_ADMIN_PASSWORD` | 登录凭据 | jmqtt / jmqtt |
| `JMQTT_ADMIN_REDIS_MODE` | Redis 形态:cluster / standalone / sentinel | cluster |
| `JMQTT_ADMIN_REDIS_NODES` | Redis 地址,逗号分隔(standalone 也走这个字段) | 10.10.10.248:6379…6384 |
| `JMQTT_ADMIN_REDIS_PASSWORD` | Redis 密码 | (见 application.yml) |
| `JMQTT_ADMIN_REDIS_DATABASE` | Redis database | 0 |
| `JMQTT_ADMIN_REDIS_KEY_PREFIX` | ★ key 前缀,必须与 broker 一致 | jmqtt |
| `JMQTT_ADMIN_REDIS_COMMANDTIMEOUTMS` | Redis 命令超时 | 3000 |

**自定义路径的配置文件**(docker 挂载场景, 文件名可自定义, 与环境变量并存且优先级更高):

```bash
docker run -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/jmqtt-admin/overrides.yml \
           -v ./prod-config.yml:/etc/jmqtt-admin/overrides.yml:ro jmqtt-admin:latest
```

JVM 参数通过 `JAVA_OPTS` 覆盖,默认 `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`。

## 注意

- **健康检查**写死了 9100(liveness 语义:端口能建 TCP 连接即健康);若用 `SERVER_PORT`
  改端口,需在 compose 里覆盖 `healthcheck`。要判断「控制台真的可用」(含 Redis 探活),
  用外部探针打 `/actuator/health`,不要放进 HEALTHCHECK —— Redis 挂了重启容器解决不了问题。
- **控制台只连 Redis**,不直连任何 broker;部署上只需要它能到达 Redis。
- **key-prefix 配错不会报错**,只表现为「控制台里一个节点都没有」—— 与 broker 的
  `jmqtt.broker.redis.key-prefix` 保持一致。
