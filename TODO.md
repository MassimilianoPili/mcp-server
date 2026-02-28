# MCP Server — TODO

## Supporto SSE Transport per Agent Framework

Il server MCP attualmente usa solo il trasporto **STDIO** (standard per Claude Code CLI).
Per supportare i worker dell'agent-framework come client MCP via rete, servono le seguenti modifiche.

### 1. Abilitare il transport SSE (HTTP)

Aggiungere `spring-ai-starter-mcp-server-webflux` (o `webmvc`) al `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

Questo espone automaticamente l'endpoint `/sse` per le connessioni MCP client via Server-Sent Events.

Configurazione in `application.yml`:

```yaml
spring:
  ai:
    mcp:
      server:
        sse-message-endpoint: /mcp/message
```

### 2. Profili Spring Boot per gruppi di tool

I worker dell'agent-framework dichiarano quali "server logici" usano (git, repo-fs, openapi, test, azure).
Il server MCP puo' esporre sottoinsiemi di tool via profili:

```yaml
# application-git.yml — solo tool git
mcp.devops.pat: ${DEVOPS_PAT}
# disabilita gli altri tool

# application-repo-fs.yml — solo tool filesystem
# abilita solo FileSystemToolsAutoConfiguration
```

In alternativa, un singolo server che espone TUTTI i tool e lascia al client (worker) il filtraggio
via allowlist. Questa e' la soluzione piu' semplice e consigliata per iniziare.

### 3. Deploy su rete Docker shared

Aggiungere un `docker-compose.yml` (o estendere quello esistente) per deployare il server MCP
come container sulla rete `shared`:

```yaml
services:
  mcp-server:
    build: .
    networks:
      - shared
    ports:
      - "127.0.0.1:8095:8080"  # solo localhost per debug
    environment:
      - SPRING_PROFILES_ACTIVE=sse
      - DEVOPS_PAT=${DEVOPS_PAT}
      - MCP_DOCKER_HOST=unix:///var/run/docker.sock

networks:
  shared:
    external: true
```

I worker si connettono via `http://mcp-server:8080` (DNS Docker).

### 4. Health check

Esporre un endpoint `/health` per readiness probe:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  health:
    mcp:
      enabled: true
```

### 5. Sicurezza (fase successiva)

- Autenticazione: header `Authorization: Bearer <token>` sulle connessioni SSE
- mTLS: certificati reciproci tra worker e MCP server
- Network policy: solo i container worker possono raggiungere `mcp-server:8080`

### Priorita'

1. **SSE transport** — minimo necessario per i worker
2. **Docker deploy** — container sulla rete shared
3. **Health check** — readiness probe
4. **Profili tool** — opzionale, il filtraggio client-side basta per iniziare
5. **Sicurezza** — fase successiva
