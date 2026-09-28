#!/usr/bin/env bash
#
# 运行 jmqtt-admin 容器。额外参数原样透传给 docker run(在镜像名之前)。
#
# 用法示例:
#   ./run.sh                                                  # 默认配置(用 application.yml 内置值)
#   ./run.sh -e JMQTT_ADMIN_REDIS_MODE=standalone \
#            -e JMQTT_ADMIN_REDIS_NODES=10.0.0.6:6379         # 覆盖 Redis(Spring Boot 松散绑定)
#   ./run.sh --memory 512m                                    # 限制内存
#   IMAGE=jmqtt-admin:0.1.0-SNAPSHOT ./run.sh                 # 指定镜像 tag
#
# 端口约定: 9100(页面 + /api + actuator)只绑本机 —— 管理台能驱逐客户端,
# 不该直接暴露公网; 远程访问走 SSH 隧道, 或由内网反代(在反代上做 TLS)转发。
set -euo pipefail
cd "$(dirname "$0")"

IMAGE="${IMAGE:-jmqtt-admin:latest}"
NAME="${NAME:-jmqtt-admin}"

if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "容器 $NAME 已存在, 先删除(如需保留日志请自行导出)" >&2
    docker rm -f "$NAME"
fi

exec docker run -d \
    --name "$NAME" \
    --restart unless-stopped \
    -p 127.0.0.1:9100:9100 \
    -e TZ="${TZ:-Asia/Shanghai}" \
    "$@" \
    "$IMAGE"
