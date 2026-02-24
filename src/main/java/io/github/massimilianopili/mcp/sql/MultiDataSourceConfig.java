package io.github.massimilianopili.mcp.sql;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class MultiDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiDataSourceConfig.class);

    @Value("${mcp.db.names:}")
    private String dbNames;

    @Bean
    public Map<String, JdbcTemplate> jdbcTemplateRegistry(JdbcTemplate defaultJdbcTemplate) {
        Map<String, JdbcTemplate> registry = new LinkedHashMap<>();

        if (dbNames == null || dbNames.isBlank()) {
            // Modalita' singola: usa il datasource auto-config di Spring Boot
            registry.put("default", defaultJdbcTemplate);
            log.info("Multi-DB: modalita' singola, datasource 'default' registrato");
            return registry;
        }

        // Modalita' multi: crea datasource per ogni nome
        for (String name : dbNames.split(",")) {
            name = name.trim();
            if (name.isEmpty()) continue;

            String envPrefix = "MCP_DB_" + name.toUpperCase() + "_";
            String url = System.getenv(envPrefix + "URL");
            String driver = System.getenv(envPrefix + "DRIVER");
            String user = System.getenv(envPrefix + "USER");
            String password = System.getenv(envPrefix + "PASSWORD");

            if (url == null || url.isBlank()) {
                log.warn("Multi-DB: datasource '{}' ignorato - {} non impostato", name, envPrefix + "URL");
                continue;
            }

            try {
                HikariDataSource ds = new HikariDataSource();
                ds.setJdbcUrl(url);
                if (driver != null && !driver.isBlank()) {
                    ds.setDriverClassName(driver);
                }
                if (user != null) ds.setUsername(user);
                if (password != null) ds.setPassword(password);
                ds.setMaximumPoolSize(3);
                ds.setMinimumIdle(1);
                ds.setConnectionTimeout(10000);
                ds.setPoolName("hikari-" + name);

                registry.put(name, new JdbcTemplate(ds));
                log.info("Multi-DB: datasource '{}' registrato ({})", name, detectDbType(url));
            } catch (Exception e) {
                log.error("Multi-DB: errore creazione datasource '{}': {}", name, e.getMessage());
            }
        }

        if (registry.isEmpty()) {
            // Fallback: se nessun datasource configurato, usa il default
            registry.put("default", defaultJdbcTemplate);
            log.warn("Multi-DB: nessun datasource valido, fallback su 'default'");
        }

        return registry;
    }

    /**
     * Rileva il tipo di database dal JDBC URL.
     */
    public static String detectDbType(String jdbcUrl) {
        if (jdbcUrl == null) return "unknown";
        String lower = jdbcUrl.toLowerCase();
        if (lower.contains(":oracle:")) return "oracle";
        if (lower.contains(":h2:")) return "h2";
        if (lower.contains(":postgresql:")) return "postgresql";
        if (lower.contains(":mysql:") || lower.contains(":mariadb:")) return "mysql";
        if (lower.contains(":sqlserver:")) return "sqlserver";
        return "unknown";
    }
}
