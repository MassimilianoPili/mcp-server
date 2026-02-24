package io.github.massimilianopili.mcp.devops;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
@ConditionalOnProperty(name = "mcp.devops.pat")
public class DevOpsWorkItemTools {

    private final WebClient webClient;
    private final DevOpsProperties props;

    public DevOpsWorkItemTools(
            @Qualifier("devOpsWebClient") WebClient webClient,
            DevOpsProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    @ReactiveTool(name = "devops_query_work_items",
          description = "Esegue una query WIQL su Azure DevOps e restituisce i work item trovati con i campi principali (ID, titolo, stato, tipo, assegnatario). Massimo 200 risultati.")
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> queryWorkItems(
            @ToolParam(description = "Query WIQL, es: SELECT [System.Id], [System.Title] FROM workitems WHERE [System.State] = 'Active'")
            String wiqlQuery) {
        return webClient.post()
                .uri(props.getBaseUrl() + "/_apis/wit/wiql?api-version=" + props.getApiVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", wiqlQuery))
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(wiqlResult -> {
                    if (!wiqlResult.containsKey("workItems")) {
                        return Mono.just(Map.<String, Object>of("count", 0, "workItems", List.of()));
                    }

                    List<Map<String, Object>> wiqlItems = (List<Map<String, Object>>) wiqlResult.get("workItems");
                    if (wiqlItems.isEmpty()) {
                        return Mono.just(Map.<String, Object>of("count", 0, "workItems", List.of()));
                    }

                    List<Integer> ids = wiqlItems.stream()
                            .map(item -> (Integer) item.get("id"))
                            .limit(200)
                            .toList();

                    return webClient.post()
                            .uri(props.getBaseUrl() + "/_apis/wit/workitemsbatch?api-version=" + props.getApiVersion())
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of(
                                    "ids", ids,
                                    "fields", List.of(
                                            "System.Id", "System.Title", "System.State",
                                            "System.WorkItemType", "System.AssignedTo",
                                            "System.IterationPath", "System.AreaPath",
                                            "System.CreatedDate", "System.ChangedDate"
                                    )
                            ))
                            .retrieve()
                            .bodyToMono(Map.class)
                            .map(batchResult -> {
                                if (!batchResult.containsKey("value")) {
                                    return Map.<String, Object>of("count", 0, "workItems", List.of());
                                }
                                List<Map<String, Object>> items = (List<Map<String, Object>>) batchResult.get("value");
                                return Map.<String, Object>of("count", items.size(), "workItems", items);
                            });
                })
                .onErrorResume(e -> Mono.just(Map.of("error", "Errore query work items: " + e.getMessage())));
    }

    @ReactiveTool(name = "devops_get_work_item",
          description = "Recupera un singolo work item di Azure DevOps per ID, con tutti i campi e opzionalmente le relazioni")
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getWorkItem(
            @ToolParam(description = "ID numerico del work item") int workItemId,
            @ToolParam(description = "Espandi: None, Relations, Fields, Links, All", required = false)
            String expand) {
        String uri = props.getBaseUrl() + "/_apis/wit/workitems/" + workItemId
                + "?api-version=" + props.getApiVersion();
        if (expand != null && !expand.isBlank()) {
            uri += "&$expand=" + expand;
        }
        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(Map.class)
                .map(r -> (Map<String, Object>) r)
                .onErrorResume(e -> Mono.just(Map.of("error", "Errore recupero work item " + workItemId + ": " + e.getMessage())));
    }

    @ReactiveTool(name = "devops_create_work_item",
          description = "Crea un nuovo work item in Azure DevOps (Bug, Task, User Story, Feature, Epic)")
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> createWorkItem(
            @ToolParam(description = "Tipo: Bug, Task, User Story, Feature, Epic") String workItemType,
            @ToolParam(description = "Titolo del work item") String title,
            @ToolParam(description = "Descrizione (HTML supportato)", required = false) String description,
            @ToolParam(description = "Stato iniziale, es: New, Active", required = false) String state,
            @ToolParam(description = "Assegnatario (email o display name)", required = false) String assignedTo,
            @ToolParam(description = "Iteration path, es: ProjectName\\Sprint 1", required = false) String iterationPath,
            @ToolParam(description = "Area path", required = false) String areaPath) {
        List<Map<String, String>> patchOps = buildPatchDocument(
                title, description, state, assignedTo, iterationPath, areaPath);

        return webClient.post()
                .uri(props.getBaseUrl() + "/_apis/wit/workitems/$" + workItemType
                        + "?api-version=" + props.getApiVersion())
                .contentType(MediaType.valueOf("application/json-patch+json"))
                .bodyValue(patchOps)
                .retrieve()
                .bodyToMono(Map.class)
                .map(r -> (Map<String, Object>) r)
                .onErrorResume(e -> Mono.just(Map.of("error", "Errore creazione work item: " + e.getMessage())));
    }

    @ReactiveTool(name = "devops_update_work_item",
          description = "Aggiorna un work item esistente in Azure DevOps. Specifica solo i campi da modificare.")
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> updateWorkItem(
            @ToolParam(description = "ID del work item da aggiornare") int workItemId,
            @ToolParam(description = "Nuovo titolo", required = false) String title,
            @ToolParam(description = "Nuova descrizione", required = false) String description,
            @ToolParam(description = "Nuovo stato, es: Active, Resolved, Closed", required = false) String state,
            @ToolParam(description = "Nuovo assegnatario", required = false) String assignedTo,
            @ToolParam(description = "Nuovo iteration path", required = false) String iterationPath) {
        return Mono.defer(() -> {
            List<Map<String, String>> patchOps = buildPatchDocument(
                    title, description, state, assignedTo, iterationPath, null);

            if (patchOps.isEmpty()) {
                return Mono.just(Map.<String, Object>of("error", "Nessun campo da aggiornare specificato"));
            }

            return webClient.patch()
                    .uri(props.getBaseUrl() + "/_apis/wit/workitems/" + workItemId
                            + "?api-version=" + props.getApiVersion())
                    .contentType(MediaType.valueOf("application/json-patch+json"))
                    .bodyValue(patchOps)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(r -> (Map<String, Object>) r);
        })
        .onErrorResume(e -> Mono.just(Map.of("error", "Errore aggiornamento work item " + workItemId + ": " + e.getMessage())));
    }

    @ReactiveTool(name = "devops_search_work_items",
          description = "Cerca work item per filtri comuni (stato, tipo, assegnatario, sprint, tag). "
                      + "Tutti i filtri sono opzionali. Senza filtri restituisce i work item recenti.")
    public Mono<Map<String, Object>> searchWorkItems(
            @ToolParam(description = "Stato: New, Active, Resolved, Closed", required = false) String state,
            @ToolParam(description = "Tipo: Bug, Task, User Story, Feature, Epic", required = false) String workItemType,
            @ToolParam(description = "Assegnatario (email o nome). Usa '@me' per l'utente corrente", required = false) String assignedTo,
            @ToolParam(description = "Iteration path o nome sprint, es: Sprint 5", required = false) String iteration,
            @ToolParam(description = "Tag da filtrare", required = false) String tag) {

        StringBuilder wiql = new StringBuilder(
                "SELECT [System.Id], [System.Title], [System.State], [System.WorkItemType], [System.AssignedTo] "
              + "FROM workitems WHERE [System.TeamProject] = @project");

        if (state != null && !state.isBlank()) {
            wiql.append(" AND [System.State] = '").append(state.replace("'", "''")).append("'");
        }
        if (workItemType != null && !workItemType.isBlank()) {
            wiql.append(" AND [System.WorkItemType] = '").append(workItemType.replace("'", "''")).append("'");
        }
        if (assignedTo != null && !assignedTo.isBlank()) {
            if ("@me".equalsIgnoreCase(assignedTo.trim())) {
                wiql.append(" AND [System.AssignedTo] = @me");
            } else {
                wiql.append(" AND [System.AssignedTo] = '").append(assignedTo.replace("'", "''")).append("'");
            }
        }
        if (iteration != null && !iteration.isBlank()) {
            wiql.append(" AND [System.IterationPath] UNDER '").append(iteration.replace("'", "''")).append("'");
        }
        if (tag != null && !tag.isBlank()) {
            wiql.append(" AND [System.Tags] CONTAINS '").append(tag.replace("'", "''")).append("'");
        }

        wiql.append(" ORDER BY [System.ChangedDate] DESC");

        return queryWorkItems(wiql.toString());
    }

    private Map<String, String> patchOp(String field, String value) {
        return Map.of("op", "add", "path", "/fields/" + field, "value", value);
    }

    private List<Map<String, String>> buildPatchDocument(
            String title, String description, String state,
            String assignedTo, String iterationPath, String areaPath) {
        List<Map<String, String>> ops = new ArrayList<>();
        if (title != null && !title.isBlank()) ops.add(patchOp("System.Title", title));
        if (description != null && !description.isBlank()) ops.add(patchOp("System.Description", description));
        if (state != null && !state.isBlank()) ops.add(patchOp("System.State", state));
        if (assignedTo != null && !assignedTo.isBlank()) ops.add(patchOp("System.AssignedTo", assignedTo));
        if (iterationPath != null && !iterationPath.isBlank()) ops.add(patchOp("System.IterationPath", iterationPath));
        if (areaPath != null && !areaPath.isBlank()) ops.add(patchOp("System.AreaPath", areaPath));
        return ops;
    }
}
