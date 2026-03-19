# MCP Server (simoge-mcp)

Server MCP monolitico Spring Boot che aggrega 15 librerie tool + tool inline. ~231 tool registrati.
Container: `simoge-mcp`, 1g memoria, porta 8099, transport SSE (`http://localhost:8099/sse`).

## Build & Deploy

```bash
cd /data/massimiliano/Vari/mcp
/opt/maven/bin/mvn clean compile
/opt/maven/bin/mvn clean install -Dgpg.skip=true

# Deploy container
sol deploy mcp
```

Java 17+. Maven: `/opt/maven/bin/mvn`.

## Librerie incluse

mcp-sql-tools, mcp-mongo-tools, mcp-devops-tools, mcp-azure-all, mcp-filesystem-tools,
mcp-ocp-tools, mcp-docker-tools, mcp-jira-tools, mcp-vector-tools, mcp-graph-tools,
mcp-bash-tool, mcp-python-tool, mcp-redis-tools, mcp-playwright-tools, spring-ai-reactive-tools.

Tool inline: Context7Tools, WebSearchTools, ClaudeMessagingTools.

Configurazione completa, librerie e tool list: vedi [README.md](README.md).
Ricerca semantica: `embeddings_search_docs("simoge-mcp server")`
