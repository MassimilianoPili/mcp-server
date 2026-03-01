# MCP Server

Server MCP monolitico Spring Boot che aggrega 10 librerie di tool: SQL, MongoDB, Azure DevOps, Azure Cloud, Filesystem, OpenShift, Docker, Jira Cloud, Vector Search e API Proxy.

## Build

```bash
# Build
/opt/maven/bin/mvn clean package -DskipTests

# Run (STDIO mode)
/opt/java/bin/java -jar target/mcp-server-0.0.1-SNAPSHOT.jar
```

Java 17 richiesto. Maven: `/opt/maven/bin/mvn`, Java: `/opt/java/bin/java`.

## Running as MCP Server

```bash
claude mcp add --transport stdio --scope user mcp-server -- /opt/java/bin/java -jar /data/massimiliano/Vari/mcp/target/mcp-server-0.0.1-SNAPSHOT.jar
```

## Struttura Progetto

```
src/main/java/com/example/mcp/
├── McpServerApplication.java          # Entry point
└── tools/ApiProxyTools.java           # @ReactiveTool: api_get, api_post, api_health
```

I tool provengono dalle librerie auto-configurate (via `META-INF/spring/AutoConfiguration.imports`):

| Libreria | Package | Tool | Attivazione |
|----------|---------|------|-------------|
| mcp-sql-tools | `io.github.massimilianopili.mcp.sql` | db_query, db_tables, db_count, db_list_databases, db_list_schemas | Sempre attivo (datasource default H2) |
| mcp-mongo-tools | `io.github.massimilianopili.mcp.mongo` | mongo_find, mongo_count, mongo_list_collections, mongo_aggregate, mongo_list_databases | `MCP_MONGO_ENABLED=true` |
| mcp-filesystem-tools | `io.github.massimilianopili.mcp.filesystem` | fs_list, fs_read, fs_search | Sempre attivo |
| mcp-devops-tools | `io.github.massimilianopili.mcp.devops` | 47 tool (WIQL, work item, git, pipeline, board, release) | `MCP_DEVOPS_PAT` |
| mcp-azure-all | `io.github.massimilianopili.mcp.azure.*` | ~64 tool (subscription, RG, VM, AKS, VNet, SQL, Key Vault, ecc.) | `MCP_AZURE_CLIENT_ID` |
| mcp-ocp-tools | `io.github.massimilianopili.mcp.ocp` | 49 tool (project, pod, deployment, route, build, node, ecc.) | `MCP_OCP_TOKEN` |
| mcp-docker-tools | `io.github.massimilianopili.mcp.docker` | 41 tool (container, image, network, volume, system, compose) | `MCP_DOCKER_HOST` |
| mcp-jira-tools | `io.github.massimilianopili.mcp.jira` | 24 tool (issue JQL, board, sprint, comment, user) | `MCP_JIRA_API_TOKEN` |
| mcp-vector-tools | `io.github.massimilianopili.mcp.vector` | vector search multi-provider: Ollama/ONNX/OpenAI (PostgreSQL + pgvector) | `MCP_VECTOR_ENABLED=true` |

## Annotazioni Tool

- **`@Tool`** (Spring AI): Sincroni — sql, filesystem, mongo
- **`@ReactiveTool`** (spring-ai-reactive-tools): Asincroni `Mono<T>` — devops, azure, ocp, docker, jira, ApiProxy
- **`@ToolParam`**: Descrizione parametri per schema MCP

## Configurazione (Variabili d'Ambiente)

### Database SQL
- `MCP_DB_URL`, `MCP_DB_DRIVER`, `MCP_DB_USER`, `MCP_DB_PASSWORD`
- `MCP_DB_NAMES` — multi-DB (pattern: `MCP_DB_{NAME}_URL`, etc.)
- `MCP_DB_SCHEMA` — schema default (default: PUBLIC)

### File System
- `MCP_FS_BASEDIR` — directory base (default: `C:/NoCloud`)

### API Proxy
- `MCP_API_BASEURL` — URL API target (default: `http://localhost:8080`)

### Azure DevOps (condizionale)
- `MCP_DEVOPS_PAT` — Personal Access Token (abilita tutti i tool DevOps)
- `MCP_DEVOPS_ORG`, `MCP_DEVOPS_PROJECT`, `MCP_DEVOPS_TEAM`

### Azure Cloud (condizionale)
- `MCP_AZURE_TENANT_ID`, `MCP_AZURE_CLIENT_ID`, `MCP_AZURE_CLIENT_SECRET`, `MCP_AZURE_SUBSCRIPTION_ID`

### MongoDB (condizionale)
- `MCP_MONGO_ENABLED=true` — abilita tool MongoDB
- `MCP_MONGO_URI`, `MCP_MONGO_NAMES`

### OpenShift (condizionale)
- `MCP_OCP_TOKEN` — Bearer token (`oc whoami -t`)
- `MCP_OCP_SERVER`, `MCP_OCP_SKIP_TLS_VERIFY`, `MCP_OCP_NAMESPACE`

### Docker (condizionale)
- `MCP_DOCKER_HOST` — Docker daemon (es. `unix:///var/run/docker.sock`)
- `MCP_DOCKER_TLS_VERIFY`, `MCP_DOCKER_CERT_PATH`, `MCP_DOCKER_API_VERSION`

### Jira Cloud (condizionale)
- `MCP_JIRA_API_TOKEN` — API token Atlassian
- `MCP_JIRA_BASE_URL`, `MCP_JIRA_EMAIL`

### Vector Search (condizionale)
- `MCP_VECTOR_ENABLED=true` — abilita tool vector search
- `MCP_VECTOR_PROVIDER` — provider embedding: ollama (default), onnx, openai
- `MCP_VECTOR_DB_URL`, `MCP_VECTOR_DB_USER`, `MCP_VECTOR_DB_CREDENTIAL`
- `MCP_VECTOR_CONVERSATIONS_PATH`, `MCP_VECTOR_DOCS_PATH`
- `MCP_VECTOR_OLLAMA_BASE_URL`, `MCP_VECTOR_OLLAMA_MODEL`
- `MCP_VECTOR_ONNX_MODEL_CACHE`, `MCP_VECTOR_OPENAI_API_KEY`

## Dipendenze

- Spring Boot 3.4.1, Spring AI 1.0.0, spring-ai-reactive-tools 0.2.0
- H2, Oracle (ojdbc11), PostgreSQL, Spring Data MongoDB, Spring WebFlux
- Librerie MCP: sql-tools, devops-tools, filesystem-tools, mongo-tools, azure-all, ocp-tools, docker-tools, jira-tools, vector-tools
