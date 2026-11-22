# syntax=docker/dockerfile:1

# 构建阶段：使用固定 Gradle + JDK 17（与 Gradle toolchain 一致），避免依赖宿主机环境
FROM gradle:9.9-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle --no-daemon installDist

# 运行阶段：只带 JRE 与构建产物；客户端本体由用户挂载到 /data，不进镜像
FROM eclipse-temurin:21-jre

RUN groupadd -r webmc && useradd -r -g webmc -d /opt/webmc -s /usr/sbin/nologin webmc
WORKDIR /opt/webmc
COPY --from=build /src/build/install/orzmc-webmc/ ./
RUN mkdir -p /data/versions /tmp/webmc-cache && chown -R webmc:webmc /data /tmp/webmc-cache /opt/webmc

ENV WEBMC_DATA_DIR=/data/versions \
    WEBMC_CACHE_DIR=/tmp/webmc-cache \
    WEBMC_PORT=8300
VOLUME ["/data"]
EXPOSE 8300
USER webmc

# 端口探活（不依赖 curl/wget）
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8300' || exit 1

ENTRYPOINT ["/opt/webmc/bin/orzmc-webmc"]
