# 星云盘后端运行镜像：直接打包宿主机已构建的 jar，不在容器内跑 Maven。
# 构建前先执行：mvn -q -pl st-api -am -DskipTests package
FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="st-cloud-api" \
      org.opencontainers.image.description="星云盘后端服务（Spring Boot）"

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

WORKDIR /app
COPY st-api/target/st-api-*.jar /app/app.jar

RUN groupadd --system stcloud \
    && useradd --system --gid stcloud --home /app stcloud \
    && chown -R stcloud:stcloud /app
USER stcloud

EXPOSE 8080
# 存活探针：不依赖 curl，直接用 bash 的 /dev/tcp
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=10 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
