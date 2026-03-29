package com.example.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.massimilianopili.mcp.graph.CypherExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KoreLookupService {

    private static final Logger log = LoggerFactory.getLogger(KoreLookupService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double SIMILARITY_THRESHOLD = 0.85;
    private static final int GRAPH_NEIGHBOR_LIMIT = 10;

    private final VectorStore vectorStore;
    private final Map<String, CypherExecutor> graphExecutors;

    public KoreLookupService(
            @Autowired(required = false) @Qualifier("vectorVectorStore") VectorStore vectorStore,
            @Autowired(required = false) @Qualifier("graphExecutors") Map<String, CypherExecutor> graphExecutors) {
        this.vectorStore = vectorStore;
        this.graphExecutors = graphExecutors != null ? graphExecutors : Map.of();
    }

    public boolean isAvailable() {
        return vectorStore != null;
    }

    public String searchSemantic(String query, int limit) {
        if (vectorStore == null) return null;

        try {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(limit)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .build());

            if (results == null || results.isEmpty()) return null;

            ArrayNode arr = MAPPER.createArrayNode();
            for (Document doc : results) {
                ObjectNode item = MAPPER.createObjectNode();
                Map<String, Object> meta = doc.getMetadata();

                item.put("title", stringMeta(meta, "name", ""));
                item.put("source", stringMeta(meta, "source_file", ""));
                item.put("type", stringMeta(meta, "type", ""));
                item.put("label", stringMeta(meta, "label", ""));

                String content = doc.getText();
                if (content != null && content.length() > 300) {
                    content = content.substring(0, 300) + "...";
                }
                item.put("snippet", content != null ? content : "");

                arr.add(item);
            }

            return arr.toString();
        } catch (Exception e) {
            log.warn("KORE semantic search failed for '{}': {}", query, e.getMessage());
            return null;
        }
    }

    /**
     * Graph-augmented search: pgvector lookup + AGE graph traversal.
     * For each vector result with a name+label (web-ingested content),
     * traverses AGE edges to find related nodes (authors, topics, venues).
     */
    public String searchGraphAugmented(String query, int vectorLimit, int graphDepth) {
        if (vectorStore == null) return null;

        try {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(vectorLimit)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .build());

            if (results == null || results.isEmpty()) return null;

            CypherExecutor age = graphExecutors.get("age");

            ArrayNode arr = MAPPER.createArrayNode();
            for (Document doc : results) {
                ObjectNode item = MAPPER.createObjectNode();
                Map<String, Object> meta = doc.getMetadata();

                String name = stringMeta(meta, "name", "");
                String label = stringMeta(meta, "label", "");

                item.put("title", name);
                item.put("source", stringMeta(meta, "source_file", ""));
                item.put("type", stringMeta(meta, "type", ""));
                item.put("label", label);

                String content = doc.getText();
                if (content != null && content.length() > 300) {
                    content = content.substring(0, 300) + "...";
                }
                item.put("snippet", content != null ? content : "");

                // Graph expansion: traverse AGE edges for this node
                if (age != null && !name.isBlank() && !label.isBlank()) {
                    ArrayNode graphContext = expandViaGraph(age, name, graphDepth);
                    if (graphContext != null && !graphContext.isEmpty()) {
                        item.set("graph_context", graphContext);
                    }
                }

                arr.add(item);
            }

            return arr.toString();
        } catch (Exception e) {
            log.warn("KORE graph-augmented search failed for '{}': {}", query, e.getMessage());
            return null;
        }
    }

    private ArrayNode expandViaGraph(CypherExecutor age, String archivalId, int depth) {
        try {
            String escaped = escCypher(archivalId);

            // Depth-1: direct neighbors
            String cypher = "MATCH (n {archival_id: '" + escaped + "'})-[r]-(neighbor) " +
                    "RETURN {rel_type: type(r), title: coalesce(neighbor.title, neighbor.name), " +
                    "label: label(neighbor), domain: neighbor.domain} " +
                    "LIMIT " + GRAPH_NEIGHBOR_LIMIT;

            List<Map<String, Object>> rows = age.execute(cypher, Map.of());

            ArrayNode context = MAPPER.createArrayNode();
            for (Map<String, Object> row : rows) {
                ObjectNode neighbor = MAPPER.createObjectNode();
                Map<String, Object> data = flattenAgeResult(row);
                neighbor.put("rel", stringMeta(data, "rel_type", ""));
                neighbor.put("title", stringMeta(data, "title", ""));
                neighbor.put("label", stringMeta(data, "label", ""));
                context.add(neighbor);
            }

            // Depth-2: 2-hop neighbors (if requested)
            if (depth >= 2 && !context.isEmpty()) {
                String cypher2 = "MATCH (n {archival_id: '" + escaped + "'})-[r1]-(n2)-[r2]-(n3) " +
                        "WHERE n3 <> n " +
                        "RETURN {path: type(r1) + ' -> ' + type(r2), " +
                        "title: coalesce(n3.title, n3.name), label: label(n3)} " +
                        "LIMIT 5";

                List<Map<String, Object>> rows2 = age.execute(cypher2, Map.of());
                for (Map<String, Object> row : rows2) {
                    ObjectNode neighbor = MAPPER.createObjectNode();
                    Map<String, Object> data = flattenAgeResult(row);
                    neighbor.put("rel", stringMeta(data, "path", ""));
                    neighbor.put("title", stringMeta(data, "title", ""));
                    neighbor.put("label", stringMeta(data, "label", ""));
                    neighbor.put("depth", 2);
                    context.add(neighbor);
                }
            }

            return context;
        } catch (Exception e) {
            log.debug("Graph expansion failed for '{}': {}", archivalId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flattenAgeResult(Map<String, Object> row) {
        // AGE returns nested maps — flatten one level
        for (var entry : row.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> inner) {
                return (Map<String, Object>) inner;
            }
        }
        return row;
    }

    private static String stringMeta(Map<String, Object> meta, String key, String fallback) {
        Object val = meta.get(key);
        return val != null ? val.toString() : fallback;
    }

    static String escCypher(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")
                .replace("\r", "");
    }
}
