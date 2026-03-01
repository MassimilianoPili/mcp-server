# MCP Server — Guida Setup per Claude Code

Istruzioni per configurare e avviare `mcp-server` come MCP server STDIO per Claude Code. Questa guida e' destinata a un assistente AI che deve impostare la configurazione.

## Prerequisiti

1. **Java 21+** installato e raggiungibile. Trovare il path:
   ```bash
   which java          # oppure
   /usr/lib/jvm/java-21-*/bin/java -version
   ```

2. **JAR del server** gia' compilato. Verificare:
   ```bash
   ls -la /path/to/mcp-server-0.0.1-SNAPSHOT.jar
   ```
   Se non esiste, compilare:
   ```bash
   cd <directory-progetto-mcp-server>
   mvn clean package -DskipTests
   ```

3. **Browser Playwright** (opzionale, solo se si vuole `MCP_PLAYWRIGHT_ENABLED=true`):
   ```bash
   cd <directory-mcp-playwright-tools>
   mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps chromium"
   ```

## Configurazione `~/.claude.json`

Il server si registra nella sezione `"mcpServers"` del file `~/.claude.json`. La struttura e':

```json
{
  "mcpServers": {
    "simoge-mcp": {
      "type": "stdio",
      "command": "<PATH_JAVA_21>",
      "args": [
        "-Xmx512m",
        "-jar",
        "<PATH_JAR>"
      ],
      "env": {
        // --- Variabili obbligatorie ---
        // (almeno MCP_FS_BASEDIR per avere i tool filesystem)

        // --- Variabili per attivare librerie opzionali ---
        // (impostare solo quelle che servono)
      }
    }
  }
}
```

### Campi da compilare

| Campo | Valore | Esempio |
|-------|--------|---------|
| `command` | Path assoluto a Java 21+ | `/opt/java21/bin/java`, `/usr/bin/java` |
| `args[2]` (path JAR) | Path assoluto al fat JAR | `/home/user/mcp/target/mcp-server-0.0.1-SNAPSHOT.jar` |

### Variabili d'ambiente (`env`)

Ogni libreria si attiva impostando le relative env vars. **Non impostare** le variabili di librerie che non servono — il server le ignora.

#### Filesystem (sempre attivo)

```json
"MCP_FS_BASEDIR": "/home/user/projects"
```

Root directory per le operazioni file. I tool filesystem non possono uscire da questa directory.

#### SQL Database (attivo per default)

```json
"MCP_SQL_ENABLED": "true",
"MCP_DB_URL": "jdbc:postgresql://localhost:5432/mydb",
"MCP_DB_USER": "postgres",
"MCP_DB_PASSWORD": "secret"
```

Per disabilitare: `"MCP_SQL_ENABLED": "false"`. Supporta H2 (default), PostgreSQL, Oracle.
Multi-database: `"MCP_DB_NAMES": "db1,db2"` + `MCP_DB_db1_URL`, `MCP_DB_db1_USER`, etc.

#### MongoDB

```json
"MCP_MONGO_ENABLED": "true",
"MCP_MONGO_URI": "mongodb://user:pass@localhost:27017/mydb"
```

Multi-istanza: `"MCP_MONGO_NAMES": "mongo1,mongo2"` + `MCP_MONGO_mongo1_URI`, etc.

#### Azure DevOps

```json
"MCP_DEVOPS_PAT": "<personal-access-token>",
"MCP_DEVOPS_ORG": "<organizzazione>",
"MCP_DEVOPS_PROJECT": "<progetto>",
"MCP_DEVOPS_TEAM": "<team>"
```

Si attiva quando `MCP_DEVOPS_PAT` non e' vuoto. Fornisce 47 tool (WIQL, work items, Git, pipelines, wiki).

#### Azure Cloud

```json
"MCP_AZURE_TENANT_ID": "<tenant-id>",
"MCP_AZURE_CLIENT_ID": "<client-id>",
"MCP_AZURE_CLIENT_SECRET": "<client-secret>",
"MCP_AZURE_SUBSCRIPTION_ID": "<subscription-id>"
```

Si attiva quando `MCP_AZURE_CLIENT_ID` non e' vuoto. Richiede un service principal. ~64 tool.

#### OpenShift

```json
"MCP_OCP_TOKEN": "sha256~...",
"MCP_OCP_SERVER": "https://api.cluster.example.com:6443"
```

Si attiva quando `MCP_OCP_TOKEN` non e' vuoto. 49 tool.

#### Docker

```json
"MCP_DOCKER_HOST": "unix:///var/run/docker.sock"
```

Si attiva quando `MCP_DOCKER_HOST` non e' vuoto. 41 tool. Su macOS: `unix:///var/run/docker.sock`. Su Linux remoto: `tcp://host:2376`.

#### Jira Cloud

```json
"MCP_JIRA_BASE_URL": "https://myorg.atlassian.net",
"MCP_JIRA_EMAIL": "user@example.com",
"MCP_JIRA_API_TOKEN": "<api-token>"
```

Si attiva quando `MCP_JIRA_API_TOKEN` non e' vuoto. 24 tool.

#### Vector Search (pgvector)

```json
"MCP_VECTOR_ENABLED": "true",
"MCP_VECTOR_PROVIDER": "ollama",
"MCP_VECTOR_DB_URL": "jdbc:postgresql://localhost:5432/embeddings",
"MCP_VECTOR_DB_USER": "postgres",
"MCP_VECTOR_DB_CREDENTIAL": "<password>"
```

Provider embedding: `ollama` (default, richiede Ollama in rete), `onnx` (locale, all-MiniLM-L6-v2), `openai` (richiede API key). 5 tool.

#### Graph Database

```json
"MCP_GRAPH_ENABLED": "true",
"MCP_GRAPH_NEO4J_URI": "bolt://localhost:7687",
"MCP_GRAPH_NEO4J_USER": "neo4j",
"MCP_GRAPH_NEO4J_PASSWORD": "<password>"
```

Supporta anche Apache AGE su PostgreSQL: `"MCP_GRAPH_AGE_ENABLED": "true"` + `MCP_GRAPH_AGE_DB_URL`. 5 tool.

#### Playwright Browser Automation

```json
"MCP_PLAYWRIGHT_ENABLED": "true",
"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD": "1"
```

15 tool (navigate, click, fill, screenshot, snapshot, evaluate JS, etc.).
`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` impedisce il download automatico dei browser a ogni avvio — i browser devono essere installati manualmente (vedi Prerequisiti).

Opzionali (raramente necessari, hanno default ragionevoli):
```json
"MCP_PLAYWRIGHT_BROWSER": "chromium",
"MCP_PLAYWRIGHT_HEADLESS": "true",
"MCP_PLAYWRIGHT_TIMEOUT": "30000",
"MCP_PLAYWRIGHT_VIEWPORT_WIDTH": "1280",
"MCP_PLAYWRIGHT_VIEWPORT_HEIGHT": "720",
"MCP_PLAYWRIGHT_LOCALE": "it-IT"
```

## Esempio completo minimale

Config con solo filesystem + SQL (H2 default) + Playwright:

```json
{
  "mcpServers": {
    "simoge-mcp": {
      "type": "stdio",
      "command": "/usr/bin/java",
      "args": [
        "-Xmx512m",
        "-jar",
        "/home/user/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar"
      ],
      "env": {
        "MCP_FS_BASEDIR": "/home/user/projects",
        "MCP_PLAYWRIGHT_ENABLED": "true",
        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD": "1"
      }
    }
  }
}
```

## Esempio completo esteso

Config con filesystem + SQL PostgreSQL + DevOps + MongoDB + Playwright:

```json
{
  "mcpServers": {
    "simoge-mcp": {
      "type": "stdio",
      "command": "/usr/bin/java",
      "args": [
        "-Xmx512m",
        "-jar",
        "/home/user/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar"
      ],
      "env": {
        "MCP_FS_BASEDIR": "/home/user/projects",
        "MCP_DB_URL": "jdbc:postgresql://localhost:5432/mydb",
        "MCP_DB_USER": "postgres",
        "MCP_DB_PASSWORD": "secret",
        "MCP_DEVOPS_PAT": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "MCP_DEVOPS_ORG": "my-org",
        "MCP_DEVOPS_PROJECT": "my-project",
        "MCP_DEVOPS_TEAM": "my-team",
        "MCP_MONGO_ENABLED": "true",
        "MCP_MONGO_URI": "mongodb://root:pass@localhost:27017",
        "MCP_PLAYWRIGHT_ENABLED": "true",
        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD": "1"
      }
    }
  }
}
```

## Verifica

Dopo aver salvato `~/.claude.json`, **riavviare Claude Code**. Poi verificare:

1. Il server `simoge-mcp` non mostra errori (nessun badge rosso)
2. I tool sono disponibili — provare a invocare `playwright_navigate` con un URL o `sql_query` con una query

### Test manuale (senza Claude Code)

```bash
{
  printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}\n'
  sleep 4
  printf '{"jsonrpc":"2.0","method":"notifications/initialized"}\n'
  sleep 1
  printf '{"jsonrpc":"2.0","id":2,"method":"tools/list"}\n'
  sleep 2
} | MCP_FS_BASEDIR=/tmp java -Xmx384m -jar target/mcp-server-0.0.1-SNAPSHOT.jar 2>/dev/null
```

Deve rispondere con JSON-RPC: `initialize` result + `tools/list` result.

## Troubleshooting

| Sintomo | Causa | Soluzione |
|---------|-------|-----------|
| `UnsupportedClassVersionError: class file version 65.0` | Java < 21 | Usare Java 21+. Verificare `java -version` |
| `Executable doesn't exist at ~/.cache/ms-playwright/...` | Browser non installato | `cd mcp-playwright-tools && mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps chromium"` |
| Exit code 1, zero output su stdout | Errore di startup | Controllare `logs/mcp-server.log` nella directory di lavoro del JAR |
| Server parte ma zero tool | Nessuna env var impostata | Verificare sezione `env` in `~/.claude.json` |
| Badge rosso `simoge-mcp` in Claude Code | Crash del processo | Provare il test manuale sopra per vedere l'errore |
| Prima chiamata Playwright lenta (~10s) | Lazy init del browser | Normale. Le chiamate successive sono veloci |
| Playwright fallisce ma il resto funziona | Browser non installato, graceful degradation | I 15 tool Playwright tornano errore, gli altri ~250 funzionano normalmente |

## Note tecniche

- **Trasporto**: STDIO (stdin/stdout JSON-RPC 2.0). Tutto il logging va su file (`logs/mcp-server.log`), mai su stdout
- **Memoria**: `-Xmx512m` consigliato. Con tutte le librerie attive, il footprint e' ~300-400 MB
- **Virtual threads**: Java 21 virtual threads abilitati (`spring.threads.virtual.enabled=true`), le chiamate tool sono concorrenti
- **Lazy init Playwright**: Il browser si avvia solo alla prima chiamata tool, non allo startup del server. Se il browser non e' installato, il server parte comunque e gli altri tool funzionano. Se il browser viene installato dopo, la prossima chiamata funziona (auto-recovery)
- **`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`**: Importante in produzione. Senza, Playwright tenta di scaricare ~100 MB di binari browser a ogni avvio del server
