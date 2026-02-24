package io.github.massimilianopili.mcp.mongo;

import org.bson.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "mcp.mongo.enabled", havingValue = "true")
public class MongoDbTools {

    private final Map<String, MongoTemplate> registry;

    public MongoDbTools(Map<String, MongoTemplate> mongoTemplateRegistry) {
        this.registry = mongoTemplateRegistry;
    }

    @Tool(name = "mongo_find",
          description = "Cerca documenti in una collezione MongoDB. Il filtro e la proiezione usano la sintassi JSON di MongoDB. Default: max 50 documenti.")
    public List<Map<String, Object>> find(
            @ToolParam(description = "Nome della collezione") String collection,
            @ToolParam(description = "Filtro JSON, es: {\"status\": \"active\"}", required = false) String filter,
            @ToolParam(description = "Proiezione JSON, es: {\"name\": 1, \"_id\": 0}", required = false) String projection,
            @ToolParam(description = "Numero massimo di documenti (default 50, max 200)", required = false) Integer limit,
            @ToolParam(description = "Nome dell'istanza MongoDB (da mongo_list_databases). Se omesso usa la prima disponibile.", required = false) String database) {
        try {
            MongoTemplate mongo = getMongo(database);
            Document filterDoc = (filter != null && !filter.isBlank())
                    ? Document.parse(filter) : new Document();
            Document projDoc = (projection != null && !projection.isBlank())
                    ? Document.parse(projection) : null;

            Query query;
            if (projDoc != null) {
                query = new BasicQuery(filterDoc, projDoc);
            } else {
                query = new BasicQuery(filterDoc);
            }

            int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, 200) : 50;
            query.limit(effectiveLimit);

            List<Document> results = mongo.find(query, Document.class, collection);
            return results.stream()
                    .map(doc -> new LinkedHashMap<String, Object>(doc))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of(Map.of("error", "Errore query MongoDB: " + e.getMessage()));
        }
    }

    @Tool(name = "mongo_count",
          description = "Conta i documenti in una collezione MongoDB, con filtro opzionale")
    public Map<String, Object> count(
            @ToolParam(description = "Nome della collezione") String collection,
            @ToolParam(description = "Filtro JSON, es: {\"status\": \"active\"}", required = false) String filter,
            @ToolParam(description = "Nome dell'istanza MongoDB (da mongo_list_databases). Se omesso usa la prima disponibile.", required = false) String database) {
        try {
            MongoTemplate mongo = getMongo(database);
            Query query;
            if (filter != null && !filter.isBlank()) {
                query = new BasicQuery(Document.parse(filter));
            } else {
                query = new Query();
            }
            long total = mongo.count(query, collection);
            return Map.of("collection", collection, "count", total);
        } catch (Exception e) {
            return Map.of("error", "Errore conteggio MongoDB: " + e.getMessage());
        }
    }

    @Tool(name = "mongo_list_collections",
          description = "Elenca tutte le collezioni nel database MongoDB configurato")
    public List<String> listCollections(
            @ToolParam(description = "Nome dell'istanza MongoDB (da mongo_list_databases). Se omesso usa la prima disponibile.", required = false) String database) {
        try {
            return getMongo(database).getCollectionNames().stream()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of("Errore: " + e.getMessage());
        }
    }

    @Tool(name = "mongo_aggregate",
          description = "Esegue una pipeline di aggregazione MongoDB su una collezione. La pipeline e' un JSON array di stage.")
    public List<Map<String, Object>> aggregate(
            @ToolParam(description = "Nome della collezione") String collection,
            @ToolParam(description = "Pipeline JSON array, es: [{\"$match\":{\"status\":\"active\"}},{\"$group\":{\"_id\":\"$category\",\"total\":{\"$sum\":1}}}]")
            String pipelineJson,
            @ToolParam(description = "Nome dell'istanza MongoDB (da mongo_list_databases). Se omesso usa la prima disponibile.", required = false) String database) {
        try {
            MongoTemplate mongo = getMongo(database);
            List<Document> pipeline = Document.parse("{\"p\":" + pipelineJson + "}")
                    .getList("p", Document.class);

            List<Document> results = mongo.getCollection(collection)
                    .aggregate(pipeline)
                    .into(new ArrayList<>());

            return results.stream()
                    .map(doc -> new LinkedHashMap<String, Object>(doc))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of(Map.of("error", "Errore aggregazione MongoDB: " + e.getMessage()));
        }
    }

    @Tool(name = "mongo_list_databases",
          description = "Elenca le istanze MongoDB configurate nel server MCP. Ogni nome puo' essere usato come parametro 'database' negli altri tool MongoDB.")
    public List<String> listDatabases() {
        return new ArrayList<>(registry.keySet());
    }

    // --- Metodi privati ---

    private MongoTemplate getMongo(String database) {
        if (database == null || database.isBlank()) {
            return registry.values().iterator().next();
        }
        MongoTemplate template = registry.get(database);
        if (template == null) {
            throw new IllegalArgumentException(
                    "Istanza MongoDB '" + database + "' non trovata. Disponibili: " + registry.keySet());
        }
        return template;
    }
}
