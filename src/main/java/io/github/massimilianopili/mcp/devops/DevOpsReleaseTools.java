package io.github.massimilianopili.mcp.devops;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "mcp.devops.pat")
public class DevOpsReleaseTools {

    private final WebClient webClient;
    private final DevOpsProperties props;

    public DevOpsReleaseTools(
            @Qualifier("devOpsWebClient") WebClient webClient,
            DevOpsProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    @ReactiveTool(name = "devops_analyze_release",
          description = "Analizza una lista di work item (task/user story/bug) e restituisce i repository (microservizi) da rilasciare, " +
                        "basandosi sui branch, commit e pull request agganciati a ciascun work item. " +
                        "Fornire gli ID dei work item separati da virgola OPPURE una query WIQL.",
          timeoutMs = 60000)
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> analyzeRelease(
            @ToolParam(description = "ID dei work item separati da virgola, es: 123,456,789")
            String workItemIds,
            @ToolParam(description = "Alternativa: query WIQL per selezionare i work item (se fornita, workItemIds viene ignorato)",
                       required = false)
            String wiqlQuery) {
        return resolveWorkItemIds(workItemIds, wiqlQuery)
                .flatMap(ids -> {
                    if (ids.isEmpty()) {
                        return Mono.just(Map.<String, Object>of("error", "Nessun work item trovato"));
                    }

                    Mono<List<Map<String, Object>>> workItemsMono = fetchWorkItemsWithRelations(ids);
                    Mono<Map<String, String>> repoMapMono = fetchRepoMap();

                    return Mono.zip(workItemsMono, repoMapMono)
                            .map(tuple -> buildReleaseResult(ids, tuple.getT1(), tuple.getT2()));
                })
                .onErrorResume(e -> Mono.just(Map.of("error", "Errore analisi rilascio: " + e.getMessage())));
    }

    // --- Metodi privati ---

    @SuppressWarnings("unchecked")
    private Mono<List<Integer>> resolveWorkItemIds(String workItemIds, String wiqlQuery) {
        if (wiqlQuery != null && !wiqlQuery.isBlank()) {
            return webClient.post()
                    .uri(props.getBaseUrl() + "/_apis/wit/wiql?api-version=" + props.getApiVersion())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("query", wiqlQuery))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(result -> {
                        if (result.containsKey("workItems")) {
                            List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("workItems");
                            return items.stream()
                                    .map(item -> (Integer) item.get("id"))
                                    .limit(200)
                                    .collect(Collectors.toList());
                        }
                        return List.<Integer>of();
                    });
        }

        if (workItemIds == null || workItemIds.isBlank()) return Mono.just(List.of());
        return Mono.just(Arrays.stream(workItemIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .limit(200)
                .collect(Collectors.toList()));
    }

    @SuppressWarnings("unchecked")
    private Mono<List<Map<String, Object>>> fetchWorkItemsWithRelations(List<Integer> ids) {
        return Flux.fromIterable(ids)
                .flatMap(id -> webClient.get()
                        .uri(props.getBaseUrl() + "/_apis/wit/workitems/" + id
                                + "?$expand=Relations&api-version=" + props.getApiVersion())
                        .retrieve()
                        .bodyToMono(Map.class)
                        .map(r -> (Map<String, Object>) r)
                        .onErrorResume(e -> Mono.empty()),
                        5)  // concurrency limit
                .collectList();
    }

    @SuppressWarnings("unchecked")
    private Mono<Map<String, String>> fetchRepoMap() {
        return webClient.get()
                .uri(props.getBaseUrl() + "/_apis/git/repositories?api-version=" + props.getApiVersion())
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    Map<String, String> map = new HashMap<>();
                    if (response.containsKey("value")) {
                        List<Map<String, Object>> repos = (List<Map<String, Object>>) response.get("value");
                        for (Map<String, Object> repo : repos) {
                            String id = (String) repo.getOrDefault("id", "");
                            String name = (String) repo.getOrDefault("name", "");
                            if (!id.isEmpty()) map.put(id, name);
                        }
                    }
                    return map;
                })
                .onErrorResume(e -> Mono.just(new HashMap<>()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildReleaseResult(
            List<Integer> ids,
            List<Map<String, Object>> workItems,
            Map<String, String> repoIdToName) {

        Map<String, Map<String, Object>> repoMap = new LinkedHashMap<>();
        List<Integer> workItemsWithoutLinks = new ArrayList<>();

        for (Map<String, Object> wi : workItems) {
            int wiId = (int) wi.getOrDefault("id", 0);
            List<Map<String, Object>> relations = extractRelations(wi);
            boolean hasGitLink = false;

            for (Map<String, Object> rel : relations) {
                String url = (String) rel.getOrDefault("url", "");
                String relType = (String) rel.getOrDefault("rel", "");

                if (!"ArtifactLink".equals(relType)) continue;

                GitArtifact artifact = parseGitArtifactUrl(url);
                if (artifact == null) continue;

                hasGitLink = true;
                String repoId = artifact.repoId;
                String repoName = repoIdToName.getOrDefault(repoId, repoId);

                repoMap.computeIfAbsent(repoId, k -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("repoName", repoName);
                    entry.put("repoId", repoId);
                    entry.put("branches", new LinkedHashSet<String>());
                    entry.put("workItemIds", new LinkedHashSet<Integer>());
                    entry.put("artifactTypes", new LinkedHashSet<String>());
                    return entry;
                });

                Map<String, Object> entry = repoMap.get(repoId);
                ((Set<Integer>) entry.get("workItemIds")).add(wiId);
                ((Set<String>) entry.get("artifactTypes")).add(artifact.type);
                if (artifact.ref != null && !artifact.ref.isEmpty()) {
                    ((Set<String>) entry.get("branches")).add(artifact.ref);
                }
            }

            if (!hasGitLink) {
                workItemsWithoutLinks.add(wiId);
            }
        }

        List<Map<String, Object>> repositories = repoMap.values().stream().map(entry -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("repoName", entry.get("repoName"));
            result.put("repoId", entry.get("repoId"));
            result.put("branches", new ArrayList<>((Set<String>) entry.get("branches")));
            result.put("workItemIds", new ArrayList<>((Set<Integer>) entry.get("workItemIds")));
            result.put("artifactTypes", new ArrayList<>((Set<String>) entry.get("artifactTypes")));
            return result;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalWorkItems", ids.size());
        result.put("totalRepositories", repositories.size());
        result.put("repositories", repositories);
        if (!workItemsWithoutLinks.isEmpty()) {
            result.put("workItemsWithoutLinks", workItemsWithoutLinks);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractRelations(Map<String, Object> workItem) {
        Object relations = workItem.get("relations");
        if (relations instanceof List) {
            return (List<Map<String, Object>>) relations;
        }
        return List.of();
    }

    private GitArtifact parseGitArtifactUrl(String url) {
        if (url == null || !url.contains("vstfs:///Git/")) return null;

        try {
            String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
            String gitPart = decoded.substring(decoded.indexOf("vstfs:///Git/") + "vstfs:///Git/".length());
            String[] parts = gitPart.split("/", 4);

            if (parts.length >= 3) {
                GitArtifact artifact = new GitArtifact();
                artifact.type = parts[0];
                artifact.repoId = parts[2];
                artifact.ref = parts.length > 3 ? parts[3] : "";
                if (artifact.ref.startsWith("refs/heads/")) {
                    artifact.ref = artifact.ref.substring("refs/heads/".length());
                }
                return artifact;
            }
        } catch (Exception e) {
            // URL non parsabile
        }
        return null;
    }

    private static class GitArtifact {
        String type;
        String repoId;
        String ref;
    }
}
