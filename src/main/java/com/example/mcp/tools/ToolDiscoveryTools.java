package com.example.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ToolDiscoveryTools {

    private final ApplicationContext context;
    private final ObjectMapper objectMapper;
    private volatile List<ToolMetadata> toolIndex;

    record ToolMetadata(String name, String description, String category) {}

    private static final Map<String, String> PREFIX_TO_CATEGORY = new LinkedHashMap<>();
    static {
        PREFIX_TO_CATEGORY.put("docker_", "docker");
        PREFIX_TO_CATEGORY.put("ocp_", "ocp");
        PREFIX_TO_CATEGORY.put("devops_", "devops");
        PREFIX_TO_CATEGORY.put("jira_", "jira");
        PREFIX_TO_CATEGORY.put("gitea_", "gitea");
        PREFIX_TO_CATEGORY.put("keycloak_", "keycloak");
        PREFIX_TO_CATEGORY.put("redis_", "redis");
        PREFIX_TO_CATEGORY.put("s3_", "s3");
        PREFIX_TO_CATEGORY.put("ssh_", "ssh");
        PREFIX_TO_CATEGORY.put("db_", "sql");
        PREFIX_TO_CATEGORY.put("graph_", "graph");
        PREFIX_TO_CATEGORY.put("embeddings_", "embeddings");
        PREFIX_TO_CATEGORY.put("web_", "web");
        PREFIX_TO_CATEGORY.put("http_", "http");
        PREFIX_TO_CATEGORY.put("playwright_", "playwright");
        PREFIX_TO_CATEGORY.put("code_", "code");
        PREFIX_TO_CATEGORY.put("csv_", "csv");
        PREFIX_TO_CATEGORY.put("json_", "json");
        PREFIX_TO_CATEGORY.put("markdown_", "markdown");
        PREFIX_TO_CATEGORY.put("pdf_", "pdf");
        PREFIX_TO_CATEGORY.put("yaml_", "yaml");
        PREFIX_TO_CATEGORY.put("llm_", "ollama");
        PREFIX_TO_CATEGORY.put("ai_", "ai");
        PREFIX_TO_CATEGORY.put("meta_", "meta");
        PREFIX_TO_CATEGORY.put("claude_", "claude");
        PREFIX_TO_CATEGORY.put("anki_", "anki");
        PREFIX_TO_CATEGORY.put("openalex_", "openalex");
        PREFIX_TO_CATEGORY.put("fs_", "filesystem");
        PREFIX_TO_CATEGORY.put("recovery_", "recovery");
        PREFIX_TO_CATEGORY.put("infra_", "infra");
        PREFIX_TO_CATEGORY.put("auth_", "auth");
        PREFIX_TO_CATEGORY.put("ops_", "ops");
        PREFIX_TO_CATEGORY.put("net_", "net");
        PREFIX_TO_CATEGORY.put("metrics_", "monitoring");
        PREFIX_TO_CATEGORY.put("backup_", "backup");
        PREFIX_TO_CATEGORY.put("systemd_", "systemd");
        PREFIX_TO_CATEGORY.put("cf_", "cloudflare");
        PREFIX_TO_CATEGORY.put("research_", "research");
        PREFIX_TO_CATEGORY.put("tool_", "discovery");
        PREFIX_TO_CATEGORY.put("resolve_", "context7");
        PREFIX_TO_CATEGORY.put("query_", "context7");
        PREFIX_TO_CATEGORY.put("api_", "api");
        PREFIX_TO_CATEGORY.put("html_", "web");
    }

    // Cache for full tool schemas (built lazily per tool)
    private final Map<String, String> schemaCache = new ConcurrentHashMap<>();

    public ToolDiscoveryTools(ApplicationContext context, ObjectMapper objectMapper) {
        this.context = context;
        this.objectMapper = objectMapper;
    }

    private List<ToolMetadata> getIndex() {
        if (toolIndex == null) {
            synchronized (this) {
                if (toolIndex == null) {
                    toolIndex = buildIndex();
                }
            }
        }
        return toolIndex;
    }

    private List<ToolMetadata> buildIndex() {
        List<ToolMetadata> index = new ArrayList<>();
        Map<String, ToolCallbackProvider> providers = context.getBeansOfType(ToolCallbackProvider.class);
        for (ToolCallbackProvider provider : providers.values()) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                ToolDefinition def = callback.getToolDefinition();
                String name = def.name();
                String desc = def.description();
                String category = inferCategory(name);
                index.add(new ToolMetadata(name, desc, category));
                schemaCache.put(name, def.inputSchema());
            }
        }
        index.sort(Comparator.comparing(ToolMetadata::name));
        return Collections.unmodifiableList(index);
    }

    @ReactiveTool(
            name = "tool_search",
            description = "Search available MCP tools by query or category. Returns matching tool names and descriptions. "
                    + "Use this to discover tools before calling them. "
                    + "Categories: infra, docker, ocp, code, sql, web, devops, gitea, jira, ai, ollama, "
                    + "embeddings, keycloak, graph, s3, redis, ssh, csv, json, markdown, pdf, http, "
                    + "playwright, anki, openalex, claude, meta, auth, ops, net, context7, api, discovery.",
            readOnly = true, idempotent = true
    )
    public Mono<String> toolSearch(
            @ToolParam(description = "Search query (matches tool name and description). Empty string returns all tools in category.") String query,
            @ToolParam(description = "Optional category filter (e.g. 'docker', 'infra', 'web'). If empty, searches all categories.") String category) {
        return Mono.fromCallable(() -> {
            List<ToolMetadata> index = getIndex();
            String q = query != null ? query.toLowerCase().trim() : "";
            String cat = category != null ? category.toLowerCase().trim() : "";

            List<ToolMetadata> results = index.stream()
                    .filter(t -> cat.isEmpty() || t.category().equals(cat))
                    .filter(t -> q.isEmpty()
                            || t.name().toLowerCase().contains(q)
                            || t.description().toLowerCase().contains(q))
                    .limit(30)
                    .toList();

            if (results.isEmpty()) {
                return "{\"count\": 0, \"tools\": [], \"hint\": \"Try broader query or different category\"}";
            }

            List<Map<String, String>> toolList = results.stream()
                    .map(t -> Map.of("name", t.name(), "description", truncate(t.description(), 120), "category", t.category()))
                    .toList();

            // Include category summary
            Map<String, Long> categoryCounts = index.stream()
                    .collect(Collectors.groupingBy(ToolMetadata::category, Collectors.counting()));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", results.size());
            response.put("total_tools", index.size());
            response.put("tools", toolList);
            if (cat.isEmpty() && q.isEmpty()) {
                response.put("categories", categoryCounts);
            }

            return objectMapper.writeValueAsString(response);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @ReactiveTool(
            name = "tool_info",
            description = "Get the full JSON schema of a specific tool including all parameters, types, required fields, and description. "
                    + "Use tool_search first to find the tool name.",
            readOnly = true, idempotent = true
    )
    public Mono<String> toolInfo(
            @ToolParam(description = "Exact tool name (e.g. 'web_search', 'docker_list_containers')") String toolName) {
        return Mono.fromCallable(() -> {
            String name = toolName != null ? toolName.trim() : "";
            String schema = schemaCache.get(name);

            if (schema == null) {
                // Force index build if not yet done
                getIndex();
                schema = schemaCache.get(name);
            }

            if (schema == null) {
                return "{\"error\": \"Tool not found: " + name + "\", \"hint\": \"Use tool_search to find available tools\"}";
            }

            // Find description from index
            String description = getIndex().stream()
                    .filter(t -> t.name().equals(name))
                    .map(ToolMetadata::description)
                    .findFirst()
                    .orElse("");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", name);
            response.put("description", description);
            try {
                response.put("inputSchema", objectMapper.readTree(schema));
            } catch (JsonProcessingException e) {
                response.put("inputSchema", schema);
            }

            return objectMapper.writeValueAsString(response);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static String inferCategory(String name) {
        for (Map.Entry<String, String> entry : PREFIX_TO_CATEGORY.entrySet()) {
            if (name.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "other";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
