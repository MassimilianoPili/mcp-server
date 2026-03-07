# MCP Server — Guida Setup Completa

Runbook per compilare, configurare e avviare `mcp-server` come server MCP STDIO per Claude Code. Seguire gli step in ordine.

---

## Step 1 — Verificare Java 21

Il server richiede Java 21+. Verificare:

```bash
java -version
```

Se il default e' Java 17 o precedente, trovare Java 21:

```bash
# Cercare installazioni disponibili
ls /opt/java*/bin/java 2>/dev/null
ls /usr/lib/jvm/java-21-*/bin/java 2>/dev/null
```

Annotare il path assoluto (es. `/opt/java21/bin/java`). Sara' necessario nello Step 5.

## Step 2 — Build delle librerie tool

Le 12 librerie devono essere nel Maven locale (`~/.m2/repository`). Se gia' presenti (es. da Maven Central o build precedente), saltare questo step.

**Ordine di build** (il framework reattivo deve essere compilato per primo):

```bash
# 1. Framework reattivo (dipendenza condivisa)
cd spring-ai-reactive-tools && mvn clean install -Dgpg.skip=true && cd ..

# 2. Librerie tool (ordine libero)
for lib in mcp-sql-tools mcp-filesystem-tools mcp-mongo-tools mcp-devops-tools \
           mcp-azure-tools mcp-ocp-tools mcp-docker-tools mcp-jira-tools \
           mcp-vector-tools mcp-graph-tools mcp-playwright-tools; do
  echo "=== Building $lib ==="
  cd "$lib" && mvn clean install -Dgpg.skip=true && cd ..
done
```

Tutti devono terminare con `BUILD SUCCESS`.

## Step 3 — Build del server

```bash
cd mcp-server
mvn clean package -DskipTests
```

Produce: `target/mcp-server-0.0.1-SNAPSHOT.jar` (~370 MB).

Verificare:

```bash
ls -lh target/mcp-server-0.0.1-SNAPSHOT.jar
```

Annotare il **path assoluto** del JAR. Sara' necessario nello Step 5.

## Step 4 — Installare i browser Playwright (opzionale)

Solo se si vuole usare i 15 tool di browser automation. Se non serve, saltare e impostare `MCP_PLAYWRIGHT_ENABLED=false` (o non impostarlo, il default e' `false`).

```bash
cd mcp-playwright-tools
mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install --with-deps chromium"
```

Scarica Chromium (~100 MB) in `~/.cache/ms-playwright/`. Se `--with-deps` richiede root:

```bash
sudo mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install-deps chromium"
mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

**Nota**: Se si salta questo step ma si attiva Playwright, il server parte comunque — i tool Playwright tornano un errore descrittivo con il comando di installazione. Gli altri tool funzionano normalmente.

## Step 5 — Configurare `~/.claude.json`

Aprire `~/.claude.json` e aggiungere (o modificare) la sezione `"mcpServers"`:

```json
{
  "mcpServers": {
    "simoge-mcp": {
      "type": "stdio",
      "command": "<JAVA_21_PATH>",
      "args": [
        "-Xmx512m",
        "-jar",
        "<JAR_PATH>"
      ],
      "env": {
      }
    }
  }
}
```

Sostituire:
- `<JAVA_21_PATH>` con il path di Java 21 trovato allo Step 1 (es. `/opt/java21/bin/java` o `/usr/bin/java`)
- `<JAR_PATH>` con il path assoluto del JAR dallo Step 3 (es. `/home/user/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar`)

### Compilare la sezione `env`

Aggiungere **solo** le variabili delle librerie che servono. Ogni libreria non configurata resta disattivata (nessun tool registrato, nessun overhead).

#### Filesystem (attivo per default)

```json
"MCP_FS_BASEDIR": "/home/user/projects"
```

Root directory per le operazioni file. I tool non possono uscire da questa directory. **Obbligatorio** — senza, usa un default che potrebbe non esistere.

#### SQL Database (attivo per default)

Per usare H2 embedded (no setup DB esterno):

```json
"MCP_SQL_ENABLED": "true"
```

Per PostgreSQL o altro DB esterno:

```json
"MCP_SQL_ENABLED": "true",
"MCP_DB_URL": "jdbc:postgresql://localhost:5432/mydb",
"MCP_DB_USER": "postgres",
"MCP_DB_PASSWORD": "<password>"
```

Per disabilitare: `"MCP_SQL_ENABLED": "false"`.

Multi-database: `"MCP_DB_NAMES": "db1,db2"` poi per ogni nome: `MCP_DB_db1_URL`, `MCP_DB_db1_DRIVER`, `MCP_DB_db1_USER`, `MCP_DB_db1_PASSWORD`.

#### MongoDB

```json
"MCP_MONGO_ENABLED": "true",
"MCP_MONGO_URI": "mongodb://user:pass@localhost:27017/mydb"
```

Multi-istanza: `"MCP_MONGO_NAMES": "m1,m2"` poi `MCP_MONGO_m1_URI`, etc.

#### Azure DevOps (47 tool)

```json
"MCP_DEVOPS_PAT": "<personal-access-token>",
"MCP_DEVOPS_ORG": "<organizzazione>",
"MCP_DEVOPS_PROJECT": "<progetto>",
"MCP_DEVOPS_TEAM": "<team>"
```

Si attiva quando `MCP_DEVOPS_PAT` e' impostato e non vuoto.

#### Azure Cloud (~64 tool)

```json
"MCP_AZURE_TENANT_ID": "<tenant-id>",
"MCP_AZURE_CLIENT_ID": "<client-id>",
"MCP_AZURE_CLIENT_SECRET": "<client-secret>",
"MCP_AZURE_SUBSCRIPTION_ID": "<subscription-id>"
```

Richiede un service principal Azure. Si attiva quando `MCP_AZURE_CLIENT_ID` e' impostato.

#### OpenShift 4 (49 tool)

```json
"MCP_OCP_TOKEN": "sha256~...",
"MCP_OCP_SERVER": "https://api.cluster.example.com:6443"
```

Si attiva quando `MCP_OCP_TOKEN` e' impostato.

#### Docker (41 tool)

```json
"MCP_DOCKER_HOST": "unix:///var/run/docker.sock"
```

Si attiva quando `MCP_DOCKER_HOST` e' impostato. Linux: `unix:///var/run/docker.sock`. Remoto: `tcp://host:2376`.

#### Jira Cloud (24 tool)

```json
"MCP_JIRA_BASE_URL": "https://myorg.atlassian.net",
"MCP_JIRA_EMAIL": "user@example.com",
"MCP_JIRA_API_TOKEN": "<api-token>"
```

Si attiva quando `MCP_JIRA_API_TOKEN` e' impostato.

#### Vector Search / pgvector (5 tool)

```json
"MCP_VECTOR_ENABLED": "true",
"MCP_VECTOR_PROVIDER": "onnx",
"MCP_VECTOR_DB_URL": "jdbc:postgresql://localhost:5432/embeddings",
"MCP_VECTOR_DB_USER": "postgres",
"MCP_VECTOR_DB_CREDENTIAL": "<password>"
```

Provider: `ollama` (richiede server Ollama), `onnx` (locale, zero dipendenze esterne), `openai` (richiede `MCP_VECTOR_OPENAI_API_KEY`).

#### Graph Database (5 tool)

```json
"MCP_GRAPH_ENABLED": "true",
"MCP_GRAPH_NEO4J_URI": "bolt://localhost:7687",
"MCP_GRAPH_NEO4J_USER": "neo4j",
"MCP_GRAPH_NEO4J_PASSWORD": "<password>"
```

Oppure Apache AGE (PostgreSQL): `"MCP_GRAPH_AGE_ENABLED": "true"` + `MCP_GRAPH_AGE_DB_URL`.

#### Playwright Browser Automation (15 tool)

```json
"MCP_PLAYWRIGHT_ENABLED": "true",
"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD": "1"
```

`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` e' importante: impedisce il download automatico dei browser (~100 MB) a ogni avvio del server.

Opzionali (i default sono ragionevoli):

| Variabile | Default | Note |
|-----------|---------|------|
| `MCP_PLAYWRIGHT_BROWSER` | `chromium` | `firefox`, `webkit` |
| `MCP_PLAYWRIGHT_HEADLESS` | `true` | `false` per debug visivo |
| `MCP_PLAYWRIGHT_TIMEOUT` | `30000` | Timeout operazioni (ms) |
| `MCP_PLAYWRIGHT_VIEWPORT_WIDTH` | `1280` | |
| `MCP_PLAYWRIGHT_VIEWPORT_HEIGHT` | `720` | |
| `MCP_PLAYWRIGHT_LOCALE` | `it-IT` | |

## Step 6 — Riavviare Claude Code

Claude Code legge `~/.claude.json` solo all'avvio. Dopo aver salvato la configurazione, **riavviare Claude Code** (chiudere e riaprire).

## Step 7 — Verificare

1. **Nessun errore**: il server `simoge-mcp` non deve mostrare badge rosso o stato "failed"
2. **Tool disponibili**: provare a invocare un tool, ad esempio:
   - `playwright_navigate` con URL `https://example.com`
   - `sql_query` con una query semplice
   - `fs_list_directory` sulla `MCP_FS_BASEDIR`

### Test manuale (senza Claude Code)

Per verificare che il server funzioni prima di registrarlo:

```bash
export MCP_FS_BASEDIR=/tmp
export MCP_SQL_ENABLED=false

{
  printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}\n'
  sleep 4
  printf '{"jsonrpc":"2.0","method":"notifications/initialized"}\n'
  sleep 1
  printf '{"jsonrpc":"2.0","id":2,"method":"tools/list"}\n'
  sleep 2
} | <JAVA_21_PATH> -Xmx384m -jar <JAR_PATH> 2>/dev/null
```

Deve stampare due risposte JSON-RPC:
- id=1: `initialize` con `serverInfo.name=mcp-server`
- id=2: `tools/list` con l'elenco tool

---

## Troubleshooting

| Sintomo | Causa | Soluzione |
|---------|-------|-----------|
| `UnsupportedClassVersionError: class file version 65.0` | Java < 21 | Usare Java 21+. Controllare il `command` in `~/.claude.json` |
| `Executable doesn't exist at ~/.cache/ms-playwright/...` | Chromium non installato | Eseguire Step 4 |
| Exit code 1, zero output | Crash startup Spring Boot | Cercare `logs/mcp-server.log` nella working directory del JAR |
| Server parte, 0 tool | Nessuna libreria attivata | Aggiungere env vars in `~/.claude.json` |
| Badge rosso in Claude Code | Processo Java crasha all'avvio | Eseguire il test manuale per vedere l'errore |
| Prima chiamata Playwright lenta (~10s) | Lazy init del browser al primo uso | Normale. Successive chiamate veloci |
| Playwright fallisce, resto funziona | Chromium non installato, graceful degradation | I 15 tool Playwright tornano errore descrittivo, gli altri ~250 funzionano |

## Note

- **Memoria**: `-Xmx512m` consigliato. Footprint effettivo ~300-400 MB con tutte le librerie attive
- **Logging**: tutto su file `logs/mcp-server.log`, mai su stdout (che e' riservato a JSON-RPC)
- **Virtual threads**: abilitati con Java 21 (`spring.threads.virtual.enabled=true`)
- **Lazy init Playwright**: il browser si avvia solo al primo tool call (~5-10s), non allo startup. Auto-recovery: se il browser viene installato dopo, funziona senza riavvio
- **Graceful degradation**: una libreria in errore (es. DB non raggiungibile, browser non installato) non impatta le altre
