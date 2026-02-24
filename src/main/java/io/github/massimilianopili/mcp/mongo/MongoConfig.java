package io.github.massimilianopili.mcp.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "mcp.mongo.enabled", havingValue = "true")
@Import({MongoAutoConfiguration.class, MongoDataAutoConfiguration.class})
public class MongoConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoConfig.class);

    @Value("${mcp.mongo.names:}")
    private String mongoNames;

    @Bean
    public Map<String, MongoTemplate> mongoTemplateRegistry(MongoTemplate defaultMongoTemplate) {
        Map<String, MongoTemplate> registry = new LinkedHashMap<>();

        if (mongoNames == null || mongoNames.isBlank()) {
            // Modalita' singola: usa auto-config
            registry.put("default", defaultMongoTemplate);
            log.info("Multi-Mongo: modalita' singola, istanza 'default' registrata");
            return registry;
        }

        // Modalita' multi: crea MongoTemplate per ogni nome
        for (String name : mongoNames.split(",")) {
            name = name.trim();
            if (name.isEmpty()) continue;

            String envKey = "MCP_MONGO_" + name.toUpperCase() + "_URI";
            String uri = System.getenv(envKey);

            if (uri == null || uri.isBlank()) {
                log.warn("Multi-Mongo: istanza '{}' ignorata - {} non impostato", name, envKey);
                continue;
            }

            try {
                MongoClient client = MongoClients.create(uri);
                String dbName = extractDbName(uri);
                MongoTemplate template = new MongoTemplate(
                        new SimpleMongoClientDatabaseFactory(client, dbName));
                registry.put(name, template);
                log.info("Multi-Mongo: istanza '{}' registrata (db: {})", name, dbName);
            } catch (Exception e) {
                log.error("Multi-Mongo: errore creazione istanza '{}': {}", name, e.getMessage());
            }
        }

        if (registry.isEmpty()) {
            registry.put("default", defaultMongoTemplate);
            log.warn("Multi-Mongo: nessuna istanza valida, fallback su 'default'");
        }

        return registry;
    }

    private String extractDbName(String uri) {
        try {
            String path = uri;
            int slashIdx = path.indexOf("//");
            if (slashIdx >= 0) {
                path = path.substring(slashIdx + 2);
            }
            int hostEnd = path.indexOf('/');
            if (hostEnd >= 0) {
                String dbPart = path.substring(hostEnd + 1);
                int qIdx = dbPart.indexOf('?');
                if (qIdx >= 0) dbPart = dbPart.substring(0, qIdx);
                if (!dbPart.isEmpty()) return dbPart;
            }
        } catch (Exception ignored) {}
        return "test";
    }
}
