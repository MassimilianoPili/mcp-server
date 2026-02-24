# MCP Server

Monolithic Spring Boot MCP server with SQL, MongoDB, Azure DevOps, Filesystem and API Proxy tools.

## Build Commands

```bash
# Build
/opt/maven/bin/mvn clean package -DskipTests

# Run (STDIO mode)
/opt/java/bin/java -jar target/mcp-server-0.0.1-SNAPSHOT.jar
```

Java 17 required. Maven at `/opt/maven/bin/mvn`, Java at `/opt/java/bin/java`.

## Running as MCP Server

```bash
claude mcp add --transport stdio --scope user mcp-server -- /opt/java/bin/java -jar /data/massimiliano/Vari/mcp/target/mcp-server-0.0.1-SNAPSHOT.jar
```

## Project Structure

```
src/main/java/
├── com/example/mcp/
│   ├── McpServerApplication.java          # Entry point
│   └── tools/ApiProxyTools.java           # @ReactiveTool: api_get, api_post, api_health
└── io/github/massimilianopili/mcp/
    ├── sql/
    │   ├── MultiDataSourceConfig.java     # Multi-DB registry (Hikari)
    │   ├── DatabaseTools.java             # @Tool: db_query, db_tables, db_count, db_list_databases, db_list_schemas
    │   └── SqlToolsAutoConfiguration.java
    ├── mongo/
    │   ├── MongoConfig.java               # Multi-instance registry
    │   ├── MongoDbTools.java              # @Tool: mongo_find, mongo_count, mongo_list_collections, mongo_aggregate, mongo_list_databases
    │   └── MongoToolsAutoConfiguration.java
    ├── devops/
    │   ├── DevOpsProperties.java          # @ConfigurationProperties
    │   ├── DevOpsConfig.java              # WebClient bean
    │   ├── DevOpsWorkItemTools.java       # @ReactiveTool: WIQL, search, work item CRUD
    │   ├── DevOpsGitTools.java            # @ReactiveTool: repos, branches, PRs
    │   ├── DevOpsPipelineTools.java       # @ReactiveTool: pipeline runs, trigger
    │   ├── DevOpsBoardTools.java          # @ReactiveTool: sprints, board columns
    │   ├── DevOpsReleaseTools.java        # @ReactiveTool: release analysis
    │   └── DevOpsToolsAutoConfiguration.java
    └── filesystem/
        ├── FileInfo.java                  # File listing DTO
        ├── FileSystemTools.java           # @Tool: fs_list, fs_read, fs_search
        └── FileSystemToolsAutoConfiguration.java
```

## Tool Annotations

- **`@Tool`** (Spring AI): Synchronous — sql, filesystem, mongo tools
- **`@ReactiveTool`** (spring-ai-reactive-tools): Async `Mono<T>` — devops, ApiProxy tools
- **`@ToolParam`**: Parameter descriptions for MCP schema

## Configuration (Environment Variables)

### Database (sql)
- `MCP_DB_URL`, `MCP_DB_DRIVER`, `MCP_DB_USER`, `MCP_DB_PASSWORD`
- `MCP_DB_NAMES` — multi-DB (pattern: `MCP_DB_{NAME}_URL`, etc.)

### File System (filesystem)
- `MCP_FS_BASEDIR` — base directory (default: `C:/NoCloud`)

### API Proxy
- `MCP_API_BASEURL` — target API URL (default: `http://localhost:8080`)

### Azure DevOps (conditional)
- `MCP_DEVOPS_PAT` — Personal Access Token (enables all DevOps tools)
- `MCP_DEVOPS_ORG`, `MCP_DEVOPS_PROJECT`, `MCP_DEVOPS_TEAM`

### MongoDB (conditional)
- `MCP_MONGO_ENABLED=true` — enables MongoDB tools
- `MCP_MONGO_URI`, `MCP_MONGO_NAMES`

## Dependencies

- Spring Boot 3.4.1, Spring AI 1.0.0, spring-ai-reactive-tools 0.2.0
- H2, Oracle (ojdbc11), Spring Data MongoDB, Spring WebFlux
