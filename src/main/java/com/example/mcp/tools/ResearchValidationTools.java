package com.example.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP tool for validating academic paper metadata against Semantic Scholar + DBLP + OpenAlex.
 * Compares claimed metadata (venue, year, citations, author) against verified sources
 * and returns structured corrections.
 *
 * Part of the academic-researcher v2/v3 enforcement pipeline:
 * - v2 (prompt): hard gates in agent definition
 * - v3 (this tool): deterministic validation via API lookup
 * - v3 (hook): validate-research-report.sh blocks incomplete reports
 */
@Service
public class ResearchValidationTools {

    private static final Logger log = LoggerFactory.getLogger(ResearchValidationTools.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);

    private static final String S2_SEARCH_URL =
            "https://api.semanticscholar.org/graph/v1/paper/search?query=%s&fields=title,authors,year,venue,citationCount,influentialCitationCount,externalIds&limit=5";
    private static final String S2_PAPER_URL =
            "https://api.semanticscholar.org/graph/v1/paper/%s?fields=title,authors,year,venue,citationCount,influentialCitationCount,externalIds";
    private static final String DBLP_SEARCH_URL =
            "https://dblp.org/search/publ/api?q=%s&format=json&h=3";
    private static final String OPENALEX_SEARCH_URL =
            "https://api.openalex.org/works?search=%s&per_page=3";

    private final WebClient httpClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(512 * 1024))
            .defaultHeader("User-Agent", "academic-researcher-agent/2.0 (research validation)")
            .defaultHeader("Accept", "application/json")
            .build();

    @ReactiveTool(name = "research_validate_paper",
            description = "Validates an academic paper against Semantic Scholar + DBLP + OpenAlex. " +
                    "Compares claimed metadata (venue, year, citations, first author) with verified ones. " +
                    "Returns structured validation with corrections. " +
                    "Use for every paper to validate in a Design Validation Report (Template F).")
    public Mono<String> validatePaper(
            @ToolParam(description = "Paper title (fuzzy search)") String title,
            @ToolParam(description = "Venue claimed in the design (e.g. 'NeurIPS 2023')", required = false)
            String claimedVenue,
            @ToolParam(description = "Claimed year", required = false) Integer claimedYear,
            @ToolParam(description = "Claimed citations (e.g. 1000)", required = false)
            Integer claimedCitations,
            @ToolParam(description = "Claimed first author last name (e.g. 'Zhou')", required = false)
            String claimedFirstAuthor
    ) {
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String fetchDate = LocalDate.now().toString();

        return fetchSemanticScholar(encodedTitle, title)
                .flatMap(s2Result -> fetchDblp(encodedTitle)
                        .map(dblpResult -> buildValidation(s2Result, dblpResult, null,
                                title, claimedVenue, claimedYear, claimedCitations, claimedFirstAuthor, fetchDate))
                        .defaultIfEmpty(buildValidation(s2Result, null, null,
                                title, claimedVenue, claimedYear, claimedCitations, claimedFirstAuthor, fetchDate))
                        // OpenAlex cross-reference for high-citation papers
                        .flatMap(result -> {
                            int verified = extractVerifiedCitations(result);
                            if (verified > 500) {
                                return fetchOpenAlex(encodedTitle)
                                        .map(oaResult -> enrichWithOpenAlex(result, oaResult))
                                        .defaultIfEmpty(result);
                            }
                            return Mono.just(result);
                        })
                )
                .onErrorResume(e -> {
                    log.warn("research_validate_paper failed for '{}': {}", title, e.getMessage());
                    return Mono.just(buildErrorResult(title, e.getMessage(), fetchDate));
                });
    }

    // ─── Semantic Scholar fetch ──────────────────────────────────────────────────

    private Mono<JsonNode> fetchSemanticScholar(String encodedTitle, String rawTitle) {
        String url = String.format(S2_SEARCH_URL, encodedTitle);
        return httpClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(FETCH_TIMEOUT)
                .map(body -> {
                    try {
                        JsonNode root = MAPPER.readTree(body);
                        JsonNode data = root.path("data");
                        if (data.isArray() && !data.isEmpty()) {
                            // Find best match by title similarity
                            return findBestMatch(data, rawTitle);
                        }
                        return MAPPER.createObjectNode().put("error", "no results");
                    } catch (Exception e) {
                        return MAPPER.createObjectNode().put("error", e.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    log.warn("S2 search failed: {}", e.getMessage());
                    return Mono.just(MAPPER.createObjectNode().put("error", "S2 fetch failed: " + e.getMessage()));
                });
    }

    private JsonNode findBestMatch(JsonNode data, String rawTitle) {
        String normalizedQuery = rawTitle.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
        JsonNode bestMatch = data.get(0);
        double bestScore = 0;
        for (JsonNode paper : data) {
            String paperTitle = paper.path("title").asText("").toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
            double score = jaroWinkler(normalizedQuery, paperTitle);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = paper;
            }
        }
        return bestMatch;
    }

    // ─── DBLP fetch ─────────────────────────────────────────────────────────────

    private Mono<JsonNode> fetchDblp(String encodedTitle) {
        String url = String.format(DBLP_SEARCH_URL, encodedTitle);
        return httpClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(FETCH_TIMEOUT)
                .map(body -> {
                    try {
                        JsonNode root = MAPPER.readTree(body);
                        JsonNode hits = root.path("result").path("hits").path("hit");
                        if (hits.isArray() && !hits.isEmpty()) {
                            return hits.get(0).path("info");
                        }
                        return null;
                    } catch (Exception e) {
                        log.warn("DBLP parse failed: {}", e.getMessage());
                        return null;
                    }
                })
                .onErrorResume(e -> {
                    log.warn("DBLP fetch failed: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    // ─── OpenAlex fetch ─────────────────────────────────────────────────────────

    private Mono<JsonNode> fetchOpenAlex(String encodedTitle) {
        String url = String.format(OPENALEX_SEARCH_URL, encodedTitle);
        return httpClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(FETCH_TIMEOUT)
                .map(body -> {
                    try {
                        JsonNode root = MAPPER.readTree(body);
                        JsonNode results = root.path("results");
                        if (results.isArray() && !results.isEmpty()) {
                            return results.get(0);
                        }
                        return null;
                    } catch (Exception e) {
                        return null;
                    }
                })
                .onErrorResume(e -> Mono.empty());
    }

    // ─── Build validation result ────────────────────────────────────────────────

    private String buildValidation(JsonNode s2, JsonNode dblp, JsonNode openAlex,
                                   String queryTitle, String claimedVenue, Integer claimedYear,
                                   Integer claimedCitations, String claimedFirstAuthor, String fetchDate) {
        try {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("status", s2.has("error") ? "S2_SEARCH_FAILED" : "VALIDATED");
            result.put("query_title", queryTitle);
            result.put("fetch_date", fetchDate);

            // Paper info from S2
            ObjectNode paper = MAPPER.createObjectNode();
            paper.put("title", s2.path("title").asText(""));
            paper.put("s2_paper_id", s2.path("paperId").asText(""));

            // Authors
            ArrayNode authors = MAPPER.createArrayNode();
            JsonNode s2Authors = s2.path("authors");
            String verifiedFirstAuthor = "";
            if (s2Authors.isArray()) {
                for (JsonNode a : s2Authors) {
                    String name = a.path("name").asText("");
                    authors.add(name);
                    if (verifiedFirstAuthor.isEmpty()) verifiedFirstAuthor = name;
                }
            }
            paper.set("authors", authors);
            paper.put("first_author", verifiedFirstAuthor);
            result.set("paper", paper);

            // External IDs
            ObjectNode externalIds = MAPPER.createObjectNode();
            JsonNode ids = s2.path("externalIds");
            if (ids.isObject()) {
                if (ids.has("ArXiv")) externalIds.put("arxiv", "arXiv:" + ids.get("ArXiv").asText());
                if (ids.has("DOI")) externalIds.put("doi", ids.get("DOI").asText());
                if (ids.has("DBLP")) externalIds.put("dblp", ids.get("DBLP").asText());
            }
            result.set("external_ids", externalIds);

            // Validation section
            ObjectNode validation = MAPPER.createObjectNode();

            // Venue validation
            ObjectNode venueVal = MAPPER.createObjectNode();
            String s2Venue = s2.path("venue").asText("");
            String dblpVenue = dblp != null ? extractDblpVenue(dblp) : "";
            String verifiedVenue = !dblpVenue.isEmpty() ? dblpVenue : s2Venue;

            venueVal.put("claimed", claimedVenue != null ? claimedVenue : "not specified");
            venueVal.put("verified_s2", s2Venue);
            if (!dblpVenue.isEmpty()) venueVal.put("verified_dblp", dblpVenue);
            venueVal.put("source", !dblpVenue.isEmpty() ? "DBLP" : "S2");
            venueVal.put("correction", claimedVenue != null && !claimedVenue.isEmpty()
                    && !venueMatches(claimedVenue, verifiedVenue));
            validation.set("venue", venueVal);

            // Year validation
            ObjectNode yearVal = MAPPER.createObjectNode();
            int verifiedYear = s2.path("year").asInt(0);
            int dblpYear = dblp != null ? parseDblpYear(dblp) : 0;
            if (dblpYear > 0) verifiedYear = dblpYear;

            yearVal.put("claimed", claimedYear != null ? claimedYear : 0);
            yearVal.put("verified", verifiedYear);
            yearVal.put("correction", claimedYear != null && claimedYear != verifiedYear && verifiedYear > 0);
            validation.set("year", yearVal);

            // Citation validation
            ObjectNode citVal = MAPPER.createObjectNode();
            int verifiedCit = s2.path("citationCount").asInt(0);
            int influentialCit = s2.path("influentialCitationCount").asInt(0);

            citVal.put("claimed", claimedCitations != null ? claimedCitations : 0);
            citVal.put("verified", verifiedCit);
            citVal.put("influential", influentialCit);
            citVal.put("source", "S2");
            citVal.put("fetch_date", fetchDate);
            if (claimedCitations != null && claimedCitations > 0 && verifiedCit > 0) {
                double delta = Math.abs((double)(verifiedCit - claimedCitations) / claimedCitations) * 100;
                citVal.put("delta_percent", Math.round(delta));
                citVal.put("correction", delta > 30);
            } else {
                citVal.put("correction", false);
            }
            validation.set("citations", citVal);

            // First author validation
            ObjectNode authorVal = MAPPER.createObjectNode();
            authorVal.put("claimed", claimedFirstAuthor != null ? claimedFirstAuthor : "not specified");
            authorVal.put("verified", verifiedFirstAuthor);
            authorVal.put("correction", claimedFirstAuthor != null && !claimedFirstAuthor.isEmpty()
                    && !verifiedFirstAuthor.isEmpty()
                    && !authorMatches(claimedFirstAuthor, verifiedFirstAuthor));
            validation.set("first_author", authorVal);

            result.set("validation", validation);

            // Multi-version tracking
            ObjectNode multiVersion = MAPPER.createObjectNode();
            if (ids.isObject() && ids.has("ArXiv")) {
                multiVersion.put("arxiv", "arXiv:" + ids.get("ArXiv").asText());
            }
            String dblpType = dblp != null ? dblp.path("type").asText("") : "";
            if ("Journal Articles".equals(dblpType)) {
                multiVersion.put("journal", verifiedVenue);
                multiVersion.put("strongest", "journal");
            } else if ("Conference and Workshop Papers".equals(dblpType)) {
                multiVersion.put("conference", verifiedVenue);
                multiVersion.put("strongest", "conference");
            } else if (!verifiedVenue.isEmpty()) {
                multiVersion.put("venue", verifiedVenue);
                multiVersion.put("strongest", "unknown");
            }
            result.set("multi_version", multiVersion);

            // Corrections summary
            ArrayNode corrections = MAPPER.createArrayNode();
            if (venueVal.path("correction").asBoolean()) {
                corrections.add("Venue: " + claimedVenue + " → " + verifiedVenue);
            }
            if (yearVal.path("correction").asBoolean()) {
                corrections.add("Year: " + claimedYear + " → " + verifiedYear);
            }
            if (citVal.path("correction").asBoolean()) {
                corrections.add("Citations: ~" + claimedCitations + " → ~" + verifiedCit + " (S2, delta " + citVal.path("delta_percent").asInt() + "%)");
            }
            if (authorVal.path("correction").asBoolean()) {
                corrections.add("First author: " + claimedFirstAuthor + " → " + verifiedFirstAuthor);
            }
            result.set("corrections_summary", corrections);
            result.put("has_corrections", corrections.size() > 0);

            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return buildErrorResult(queryTitle, e.getMessage(), fetchDate);
        }
    }

    // ─── OpenAlex enrichment ────────────────────────────────────────────────────

    private String enrichWithOpenAlex(String validationJson, JsonNode openAlex) {
        try {
            JsonNode root = MAPPER.readTree(validationJson);
            ObjectNode result = (ObjectNode) root;

            ObjectNode oaNode = MAPPER.createObjectNode();
            oaNode.put("cited_by_count", openAlex.path("cited_by_count").asInt(0));
            oaNode.put("type", openAlex.path("type").asText(""));
            JsonNode primaryLocation = openAlex.path("primary_location").path("source");
            if (primaryLocation.isObject()) {
                oaNode.put("source_name", primaryLocation.path("display_name").asText(""));
                oaNode.put("source_type", primaryLocation.path("type").asText(""));
            }
            result.set("openalex_crossref", oaNode);

            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return validationJson;
        }
    }

    private int extractVerifiedCitations(String validationJson) {
        try {
            JsonNode root = MAPPER.readTree(validationJson);
            return root.path("validation").path("citations").path("verified").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ─── DBLP helpers ───────────────────────────────────────────────────────────

    private String extractDblpVenue(JsonNode dblpInfo) {
        // DBLP uses "venue" field for conference/journal name
        JsonNode venue = dblpInfo.path("venue");
        if (venue.isTextual()) return venue.asText();
        if (venue.isArray() && !venue.isEmpty()) return venue.get(0).asText("");
        // Fallback: try "key" field (e.g., "journals/tmlr/WangXJ24")
        String key = dblpInfo.path("key").asText("");
        if (key.startsWith("journals/")) {
            return key.split("/")[1].toUpperCase();
        } else if (key.startsWith("conf/")) {
            return key.split("/")[1].toUpperCase();
        }
        return "";
    }

    private int parseDblpYear(JsonNode dblpInfo) {
        String year = dblpInfo.path("year").asText("");
        try {
            return Integer.parseInt(year);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ─── Matching helpers ───────────────────────────────────────────────────────

    private boolean venueMatches(String claimed, String verified) {
        if (claimed == null || verified == null) return true;
        String c = claimed.toLowerCase().replaceAll("[^a-z0-9]", "");
        String v = verified.toLowerCase().replaceAll("[^a-z0-9]", "");
        // Exact match after normalization
        if (c.equals(v)) return true;
        // Partial match (claimed contained in verified or vice versa)
        if (c.contains(v) || v.contains(c)) return true;
        // Extract year from claimed and check
        String claimedNoYear = c.replaceAll("\\d{4}", "").trim();
        String verifiedNoYear = v.replaceAll("\\d{4}", "").trim();
        return claimedNoYear.equals(verifiedNoYear);
    }

    private boolean authorMatches(String claimed, String verified) {
        if (claimed == null || verified == null) return true;
        String c = claimed.toLowerCase().trim();
        String v = verified.toLowerCase().trim();
        // Last name match
        String verifiedLastName = v.contains(" ") ? v.substring(v.lastIndexOf(' ') + 1) : v;
        return v.contains(c) || c.contains(verifiedLastName) || verifiedLastName.equals(c);
    }

    // ─── Jaro-Winkler for fuzzy title match ─────────────────────────────────────

    private double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        int len1 = s1.length(), len2 = s2.length();
        int maxDist = Math.max(len1, len2) / 2 - 1;
        if (maxDist < 0) maxDist = 0;

        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];
        int matches = 0, transpositions = 0;

        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - maxDist);
            int end = Math.min(i + maxDist + 1, len2);
            for (int j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0;

        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        double jaro = ((double) matches / len1 + (double) matches / len2
                + (double) (matches - transpositions / 2) / matches) / 3;

        // Winkler prefix bonus
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    // ─── Error result ───────────────────────────────────────────────────────────

    private String buildErrorResult(String title, String error, String fetchDate) {
        try {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("status", "ERROR");
            result.put("query_title", title);
            result.put("fetch_date", fetchDate);
            result.put("error", error);
            result.put("note", "S2 FETCH FAILED — DO NOT report citation counts from memory");
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return "{\"status\":\"ERROR\",\"error\":\"" + error + "\"}";
        }
    }
}
