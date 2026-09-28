#!/usr/bin/env bash
#
# 构建 jmqtt-admin Docker 镜像(前端 + 后端打进同一个 jar, 同一端口服务):
#   1. npm ci + 前端构建, 产出静态文件到 frontend/dist
#   2. 把 dist 拷进 backend/src/main/resources/static —— Spring Boot 默认从
#      classpath:/static 提供页面; 前端是 hash 路由, 不需要 SPA 转发规则
#   3. mvn package 打出可执行 jar(默认含测试)
#   4. 把 jar 暂存为 deploy/app.jar —— 构建上下文只有 deploy/, 不必把整个仓库发给 daemon
#   5. docker build, 同时打 <name>:<version> 与 <name>:latest 两个 tag
#
# 用法:
#   ./build.sh               # 完整构建(含后端测试)
#   ./build.sh --skip-tests  # 跳过后端测试
#
# 环境变量:
#   IMAGE_NAME     镜像名, 默认 jmqtt-admin
#   VERSION        镜像版本 tag, 默认从 backend/pom.xml 解析; 例: VERSION=1.2.0 ./build.sh
#   FRONTEND_BUILD 前端构建路径: vite(默认) | nowasm。受限环境(Wasm 被禁用或
#                  ulimit 限制虚拟内存)下 vite 会以 WebAssembly.instantiate():
#                  Out of memory 失败, 用 nowasm 绕开 —— 见主 README「为什么有两条构建路径」
#   MAVEN_ARGS     追加的 maven 参数。受限网络(空 TLS 信任库)下:
#                  MAVEN_ARGS="-Dmaven.resolver.transport=wagon -Dmaven.wagon.http.ssl.insecure=true" ./build.sh
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE_NAME="${IMAGE_NAME:-jmqtt-admin}"
FRONTEND_BUILD="${FRONTEND_BUILD:-vite}"
MAVEN_ARGS="${MAVEN_ARGS:-}"

# 版本号: 支持环境变量传入(VERSION=1.2.0 ./build.sh), 未设置时从 pom.xml 里
# <artifactId>jmqtt-admin-backend</artifactId> 的下一行解析
VERSION="${VERSION:-$(sed -n '/<artifactId>jmqtt-admin-backend<\/artifactId>/{n;p}' backend/pom.xml | sed -e 's:.*<version>\(.*\)</version>.*:\1:')}"
if [[ -z "$VERSION" ]]; then
    echo "无法解析版本号(未设置 VERSION, 且无法从 backend/pom.xml 解析)" >&2
    exit 1
fi

echo "==> 前端构建 (${FRONTEND_BUILD})"
(
    cd frontend
    if [[ ! -d node_modules ]]; then
        echo "==> npm ci"
        npm ci
    fi
    case "$FRONTEND_BUILD" in
        vite)   npm run build ;;
        nowasm) npm run build:nowasm ;;
        *) echo "FRONTEND_BUILD 只支持 vite | nowasm, 当前: ${FRONTEND_BUILD}" >&2; exit 1 ;;
    esac
)

DIST="frontend/dist"
if [[ ! -f "${DIST}/index.html" ]]; then
    echo "找不到 ${DIST}/index.html" >&2
    exit 1
fi

echo "==> 前端产物 ${DIST} -> backend/src/main/resources/static"
# 该目录是生成物(.gitignore 已忽略)。连 target/classes/static 一起清掉:
# 前端资源带内容 hash, 只清源目录的话, maven 增量构建会把上一轮的旧 hash 文件也打进 jar
rm -rf backend/src/main/resources/static backend/target/classes/static
mkdir -p backend/src/main/resources/static
cp -r "${DIST}"/. backend/src/main/resources/static/

MVN_ARGS=($MAVEN_ARGS package)
if [[ "${1:-}" == "--skip-tests" ]]; then
    MVN_ARGS+=( -DskipTests )
fi

echo "==> mvn ${MVN_ARGS[*]}"
(cd backend && mvn "${MVN_ARGS[@]}")

JAR="$(ls backend/target/jmqtt-admin-backend-*.jar)"
if [[ ! -f "$JAR" ]]; then
    echo "找不到 backend/target/jmqtt-admin-backend-*.jar" >&2
    exit 1
fi

echo "==> 暂存 ${JAR} -> deploy/app.jar"
cp -f "$JAR" deploy/app.jar

echo "==> docker build (${IMAGE_NAME}:${VERSION} / ${IMAGE_NAME}:latest)"
docker build -t "${IMAGE_NAME}:${VERSION}" -t "${IMAGE_NAME}:latest" -f deploy/Dockerfile deploy/

echo "==> 完成: ${IMAGE_NAME}:${VERSION}"
