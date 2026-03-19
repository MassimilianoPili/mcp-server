package com.example.mcp.tools;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Task queue tools per coordinamento inter-sessione Claude.
 * Triple-write: PostgreSQL (source of truth) + Redis (dispatch veloce) + Preference Sort (ranking).
 *
 * <p>I task persistono in {@code claude_tasks} su PostgreSQL. Redis {@code claude:taskq}
 * è un canale di notifica opzionale. {@code claude_task_list} legge sempre da PostgreSQL,
 * rendendo il sistema resiliente a restart Redis.</p>
 *
 * <p>Preference Sort integration: task auto-registrati all'enqueue, auto-rimossi al complete.
 * Best-effort — se Preference Sort è down, il task resta in DB senza ranking.</p>
 */
@Service
@ConditionalOnProperty(name = "mcp.taskqueue.enabled", havingValue = "true", matchIfMissing = false)
public class ClaudeTaskQueueTools {

    private static final Logger log = LoggerFactory.getLogger(ClaudeTaskQueueTools.class);
    private static final String RANK_API = "http://preference-sort:8093";
    private static final String RANK_USER = "f7294891-b031-432d-8382-8592d3e6b1aa";

    private final JdbcTemplate jdbc;
    private final ReactiveStringRedisTemplate msg;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeTaskQueueTools(
            @Qualifier("taskQueueDataSource") javax.sql.DataSource dataSource,
            @Qualifier("mcpRedisMessagingTemplate") ReactiveStringRedisTemplate msg) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.msg = msg;
        log.info("ClaudeTaskQueueTools inizializzato (DB + Redis DB 5 + Preference Sort)");
    }

    @ReactiveTool(name = "claude_task_enqueue",
            description = "Enqueues a task for a future agent. Dual-write: PostgreSQL (durability) + Redis (dispatch). " +
                    "If targetLabel is null, any future session can dequeue it. " +
                    "The user explicitly asks to check the queue to see and choose tasks.")
    public Mono<String> claudeTaskEnqueue(
            @ToolParam(description = "Correlation slug (e.g. 'research-gp', 'deploy-check')") String ref,
            @ToolParam(description = "Task type (e.g. 'research', 'code-review', 'ops', 'test')") String taskType,
            @ToolParam(description = "JSON task payload with fields: task, context, constraints") String payloadJson,
            @ToolParam(description = "Creating session label (e.g. 'chat-31')") String createdBy,
            @ToolParam(description = "Specific target label (null = anyone)", required = false) String targetLabel,
            @ToolParam(description = "Priority 1-10 (1=urgent, 5=default, 10=low)", required = false) Integer priority) {

        int prio = (priority != null) ? Math.max(1, Math.min(10, priority)) : 5;

        return Mono.fromCallable(() -> {
            // 1. INSERT in PostgreSQL → ottieni task_id
            Long taskId = jdbc.queryForObject(
                    "INSERT INTO claude_tasks (ref, task_type, payload_json, created_by, target_label, priority) " +
                            "VALUES (?, ?, ?::jsonb, ?, ?, ?) RETURNING task_id",
                    Long.class, ref, taskType, payloadJson, createdBy, targetLabel, prio);

            // 2. LPUSH Redis (best-effort)
            String redisMsg = String.format(
                    "{\"task_id\":%d,\"ref\":\"%s\",\"priority\":%d,\"type\":\"%s\"}",
                    taskId, ref, prio, taskType);
            boolean redisOk = false;
            try {
                msg.opsForList().leftPush("claude:taskq", redisMsg).block();
                jdbc.update("UPDATE claude_tasks SET redis_key = 'claude:taskq', dispatched_at = now() WHERE task_id = ?",
                        taskId);
                redisOk = true;
            } catch (Exception e) {
                log.warn("Redis dispatch fallito per task #{}: {}", taskId, e.getMessage());
            }

            // 3. Register in Preference Sort (best-effort)
            boolean rankOk = false;
            try {
                String listUuid = findOrCreateTaskQueueList();
                addItemToRanking(listUuid, taskId, ref, taskType);
                rankOk = true;
            } catch (Exception e) {
                log.warn("Preference Sort registration fallita per task #{}: {}", taskId, e.getMessage());
            }

            String sinks = (redisOk ? "Redis" : "") + (redisOk && rankOk ? " + " : "") + (rankOk ? "Ranker" : "");
            if (sinks.isEmpty()) sinks = "solo DB";
            return String.format("Task #%d accodato (DB + %s) — ref: %s, tipo: %s, priorità: %d",
                    taskId, sinks, ref, taskType, prio);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @ReactiveTool(name = "claude_task_claim",
            description = "Claims a specific task (chosen by the user after viewing the list). " +
                    "Updates PostgreSQL: status=CLAIMED, claimed_by, claimed_at. Returns the task payload.")
    public Mono<String> claudeTaskClaim(
            @ToolParam(description = "ID of the task to claim") Long taskId,
            @ToolParam(description = "Session label (e.g. 'chat-42')") String claimedBy) {

        return Mono.fromCallable(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "UPDATE claude_tasks SET status = 'CLAIMED', claimed_by = ?, claimed_at = now() " +
                            "WHERE task_id = ? AND status = 'PENDING' " +
                            "RETURNING task_id, ref, task_type, payload_json::text, priority, created_by, " +
                            "to_char(created_at, 'YYYY-MM-DD HH24:MI') as created",
                    claimedBy, taskId);

            if (rows.isEmpty()) {
                return "ERRORE: Task #" + taskId + " non trovato o non in stato PENDING";
            }

            Map<String, Object> row = rows.getFirst();
            return String.format("Task #%s preso in carico da %s\nRef: %s | Tipo: %s | Prio: %s | Da: %s (%s)\nPayload: %s",
                    row.get("task_id"), claimedBy,
                    row.get("ref"), row.get("task_type"), row.get("priority"),
                    row.get("created_by"), row.get("created"),
                    row.get("payload_json"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @ReactiveTool(name = "claude_task_complete",
            description = "Marks a task as completed with its result. " +
                    "Status: 'success' or 'partial' → COMPLETED, 'failed' → FAILED.")
    public Mono<String> claudeTaskComplete(
            @ToolParam(description = "Task ID") Long taskId,
            @ToolParam(description = "Status: success, partial, failed") String status,
            @ToolParam(description = "JSON result") String resultJson) {

        String dbStatus = "failed".equalsIgnoreCase(status) ? "FAILED" : "COMPLETED";

        return Mono.fromCallable(() -> {
            int updated = jdbc.update(
                    "UPDATE claude_tasks SET status = ?, result_json = ?::jsonb, completed_at = now() " +
                            "WHERE task_id = ? AND status = 'CLAIMED'",
                    dbStatus, resultJson, taskId);

            if (updated == 0) {
                return "ERRORE: Task #" + taskId + " non trovato o non in stato CLAIMED";
            }

            // Remove from Preference Sort (best-effort)
            try {
                removeItemFromRanking(taskId);
            } catch (Exception e) {
                log.warn("Preference Sort removal fallita per task #{}: {}", taskId, e.getMessage());
            }

            return String.format("Task #%d → %s (%s)", taskId, dbStatus, status);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @ReactiveTool(name = "claude_progress_persist",
            description = "Saves a snapshot of current progress (title + todo list) in claude_tasks " +
                    "as task_type='progress'. Useful for cross-session continuity: the next session " +
                    "can read the state where the previous one left off. Not auto-claimed.")
    public Mono<String> claudeProgressPersist(
            @ToolParam(description = "Work context title (e.g. 'Implementing Claude Code hooks')") String title,
            @ToolParam(description = "JSON with current state: {todos: [{content, status}], notes: '...'}") String progressJson,
            @ToolParam(description = "Session label (e.g. 'chat-74')") String createdBy) {

        return Mono.fromCallable(() -> {
            String ref = "progress-" + System.currentTimeMillis() / 1000;
            String payload = String.format("{\"title\":\"%s\",\"progress\":%s}",
                    title.replace("\"", "\\\""), progressJson);

            Long taskId = jdbc.queryForObject(
                    "INSERT INTO claude_tasks (ref, task_type, payload_json, created_by, priority) " +
                            "VALUES (?, 'progress', ?::jsonb, ?, 10) RETURNING task_id",
                    Long.class, ref, payload, createdBy);

            return String.format("Progress salvato come task #%d (ref: %s). La prossima sessione può leggerlo con claude_task_list('ALL').",
                    taskId, ref);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @ReactiveTool(name = "claude_task_list",
            description = "Lists tasks by status. Reads from PostgreSQL (source of truth, resilient to Redis restarts). " +
                    "Status: PENDING (default), CLAIMED, COMPLETED, FAILED, DISPATCHED (in Redis but not completed), ALL, " +
                    "RANKED (orders by BT score from Preference Sort instead of numeric priority).")
    public Mono<List<String>> claudeTaskList(
            @ToolParam(description = "Filter: PENDING, CLAIMED, COMPLETED, FAILED, DISPATCHED, RANKED, ALL", required = false) String status) {

        String filter = (status != null) ? status.toUpperCase() : "PENDING";

        if ("RANKED".equals(filter)) {
            return claudeTaskListRanked();
        }

        String where = switch (filter) {
            case "DISPATCHED" -> "dispatched_at IS NOT NULL AND status NOT IN ('COMPLETED','FAILED','CANCELLED')";
            case "ALL" -> "TRUE";
            default -> "status = '" + filter + "'";
        };

        return Mono.fromCallable(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT task_id, ref, task_type, priority, status, created_by, " +
                            "coalesce(claimed_by, '') as claimed_by, " +
                            "to_char(created_at, 'MM-DD HH24:MI') as created, " +
                            "CASE WHEN redis_key IS NOT NULL THEN 'Y' ELSE 'N' END as redis, " +
                            "left(payload_json::text, 200) as payload_preview " +
                            "FROM claude_tasks WHERE " + where +
                            " ORDER BY priority, created_at LIMIT 50");

            List<String> result = new ArrayList<>();
            result.add(String.format("=== %d task %s ===", rows.size(), filter));

            for (Map<String, Object> r : rows) {
                result.add(String.format("#%-4s  %-20s [%-12s]  prio:%-2s  %s  da:%-10s  preso:%-10s  redis:%s\n       %s",
                        r.get("task_id"), r.get("ref"), r.get("task_type"),
                        r.get("priority"), r.get("status"),
                        r.get("created_by"), r.get("claimed_by"),
                        r.get("redis"),
                        r.get("payload_preview")));
            }

            if (rows.isEmpty()) {
                result.add("(nessun task " + filter + ")");
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Preference Sort helpers (best-effort) ──────────────────────────

    private String findOrCreateTaskQueueList() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RANK_API + "/lists?limit=100"))
                .header("X-Auth-User-Id", RANK_USER)
                .GET().build();
        JsonNode lists = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());

        for (JsonNode list : lists) {
            if ("task-queue".equals(list.path("category").asText())) {
                return list.path("id").asText();
            }
        }

        // Create list if not found
        String body = "{\"name\":\"Task Queue\",\"category\":\"task-queue\",\"ig_threshold\":0.01}";
        HttpRequest create = HttpRequest.newBuilder()
                .uri(URI.create(RANK_API + "/lists"))
                .header("X-Auth-User-Id", RANK_USER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonNode created = mapper.readTree(http.send(create, HttpResponse.BodyHandlers.ofString()).body());
        String uuid = created.path("id").asText();
        log.info("Preference Sort: creata lista task-queue {}", uuid);
        return uuid;
    }

    private void addItemToRanking(String listUuid, long taskId, String ref, String taskType) throws Exception {
        String itemName = String.format("#%d %s [%s]", taskId, ref, taskType);
        String body = String.format("{\"items\":[{\"name\":\"%s\"}]}", itemName);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RANK_API + "/lists/" + listUuid + "/items"))
                .header("X-Auth-User-Id", RANK_USER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonNode resp = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        String itemUuid = resp.path(0).path("id").asText(null);
        if (itemUuid != null && !itemUuid.isEmpty()) {
            jdbc.update("UPDATE claude_tasks SET rank_item_uuid = ?::uuid WHERE task_id = ?", itemUuid, taskId);
            log.info("Preference Sort: task #{} registrato come item {}", taskId, itemUuid);
        }
    }

    private void removeItemFromRanking(long taskId) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rank_item_uuid::text FROM claude_tasks WHERE task_id = ? AND rank_item_uuid IS NOT NULL", taskId);
        if (rows.isEmpty()) return;

        String itemUuid = rows.getFirst().get("rank_item_uuid").toString();
        String listUuid = findOrCreateTaskQueueList();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RANK_API + "/lists/" + listUuid + "/items/" + itemUuid))
                .header("X-Auth-User-Id", RANK_USER)
                .DELETE().build();
        http.send(req, HttpResponse.BodyHandlers.ofString());
        jdbc.update("UPDATE claude_tasks SET rank_item_uuid = NULL WHERE task_id = ?", taskId);
        log.info("Preference Sort: task #{} rimosso (item {})", taskId, itemUuid);
    }

    private Mono<List<String>> claudeTaskListRanked() {
        return Mono.fromCallable(() -> {
            List<String> result = new ArrayList<>();

            // 1. Find task-queue list in Preference Sort
            String listUuid;
            try {
                listUuid = findOrCreateTaskQueueList();
            } catch (Exception e) {
                result.add("=== Preference Sort non raggiungibile ===");
                result.add(e.getMessage());
                return result;
            }

            // 2. Get ranking
            HttpRequest rankReq = HttpRequest.newBuilder()
                    .uri(URI.create(RANK_API + "/lists/" + listUuid + "/ranking"))
                    .header("X-Auth-User-Id", RANK_USER)
                    .GET().build();

            HttpResponse<String> rankResp = http.send(rankReq, HttpResponse.BodyHandlers.ofString());
            JsonNode ranking = mapper.readTree(rankResp.body());

            boolean converged = ranking.path("converged").asBoolean(false);
            result.add(String.format("=== Task PENDING (BT ranking, %s) ===",
                    converged ? "CONVERGED" : "non convergente — votare in rank-tui"));

            // 3. Map ranking items to task details
            JsonNode items = ranking.path("items");
            Map<Long, JsonNode> rankMap = new HashMap<>();
            for (JsonNode item : items) {
                String name = item.path("name").asText("");
                if (name.startsWith("#")) {
                    try {
                        long taskId = Long.parseLong(name.substring(1).split(" ")[0]);
                        rankMap.put(taskId, item);
                    } catch (NumberFormatException ignored) {}
                }
            }

            // 4. Query DB for task details, ordered by BT rank
            if (rankMap.isEmpty()) {
                result.add("(nessun task nel ranking)");
                return result;
            }

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT task_id, ref, task_type, priority, created_by, " +
                            "to_char(created_at, 'MM-DD HH24:MI') as created, " +
                            "coalesce(wiki_page_path, '') as wiki, " +
                            "left(payload_json::text, 150) as payload_preview " +
                            "FROM claude_tasks WHERE status = 'PENDING' ORDER BY task_id");

            // Sort by BT rank
            rows.sort((a, b) -> {
                long aId = ((Number) a.get("task_id")).longValue();
                long bId = ((Number) b.get("task_id")).longValue();
                int aRank = rankMap.containsKey(aId) ? rankMap.get(aId).path("rank").asInt(999) : 999;
                int bRank = rankMap.containsKey(bId) ? rankMap.get(bId).path("rank").asInt(999) : 999;
                return Integer.compare(aRank, bRank);
            });

            for (Map<String, Object> r : rows) {
                long taskId = ((Number) r.get("task_id")).longValue();
                JsonNode item = rankMap.get(taskId);
                int rank = item != null ? item.path("rank").asInt(0) : 0;
                double score = item != null ? item.path("score").asDouble(1.0) : 1.0;
                double se = item != null ? item.path("se").asDouble(1.0) : 1.0;
                String wiki = r.get("wiki").toString();
                String wikiStr = wiki.isEmpty() ? "" : " wiki:" + wiki;

                result.add(String.format("rank:%d  #%-4s  %-22s [%-8s]  BT:%.2f  SE:%.2f  da:%-10s  %s%s\n       %s",
                        rank, r.get("task_id"), r.get("ref"), r.get("task_type"),
                        score, se, r.get("created_by"), r.get("created"), wikiStr,
                        r.get("payload_preview")));
            }

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
