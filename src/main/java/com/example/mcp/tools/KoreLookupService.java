package com.example.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private final VectorStore vectorStore;

    public KoreLookupService(
            @Autowired(required = false) @Qualifier("vectorVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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

    private static String stringMeta(Map<String, Object> meta, String key, String fallback) {
        Object val = meta.get(key);
        return val != null ? val.toString() : fallback;
    }
}
