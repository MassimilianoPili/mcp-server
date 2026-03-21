# === Stage 1: Maven build + Playwright browser install ===
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,id=mcp-server-m2 mvn dependency:resolve -q

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2,id=mcp-server-m2 mvn package -DskipTests -q

# Installa Chromium via Playwright CLI (dipendenza transitiva da mcp-playwright-tools)
RUN --mount=type=cache,target=/root/.m2,id=mcp-server-m2 mvn exec:java -q \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install --with-deps chromium"

# === Stage 2: runtime JRE ===
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    libglib2.0-0 libnss3 libnspr4 libdbus-1-3 libatk1.0-0 \
    libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libxcomposite1 \
    libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /root/.cache/ms-playwright /root/.cache/ms-playwright
ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
WORKDIR /app
COPY --from=build /build/target/mcp-server-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8099
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
