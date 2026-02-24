package io.github.massimilianopili.mcp.sql;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DatabaseTools {

    private final Map<String, JdbcTemplate> registry;

    public DatabaseTools(Map<String, JdbcTemplate> jdbcTemplateRegistry) {
        this.registry = jdbcTemplateRegistry;
    }

    @Tool(name = "db_query", description = "Esegue una query SELECT read-only sul database e restituisce i risultati come lista di righe. Solo query SELECT sono permesse.")
    public List<Map<String, Object>> executeQuery(
            @ToolParam(description = "Query SQL SELECT da eseguire") String query,
            @ToolParam(description = "Nome del database (da db_list_databases). Se omesso usa il primo disponibile.", required = false) String database) {
        String trimmed = query.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT")) {
            throw new IllegalArgumentException("Solo query SELECT permesse");
        }
        return getJdbc(database).queryForList(query);
    }

    @Tool(name = "db_tables", description = "Elenca tutte le tabelle del database con le loro colonne, tipi di dato e posizione ordinale. Adatta automaticamente la query al tipo di DB (H2, Oracle, PostgreSQL, MySQL).")
    public List<Map<String, Object>> listTables(
            @ToolParam(description = "Nome del database (da db_list_databases). Se omesso usa il primo disponibile.", required = false) String database,
            @ToolParam(description = "Nome dello schema/owner (opzionale). Per Oracle: es. PAGAMENTI_DEV. Per H2: es. PUBLIC", required = false) String schema) {
        JdbcTemplate jdbc = getJdbc(database);
        String dbType = detectDbTypeFromJdbc(jdbc);

        switch (dbType) {
            case "oracle":
                return listTablesOracle(jdbc, schema);
            case "mysql":
                return listTablesMysql(jdbc, schema);
            default:
                // H2, PostgreSQL, e altri standard ANSI
                return listTablesStandard(jdbc, schema);
        }
    }

    @Tool(name = "db_count", description = "Conta le righe di una tabella, opzionalmente con filtro WHERE")
    public Map<String, Object> countRows(
            @ToolParam(description = "Nome della tabella") String tableName,
            @ToolParam(description = "Condizione WHERE opzionale, es: prezzo > 100", required = false) String whereClause,
            @ToolParam(description = "Nome del database (da db_list_databases). Se omesso usa il primo disponibile.", required = false) String database) {
        if (!tableName.matches("[a-zA-Z_][a-zA-Z0-9_.]*")) {
            throw new IllegalArgumentException("Nome tabella non valido: " + tableName);
        }
        String sql = "SELECT COUNT(*) as total FROM " + tableName;
        if (whereClause != null && !whereClause.isBlank()) {
            sql += " WHERE " + whereClause;
        }
        return getJdbc(database).queryForMap(sql);
    }

    @Tool(name = "db_list_databases", description = "Elenca i database SQL configurati nel server MCP. Ogni nome puo' essere usato come parametro 'database' negli altri tool DB.")
    public List<String> listDatabases() {
        return new ArrayList<>(registry.keySet());
    }

    @Tool(name = "db_list_schemas", description = "Elenca gli schema/owner accessibili nel database specificato. Per Oracle restituisce gli owner delle tabelle, per H2/PostgreSQL gli schema dell'information_schema.")
    public List<Map<String, Object>> listSchemas(
            @ToolParam(description = "Nome del database (da db_list_databases). Se omesso usa il primo disponibile.", required = false) String database) {
        JdbcTemplate jdbc = getJdbc(database);
        String dbType = detectDbTypeFromJdbc(jdbc);

        switch (dbType) {
            case "oracle":
                return jdbc.queryForList(
                        "SELECT DISTINCT owner as schema_name FROM all_tables ORDER BY owner");
            case "mysql":
                return jdbc.queryForList(
                        "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name");
            default:
                return jdbc.queryForList(
                        "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name");
        }
    }

    // --- Metodi privati ---

    private JdbcTemplate getJdbc(String database) {
        if (database == null || database.isBlank()) {
            // Usa il primo database disponibile
            return registry.values().iterator().next();
        }
        JdbcTemplate jdbc = registry.get(database);
        if (jdbc == null) {
            throw new IllegalArgumentException(
                    "Database '" + database + "' non trovato. Disponibili: " + registry.keySet());
        }
        return jdbc;
    }

    private String detectDbTypeFromJdbc(JdbcTemplate jdbc) {
        try {
            String url = jdbc.getDataSource().getConnection().getMetaData().getURL();
            return MultiDataSourceConfig.detectDbType(url);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private List<Map<String, Object>> listTablesStandard(JdbcTemplate jdbc, String schema) {
        String effectiveSchema = (schema != null && !schema.isBlank()) ? schema : "PUBLIC";
        return jdbc.queryForList(
                "SELECT table_name, column_name, data_type, ordinal_position " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? " +
                "ORDER BY table_name, ordinal_position",
                effectiveSchema);
    }

    private List<Map<String, Object>> listTablesOracle(JdbcTemplate jdbc, String schema) {
        if (schema != null && !schema.isBlank()) {
            return jdbc.queryForList(
                    "SELECT table_name, column_name, data_type, column_id as ordinal_position " +
                    "FROM all_tab_columns " +
                    "WHERE owner = ? " +
                    "ORDER BY table_name, column_id",
                    schema.toUpperCase());
        } else {
            // Senza schema, mostra le tabelle dell'utente corrente
            return jdbc.queryForList(
                    "SELECT table_name, column_name, data_type, column_id as ordinal_position " +
                    "FROM user_tab_columns " +
                    "ORDER BY table_name, column_id");
        }
    }

    private List<Map<String, Object>> listTablesMysql(JdbcTemplate jdbc, String schema) {
        if (schema != null && !schema.isBlank()) {
            return jdbc.queryForList(
                    "SELECT table_name, column_name, data_type, ordinal_position " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = ? " +
                    "ORDER BY table_name, ordinal_position",
                    schema);
        } else {
            return jdbc.queryForList(
                    "SELECT table_name, column_name, data_type, ordinal_position " +
                    "FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() " +
                    "ORDER BY table_name, ordinal_position");
        }
    }
}
