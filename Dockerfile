# === Stage 1: Maven build + Playwright browser install ===
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

ARG GITEA_TOKEN

# Maven settings: Gitea registry (LAN) come primo repo, Central fallback
COPY settings-docker.xml /root/.m2/settings.xml

# Dependencies + Playwright browser (cache mount avoids re-download on pom.xml changes)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository,id=mcp-server-m2 mvn dependency:resolve -q
RUN --mount=type=cache,target=/tmp/pw-cache,id=mcp-playwright \
    --mount=type=cache,target=/root/.m2/repository,id=mcp-server-m2 \
    cp -rn /tmp/pw-cache/. /root/.cache/ms-playwright/ 2>/dev/null || true \
    && mvn exec:java -q \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install --with-deps chromium" \
    && cp -ru /root/.cache/ms-playwright/. /tmp/pw-cache/

# App build (invalidato quando src/ cambia, layer Playwright sopra è cachato)
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2/repository,id=mcp-server-m2 mvn package -DskipTests -q

# === Stage 2: runtime JRE ===
FROM eclipse-temurin:21-jre-jammy

ARG BUILD_VERSION=dev
ENV BUILD_VERSION=${BUILD_VERSION}

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates-java \
    libglib2.0-0 libnss3 libnspr4 libdbus-1-3 libatk1.0-0 \
    libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libxcomposite1 \
    libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2 \
    \
    && rm -rf /var/lib/apt/lists/* \
    && cp /etc/ssl/certs/java/cacerts $JAVA_HOME/lib/security/cacerts

COPY --from=build /root/.cache/ms-playwright /root/.cache/ms-playwright
ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORKDIR /app
COPY --from=build /build/target/mcp-server-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8099
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
