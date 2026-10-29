# 一键镜像：node 编前端 → JDK 编后端 → JRE 只跑。
# 构建上下文是仓库根目录（huixiang/）：
#
#   docker build -f web/Dockerfile -t huixiang-web .
#   docker run -d -p 127.0.0.1:8080:8080 -e ACCESS_TOKEN=... -e ALLOWED_ORIGINS=https://你的域名 huixiang-web
#
# 注意：这份 Dockerfile 没有在真实 Docker 环境里验证过（开发机没装 Docker）。
# 服务器部署优先用 web/deploy/DEPLOY.md 里的 systemd 路径，那条是实测过的。

FROM node:22-alpine AS client
WORKDIR /c
COPY web/client/package.json web/client/package-lock.json ./
RUN npm ci
COPY web/client ./
RUN npm run build

# 网关是独立构建（web/gateway/settings.gradle.kts），只要有 gradle wrapper 和 tsproto 源码，
# 不需要 Android SDK，也不用复制 app/。
FROM eclipse-temurin:21-jdk AS gateway
WORKDIR /src
COPY gradle ./gradle
COPY gradlew ./
COPY tsproto ./tsproto
COPY web/gateway ./web/gateway
RUN chmod +x /src/gradlew \
 && cd /src/web/gateway \
 && /src/gradlew installDist --no-daemon --console=plain

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=gateway /src/web/gateway/build/install/webgateway/ ./
COPY --from=client /c/dist ./static

ENV PORT=8080
# 容器内必须监听所有接口，否则 -p 映射不进来；对外仍然只绑 127.0.0.1
ENV HOST=0.0.0.0
ENV WEB_DIST=/app/static
ENV LOG_LEVEL=INFO

EXPOSE 8080
ENTRYPOINT ["/app/bin/webgateway"]
