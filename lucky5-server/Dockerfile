# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -pl lucky5-server -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system lucky5 \
    && useradd --system --gid lucky5 --create-home lucky5

WORKDIR /opt/lucky5
COPY --from=build --chown=lucky5:lucky5 /workspace/lucky5-server/target/lucky5-server.jar app.jar

ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx1024m -Djava.security.egd=file:/dev/./urandom"

USER lucky5
EXPOSE 48080

ENTRYPOINT ["java", "-jar", "app.jar"]
