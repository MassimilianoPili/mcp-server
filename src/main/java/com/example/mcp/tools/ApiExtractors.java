package com.example.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiExtractors {

    private static final Logger log = LoggerFactory.getLogger(ApiExtractors.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOP_N = 20;

    private ApiExtractors() {}

    // ─── Semantic Scholar ────────────────────────────────────────────────────────

    public static String extractSemanticScholar(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);

            // Handle both single paper and search results (data array)
            JsonNode paper = root;
            if (root.has("data") && root.get("data").isArray() && !root.get("data").isEmpty()) {
                // Search results: extract array and wrap
                return extractSemanticScholarSearchResults(root);
            }

            ObjectNode out = MAPPER.createObjectNode();
            out.put("extracted_from", "semantic_scholar");
            copyIfPresent(paper, out, "paperId");
            copyIfPresent(paper, out, "title");
            copyIfPresent(paper, out, "year");
            copyIfPresent(paper, out, "venue");
            copyIfPresent(paper, out, "citationCount");
            copyIfPresent(paper, out, "influentialCitationCount");
            copyIfPresent(paper, out, "referenceCount");
            copyIfPresent(paper, out, "abstract");

            // tldr
            if (paper.has("tldr") && paper.get("tldr").has("text")) {
                out.put("tldr", paper.get("tldr").get("text").asText());
            }

            // openAccessPdf
            if (paper.has("openAccessPdf") && paper.get("openAccessPdf").has("url")) {
                out.put("pdfUrl", paper.get("openAccessPdf").get("url").asText());
            }

            // Authors: extract names only
            if (paper.has("authors") && paper.get("authors").isArray()) {
                ArrayNode authors = out.putArray("authors");
                for (JsonNode a : paper.get("authors")) {
                    if (a.has("name")) authors.add(a.get("name").asText());
                }
            }

            // Citations: top N by citationCount
            extractTopCitations(paper, out, "citations", "citations_top" + TOP_N);
            int citationsReturned = paper.has("citations") && paper.get("citations").isArray()
                    ? paper.get("citations").size() : 0;
            out.put("citations_returned", citationsReturned);

            // References: top N by citationCount
            extractTopCitations(paper, out, "references", "references_top" + TOP_N);

            return out.toString();
        } catch (Exception e) {
            log.warn("Semantic Scholar extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private static String extractSemanticScholarSearchResults(JsonNode root) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("extracted_from", "semantic_scholar_search");
        if (root.has("total")) out.put("total", root.get("total").asInt());
        if (root.has("offset")) out.put("offset", root.get("offset").asInt());

        ArrayNode results = out.putArray("results");
        for (JsonNode paper : root.get("data")) {
            ObjectNode p = MAPPER.createObjectNode();
            copyIfPresent(paper, p, "paperId");
            copyIfPresent(paper, p, "title");
            copyIfPresent(paper, p, "year");
            copyIfPresent(paper, p, "venue");
            copyIfPresent(paper, p, "citationCount");
            if (paper.has("tldr") && paper.get("tldr").has("text")) {
                p.put("tldr", paper.get("tldr").get("text").asText());
            }
            if (paper.has("authors") && paper.get("authors").isArray()) {
                ArrayNode authors = p.putArray("authors");
                for (JsonNode a : paper.get("authors")) {
                    if (a.has("name")) authors.add(a.get("name").asText());
                }
            }
            // Citations count only for search results
            int citationsReturned = paper.has("citations") && paper.get("citations").isArray()
                    ? paper.get("citations").size() : 0;
            if (citationsReturned > 0) p.put("citations_returned", citationsReturned);
            results.add(p);
        }
        return out.toString();
    }

    private static void extractTopCitations(JsonNode paper, ObjectNode out, String field, String outputField) {
        if (!paper.has(field) || !paper.get(field).isArray()) return;

        List<JsonNode> items = new ArrayList<>();
        for (JsonNode c : paper.get(field)) items.add(c);

        // Sort by citationCount DESC, take top N
        items.sort(Comparator.comparingInt(
                (JsonNode n) -> n.has("citationCount") ? n.get("citationCount").asInt(0) : 0).reversed());

        ArrayNode arr = out.putArray(outputField);
        int limit = Math.min(items.size(), TOP_N);
        for (int i = 0; i < limit; i++) {
            JsonNode c = items.get(i);
            ObjectNode entry = MAPPER.createObjectNode();
            copyIfPresent(c, entry, "title");
            copyIfPresent(c, entry, "year");
            copyIfPresent(c, entry, "citationCount");
            arr.add(entry);
        }
    }

    // ─── arXiv ───────────────────────────────────────────────────────────────────

    public static String extractArxiv(String xml) {
        try {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("extracted_from", "arxiv");

            // Extract fields from Atom XML using regex (lightweight, no XML parser dependency)
            out.put("arxivId", extractXmlTag(xml, "id", "arxiv.org/abs/([^<\"v]+)"));
            out.put("title", cleanWhitespace(extractXmlTag(xml, "title", null)));
            out.put("abstract", cleanWhitespace(extractXmlTag(xml, "summary", null)));
            out.put("published", extractXmlTag(xml, "published", null));
            out.put("updated", extractXmlTag(xml, "updated", null));

            // Authors
            ArrayNode authors = out.putArray("authors");
            Pattern authorPattern = Pattern.compile("<author>\\s*<name>([^<]+)</name>", Pattern.DOTALL);
            Matcher m = authorPattern.matcher(xml);
            while (m.find()) authors.add(m.group(1).trim());

            // Categories
            ArrayNode categories = out.putArray("categories");
            Pattern catPattern = Pattern.compile("category[^>]*term=\"([^\"]+)\"");
            Matcher cm = catPattern.matcher(xml);
            while (cm.find()) categories.add(cm.group(1));

            // PDF URL
            Pattern pdfPattern = Pattern.compile("link[^>]*href=\"([^\"]+)\"[^>]*title=\"pdf\"");
            Matcher pm = pdfPattern.matcher(xml);
            if (pm.find()) out.put("pdfUrl", pm.group(1));

            return out.toString();
        } catch (Exception e) {
            log.warn("arXiv extraction failed: {}", e.getMessage());
            return null;
        }
    }

    // ─── OpenAlex ────────────────────────────────────────────────────────────────

    public static String extractOpenAlex(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);

            // Handle search results
            if (root.has("results") && root.get("results").isArray()) {
                return extractOpenAlexSearchResults(root);
            }

            // Single work
            ObjectNode out = MAPPER.createObjectNode();
            out.put("extracted_from", "openalex");
            copyIfPresent(root, out, "id", "openAlexId");
            copyIfPresent(root, out, "doi");
            copyIfPresent(root, out, "title");
            copyIfPresent(root, out, "publication_year", "year");
            copyIfPresent(root, out, "cited_by_count", "citedByCount");
            copyIfPresent(root, out, "type");

            // Authors with institutions
            if (root.has("authorships") && root.get("authorships").isArray()) {
                ArrayNode authors = out.putArray("authors");
                for (JsonNode authorship : root.get("authorships")) {
                    ObjectNode a = MAPPER.createObjectNode();
                    if (authorship.has("author")) {
                        JsonNode author = authorship.get("author");
                        if (author.has("display_name")) a.put("name", author.get("display_name").asText());
                        if (author.has("orcid") && !author.get("orcid").isNull()) {
                            a.put("orcid", author.get("orcid").asText());
                        }
                    }
                    if (authorship.has("institutions") && authorship.get("institutions").isArray()
                            && !authorship.get("institutions").isEmpty()) {
                        JsonNode inst = authorship.get("institutions").get(0);
                        if (inst.has("display_name")) a.put("institution", inst.get("display_name").asText());
                    }
                    if (a.has("name")) authors.add(a);
                }
            }

            // Concepts
            if (root.has("concepts") && root.get("concepts").isArray()) {
                ArrayNode concepts = out.putArray("concepts");
                for (JsonNode c : root.get("concepts")) {
                    if (c.has("score") && c.get("score").asDouble() < 0.3) continue;
                    ObjectNode concept = MAPPER.createObjectNode();
                    if (c.has("display_name")) concept.put("name", c.get("display_name").asText());
                    if (c.has("level")) concept.put("level", c.get("level").asInt());
                    if (c.has("score")) concept.put("score", Math.round(c.get("score").asDouble() * 100.0) / 100.0);
                    concepts.add(concept);
                }
            }

            // Venue / primary location
            if (root.has("primary_location") && !root.get("primary_location").isNull()) {
                JsonNode loc = root.get("primary_location");
                ObjectNode venue = MAPPER.createObjectNode();
                if (loc.has("source") && !loc.get("source").isNull()) {
                    JsonNode src = loc.get("source");
                    if (src.has("display_name")) venue.put("name", src.get("display_name").asText());
                    if (src.has("type")) venue.put("type", src.get("type").asText());
                    if (src.has("issn_l") && !src.get("issn_l").isNull()) {
                        venue.put("issn", src.get("issn_l").asText());
                    }
                }
                if (loc.has("pdf_url") && !loc.get("pdf_url").isNull()) {
                    out.put("pdfUrl", loc.get("pdf_url").asText());
                }
                if (venue.size() > 0) out.set("venue", venue);
            }

            // Abstract (reconstructed from inverted index)
            if (root.has("abstract_inverted_index") && !root.get("abstract_inverted_index").isNull()) {
                out.put("abstract", reconstructAbstract(root.get("abstract_inverted_index")));
            }

            // Referenced works count
            if (root.has("referenced_works") && root.get("referenced_works").isArray()) {
                out.put("referencedWorksCount", root.get("referenced_works").size());
            }

            // Citations by year
            if (root.has("counts_by_year") && root.get("counts_by_year").isArray()) {
                ArrayNode cby = out.putArray("citationsByYear");
                for (JsonNode yearData : root.get("counts_by_year")) {
                    ObjectNode entry = MAPPER.createObjectNode();
                    copyIfPresent(yearData, entry, "year");
                    if (yearData.has("cited_by_count")) {
                        entry.put("count", yearData.get("cited_by_count").asInt());
                    }
                    cby.add(entry);
                }
            }

            return out.toString();
        } catch (Exception e) {
            log.warn("OpenAlex extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private static String extractOpenAlexSearchResults(JsonNode root) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("extracted_from", "openalex_search");
        if (root.has("meta")) {
            JsonNode meta = root.get("meta");
            if (meta.has("count")) out.put("total", meta.get("count").asInt());
        }

        ArrayNode results = out.putArray("results");
        for (JsonNode work : root.get("results")) {
            ObjectNode w = MAPPER.createObjectNode();
            copyIfPresent(work, w, "id", "openAlexId");
            copyIfPresent(work, w, "title");
            copyIfPresent(work, w, "publication_year", "year");
            copyIfPresent(work, w, "cited_by_count", "citedByCount");
            copyIfPresent(work, w, "type");

            // Authors: names only for search results
            if (work.has("authorships") && work.get("authorships").isArray()) {
                ArrayNode authors = w.putArray("authors");
                for (JsonNode authorship : work.get("authorships")) {
                    if (authorship.has("author") && authorship.get("author").has("display_name")) {
                        authors.add(authorship.get("author").get("display_name").asText());
                    }
                }
            }

            // Venue name only
            if (work.has("primary_location") && !work.get("primary_location").isNull()) {
                JsonNode loc = work.get("primary_location");
                if (loc.has("source") && !loc.get("source").isNull()
                        && loc.get("source").has("display_name")) {
                    w.put("venue", loc.get("source").get("display_name").asText());
                }
            }

            results.add(w);
        }
        return out.toString();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        copyIfPresent(from, to, field, field);
    }

    private static void copyIfPresent(JsonNode from, ObjectNode to, String fromField, String toField) {
        if (from.has(fromField) && !from.get(fromField).isNull()) {
            JsonNode val = from.get(fromField);
            if (val.isTextual()) to.put(toField, val.asText());
            else if (val.isInt()) to.put(toField, val.asInt());
            else if (val.isLong()) to.put(toField, val.asLong());
            else if (val.isDouble()) to.put(toField, val.asDouble());
            else to.set(toField, val);
        }
    }

    private static String extractXmlTag(String xml, String tag, String regexOverride) {
        if (regexOverride != null) {
            Matcher m = Pattern.compile(regexOverride).matcher(xml);
            return m.find() ? m.group(1).trim() : "";
        }
        // Simple tag extraction (first occurrence, skip feed-level tags for entry-level)
        Pattern p = Pattern.compile("<" + tag + "[^>]*>([^<]*(?:<(?!/" + tag + ")[^<]*)*)</" + tag + ">",
                Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        // For title/summary, skip the first (feed-level) and take the second (entry-level) if present
        if (("title".equals(tag) || "summary".equals(tag)) && m.find() && m.find()) {
            return m.group(1).trim();
        }
        // Reset and take first
        m.reset();
        return m.find() ? m.group(1).trim() : "";
    }

    private static String cleanWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String reconstructAbstract(JsonNode invertedIndex) {
        // OpenAlex stores abstracts as inverted index: {"word": [pos1, pos2], ...}
        if (!invertedIndex.isObject()) return "";
        List<String> words = new ArrayList<>();
        int maxPos = 0;
        invertedIndex.fields().forEachRemaining(entry -> {});
        // First pass: find max position
        var it = invertedIndex.fields();
        while (it.hasNext()) {
            var entry = it.next();
            for (JsonNode pos : entry.getValue()) {
                maxPos = Math.max(maxPos, pos.asInt());
            }
        }
        // Initialize array
        String[] arr = new String[maxPos + 1];
        it = invertedIndex.fields();
        while (it.hasNext()) {
            var entry = it.next();
            for (JsonNode pos : entry.getValue()) {
                arr[pos.asInt()] = entry.getKey();
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String w : arr) {
            if (w != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(w);
            }
        }
        return sb.toString();
    }
}
