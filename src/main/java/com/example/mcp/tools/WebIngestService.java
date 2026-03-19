package com.example.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.massimilianopili.mcp.graph.CypherExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class WebIngestService {

    private static final Logger log = LoggerFactory.getLogger(WebIngestService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_FOR_EMBEDDING = 1000;

    private final Map<String, CypherExecutor> graphExecutors;
    private final VectorStore vectorStore;

    public WebIngestService(
            @Autowired(required = false) @Qualifier("graphExecutors") Map<String, CypherExecutor> graphExecutors,
            @Autowired(required = false) @Qualifier("vectorVectorStore") VectorStore vectorStore) {
        this.graphExecutors = graphExecutors != null ? graphExecutors : Map.of();
        this.vectorStore = vectorStore;
    }

    public String ingest(String url, String title, String contentType, String body,
                         List<String> authors, Integer year, String venue,
                         List<String> concepts, Map<String, Object> extraMeta) {
        try {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("status", "ok");
            result.put("url", url);
            result.put("title", title);

            String slug = toSlug(title);
            String label = resolveLabel(contentType);
            String now = Instant.now().toString();

            ArrayNode nodesCreated = result.putArray("nodes_created");

            // Phase 1 & 2: AGE graph write
            CypherExecutor age = graphExecutors.get("age");
            if (age != null) {
                writeGraphNodes(age, slug, title, label, url, body, authors, year, venue, concepts, now, nodesCreated);
            } else {
                result.put("graph_warning", "AGE backend non disponibile, skip graph write");
            }

            // Phase 3: Embedding
            if (vectorStore != null) {
                String miniDoc = buildMiniDoc(title, label, authors, year, body, url, concepts);
                String embeddingId = writeEmbedding(miniDoc, slug, label, url);
                result.put("embedding_id", embeddingId);
            } else {
                result.put("embedding_warning", "VectorStore non disponibile, skip embedding");
            }

            log.info("Web ingest completed: {} [{}] -> {} nodes", title, label, nodesCreated.size());
            return result.toString();
        } catch (Exception e) {
            log.error("Web ingest failed for '{}': {}", title, e.getMessage(), e);
            return "{\"status\": \"error\", \"message\": \"" + escJson(e.getMessage()) + "\"}";
        }
    }

    public String ingestFromExtract(String extractedJson) {
        try {
            JsonNode root = MAPPER.readTree(extractedJson);
            String extractedFrom = root.has("extracted_from") ? root.get("extracted_from").asText() : "unknown";

            return switch (extractedFrom) {
                case "semantic_scholar" -> ingestSemanticScholar(root);
                case "arxiv" -> ingestArxiv(root);
                case "openalex" -> ingestOpenAlex(root);
                case "semantic_scholar_search", "openalex_search" ->
                    "{\"status\": \"error\", \"message\": \"Usare ingest su singoli paper, non su risultati di ricerca\"}";
                default -> "{\"status\": \"error\", \"message\": \"extracted_from non riconosciuto: " + extractedFrom + "\"}";
            };
        } catch (Exception e) {
            return "{\"status\": \"error\", \"message\": \"JSON non valido: " + escJson(e.getMessage()) + "\"}";
        }
    }

    // --- Extract-specific ingest ---

    private String ingestSemanticScholar(JsonNode root) {
        String title = textOr(root, "title", "Untitled");
        List<String> authors = textArray(root, "authors");
        Integer year = root.has("year") ? root.get("year").asInt() : null;
        String venue = textOr(root, "venue", null);
        String body = textOr(root, "abstract", textOr(root, "tldr", ""));
        String url = root.has("paperId")
                ? "https://api.semanticscholar.org/graph/v1/paper/" + root.get("paperId").asText()
                : "";

        Map<String, Object> extra = new HashMap<>();
        if (root.has("paperId")) extra.put("paperId", root.get("paperId").asText());
        if (root.has("doi")) extra.put("doi", root.get("doi").asText());
        if (root.has("citationCount")) extra.put("citation_count", root.get("citationCount").asInt());

        return ingest(url, title, "paper", body, authors, year, venue, List.of(), extra);
    }

    private String ingestArxiv(JsonNode root) {
        String title = textOr(root, "title", "Untitled");
        List<String> authors = textArray(root, "authors");
        String yearStr = textOr(root, "published", "");
        Integer year = yearStr.length() >= 4 ? Integer.parseInt(yearStr.substring(0, 4)) : null;
        String body = textOr(root, "abstract", "");
        List<String> categories = textArray(root, "categories");
        String arxivId = textOr(root, "arxivId", "");
        String url = arxivId.isEmpty() ? "" : "https://arxiv.org/abs/" + arxivId;

        return ingest(url, title, "paper", body, authors, year, null, categories, Map.of("arxiv_id", arxivId));
    }

    private String ingestOpenAlex(JsonNode root) {
        String title = textOr(root, "title", "Untitled");
        Integer year = root.has("year") ? root.get("year").asInt() : null;
        String body = textOr(root, "abstract", "");

        List<String> authors = new ArrayList<>();
        if (root.has("authors") && root.get("authors").isArray()) {
            for (JsonNode a : root.get("authors")) {
                if (a.isTextual()) authors.add(a.asText());
                else if (a.has("name")) authors.add(a.get("name").asText());
            }
        }

        String venue = null;
        if (root.has("venue") && root.get("venue").has("name")) {
            venue = root.get("venue").get("name").asText();
        }

        List<String> concepts = new ArrayList<>();
        if (root.has("concepts") && root.get("concepts").isArray()) {
            for (JsonNode c : root.get("concepts")) {
                if (c.has("name")) concepts.add(c.get("name").asText());
            }
        }

        String doi = textOr(root, "doi", "");
        String url = doi.isEmpty() ? textOr(root, "openAlexId", "") : doi;

        Map<String, Object> extra = new HashMap<>();
        if (root.has("openAlexId")) extra.put("openalex_id", root.get("openAlexId").asText());
        if (root.has("citedByCount")) extra.put("citation_count", root.get("citedByCount").asInt());

        return ingest(url, title, "paper", body, authors, year, venue, concepts, extra);
    }

    // --- AGE Graph Write ---

    private void writeGraphNodes(CypherExecutor age, String slug, String title, String label,
                                 String url, String body, List<String> authors, Integer year,
                                 String venue, List<String> concepts, String now,
                                 ArrayNode nodesCreated) {
        StringBuilder cypher = new StringBuilder();
        cypher.append("MERGE (n:").append(label).append(" {archival_id: '").append(escCypher(slug)).append("'}) ");
        cypher.append("SET n.title = '").append(escCypher(title)).append("'");
        cypher.append(", n.source = '").append(escCypher(url)).append("'");
        cypher.append(", n.domain = 'personal'");
        cypher.append(", n.last_modified = '").append(now).append("'");
        if (body != null && !body.isEmpty()) {
            String desc = body.length() > 500 ? body.substring(0, 500) : body;
            cypher.append(", n.description = '").append(escCypher(desc)).append("'");
        }
        if (year != null) cypher.append(", n.year = ").append(year);
        cypher.append(" RETURN {id: id(n), label: '").append(label).append("', title: n.title}");

        executeCypher(age, cypher.toString());
        nodesCreated.add(label + ":" + slug);

        if (authors != null) {
            for (String author : authors) {
                if (author == null || author.isBlank()) continue;
                String authorCypher = "MERGE (a:Author {name: '" + escCypher(author) + "'}) "
                        + "SET a.domain = 'personal', a.last_modified = '" + now + "' "
                        + "RETURN {id: id(a)}";
                executeCypher(age, authorCypher);

                String relCypher = "MATCH (n:" + label + " {archival_id: '" + escCypher(slug) + "'}) "
                        + "MATCH (a:Author {name: '" + escCypher(author) + "'}) "
                        + "MERGE (n)-[:WRITTEN_BY]->(a) "
                        + "RETURN {rel: 'WRITTEN_BY'}";
                executeCypher(age, relCypher);
                nodesCreated.add("Author:" + author);
            }
        }

        if (venue != null && !venue.isBlank()) {
            String venueCypher = "MERGE (v:Venue {name: '" + escCypher(venue) + "'}) "
                    + "SET v.domain = 'personal', v.last_modified = '" + now + "' "
                    + "RETURN {id: id(v)}";
            executeCypher(age, venueCypher);

            String relCypher = "MATCH (n:" + label + " {archival_id: '" + escCypher(slug) + "'}) "
                    + "MATCH (v:Venue {name: '" + escCypher(venue) + "'}) "
                    + "MERGE (n)-[:PUBLISHED_IN]->(v) "
                    + "RETURN {rel: 'PUBLISHED_IN'}";
            executeCypher(age, relCypher);
            nodesCreated.add("Venue:" + venue);
        }

        if (concepts != null) {
            for (String concept : concepts) {
                if (concept == null || concept.isBlank()) continue;
                String conceptCypher = "MERGE (c:Concept {name: '" + escCypher(concept) + "'}) "
                        + "SET c.domain = 'personal', c.last_modified = '" + now + "' "
                        + "RETURN {id: id(c)}";
                executeCypher(age, conceptCypher);

                String relCypher = "MATCH (n:" + label + " {archival_id: '" + escCypher(slug) + "'}) "
                        + "MATCH (c:Concept {name: '" + escCypher(concept) + "'}) "
                        + "MERGE (n)-[:HAS_CONCEPT]->(c) "
                        + "RETURN {rel: 'HAS_CONCEPT'}";
                executeCypher(age, relCypher);
                nodesCreated.add("Concept:" + concept);
            }
        }
    }

    private void executeCypher(CypherExecutor age, String cypher) {
        try {
            age.execute(cypher, Map.of());
        } catch (Exception e) {
            log.warn("Cypher execution warning: {} | query: {}", e.getMessage(),
                    cypher.substring(0, Math.min(100, cypher.length())));
        }
    }

    // --- Embedding ---

    private String buildMiniDoc(String title, String label, List<String> authors,
                                Integer year, String body, String url, List<String> concepts) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(": ").append(title).append("\n");
        if (authors != null && !authors.isEmpty()) {
            sb.append("Authors: ").append(String.join(", ", authors));
            if (year != null) sb.append(". Year: ").append(year);
            sb.append(".\n");
        } else if (year != null) {
            sb.append("Year: ").append(year).append(".\n");
        }
        if (body != null && !body.isEmpty()) {
            sb.append(body, 0, Math.min(body.length(), MAX_BODY_FOR_EMBEDDING)).append("\n");
        }
        sb.append("Source: ").append(url).append("\n");
        if (concepts != null && !concepts.isEmpty()) {
            sb.append("Concepts: ").append(String.join(", ", concepts)).append("\n");
        }
        return sb.toString();
    }

    private String writeEmbedding(String miniDoc, String slug, String label, String url) {
        String id = UUID.randomUUID().toString();
        Document doc = new Document(id, miniDoc, Map.of(
                "source_file", url,
                "type", "docs",
                "label", label,
                "name", slug,
                "domain", "personal",
                "source", "web_ingest"
        ));
        vectorStore.add(List.of(doc));
        return id;
    }

    // --- Helpers ---

    private String resolveLabel(String contentType) {
        if (contentType == null) return "WebContent";
        return switch (contentType.toLowerCase().trim()) {
            case "paper" -> "Paper";
            case "blog" -> "BlogPost";
            case "docs", "documentation" -> "Documentation";
            default -> "WebContent";
        };
    }

    static String toSlug(String title) {
        if (title == null || title.isBlank()) return "untitled-" + System.currentTimeMillis();
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    static String escCypher(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")
                .replace("\r", "");
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return fallback;
    }

    private static List<String> textArray(JsonNode node, String field) {
        List<String> list = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray()) {
            for (JsonNode item : node.get(field)) {
                if (item.isTextual()) list.add(item.asText());
                else if (item.has("name")) list.add(item.get("name").asText());
            }
        }
        return list;
    }
}
