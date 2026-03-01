# MCP Server

Monolithic Spring Boot MCP (Model Context Protocol) server that aggregates 12 tool libraries into a single application. Each library activates conditionally based on environment variables, so you only enable what you need.

## Quick Start

```bash
# Build
mvn clean package -DskipTests

# Run (STDIO mode for Claude Code)
java -jar target/mcp-server-0.0.1-SNAPSHOT.jar

# Register with Claude Code
claude mcp add --transport stdio --scope user mcp-server -- java -jar /path/to/mcp-server-0.0.1-SNAPSHOT.jar
```

Requires Java 21+. See [SETUP.md](SETUP.md) for detailed deployment instructions.

## Included Libraries

| Library | Tools | Activation | Description |
|---------|-------|------------|-------------|
| [mcp-sql-tools](https://github.com/MassimilianoPili/mcp-sql-tools) | 5 | `MCP_SQL_ENABLED=true` (default) | SQL database queries (multi-DB, Oracle, H2, PostgreSQL) |
| [mcp-filesystem-tools](https://github.com/MassimilianoPili/mcp-filesystem-tools) | 3 | `MCP_FILESYSTEM_ENABLED=true` (default) | File system operations with path security |
| [mcp-mongo-tools](https://github.com/MassimilianoPili/mcp-mongo-tools) | 5 | `MCP_MONGO_ENABLED=true` | MongoDB operations (multi-instance) |
| [mcp-devops-tools](https://github.com/MassimilianoPili/mcp-devops-tools) | 47 | `MCP_DEVOPS_PAT` | Azure DevOps (WIQL, work items, Git, pipelines) |
| [mcp-azure-all](https://github.com/MassimilianoPili/mcp-azure-tools) | ~64 | `MCP_AZURE_CLIENT_ID` | Azure Cloud (VM, AKS, VNet, SQL, Key Vault, ...) |
| [mcp-ocp-tools](https://github.com/MassimilianoPili/mcp-ocp-tools) | 49 | `MCP_OCP_TOKEN` | OpenShift 4 (pods, deployments, routes, builds, ...) |
| [mcp-docker-tools](https://github.com/MassimilianoPili/mcp-docker-tools) | 41 | `MCP_DOCKER_HOST` | Docker Engine (containers, images, networks, volumes) |
| [mcp-jira-tools](https://github.com/MassimilianoPili/mcp-jira-tools) | 24 | `MCP_JIRA_API_TOKEN` | Jira Cloud (issues, boards, sprints, JQL) |
| [mcp-vector-tools](https://github.com/MassimilianoPili/mcp-vector-tools) | 5 | `MCP_VECTOR_ENABLED=true` | Semantic vector search (pgvector, Ollama/ONNX/OpenAI) |
| [mcp-graph-tools](https://github.com/MassimilianoPili/mcp-graph-tools) | 5 | `MCP_GRAPH_ENABLED=true` | Graph database operations (Neo4j + Apache AGE, Cypher) |
| [mcp-playwright-tools](https://github.com/MassimilianoPili/mcp-playwright-tools) | 15 | `MCP_PLAYWRIGHT_ENABLED=true` | Browser automation (navigate, click, screenshot, snapshot, evaluate JS) |
| Built-in (ApiProxy) | 3 | Always active | Generic REST API proxy (GET, POST, health) |

**Total: ~266 tools** (when all libraries are enabled)

## Tool Annotations

- **`@Tool`** (Spring AI) — synchronous: SQL, Filesystem, MongoDB, Vector Search, Graph, Playwright
- **`@ReactiveTool`** ([spring-ai-reactive-tools](https://github.com/MassimilianoPili/spring-ai-reactive-tools)) — async `Mono<T>`: DevOps, Azure, OCP, Docker, Jira, ApiProxy

## Configuration

All configuration is via environment variables. Set only the ones for the libraries you want to activate:

```properties
# SQL (conditional, default enabled)
MCP_SQL_ENABLED=true
MCP_DB_URL=jdbc:postgresql://localhost:5432/mydb
MCP_DB_USER=postgres
MCP_DB_PASSWORD=secret

# Filesystem (conditional, default enabled)
MCP_FILESYSTEM_ENABLED=true
MCP_FS_BASEDIR=/data/myproject

# API Proxy (always active)
MCP_API_BASEURL=http://localhost:8080

# MongoDB (conditional)
MCP_MONGO_ENABLED=true
MCP_MONGO_URI=mongodb://localhost:27017/mydb

# Azure DevOps (conditional)
MCP_DEVOPS_PAT=your-pat
MCP_DEVOPS_ORG=your-org
MCP_DEVOPS_PROJECT=your-project

# Azure Cloud (conditional)
MCP_AZURE_TENANT_ID=...
MCP_AZURE_CLIENT_ID=...
MCP_AZURE_CLIENT_SECRET=...
MCP_AZURE_SUBSCRIPTION_ID=...

# OpenShift (conditional)
MCP_OCP_TOKEN=sha256~...
MCP_OCP_SERVER=https://api.cluster:6443

# Docker (conditional)
MCP_DOCKER_HOST=unix:///var/run/docker.sock

# Jira Cloud (conditional)
MCP_JIRA_BASE_URL=https://myorg.atlassian.net
MCP_JIRA_EMAIL=user@example.com
MCP_JIRA_API_TOKEN=your-token

# Vector Search (conditional)
MCP_VECTOR_ENABLED=true
MCP_VECTOR_PROVIDER=ollama
MCP_VECTOR_DB_URL=jdbc:postgresql://localhost:5432/embeddings

# Graph Database (conditional)
MCP_GRAPH_ENABLED=true
MCP_GRAPH_NEO4J_URI=bolt://neo4j:7687
MCP_GRAPH_NEO4J_USER=neo4j
MCP_GRAPH_NEO4J_PASSWORD=secret

# Playwright Browser Automation (conditional)
MCP_PLAYWRIGHT_ENABLED=true
MCP_PLAYWRIGHT_BROWSER=chromium
MCP_PLAYWRIGHT_HEADLESS=true
MCP_PLAYWRIGHT_TIMEOUT=30000
MCP_PLAYWRIGHT_VIEWPORT_WIDTH=1280
MCP_PLAYWRIGHT_VIEWPORT_HEIGHT=720
```

## Requirements

- Java 21+
- Spring Boot 3.4.1
- Spring AI 1.0.0
- spring-ai-reactive-tools 0.3.0

## License

[MIT License](LICENSE)
