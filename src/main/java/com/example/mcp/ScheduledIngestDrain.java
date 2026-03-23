package com.example.mcp;

import com.example.mcp.tools.IngestQueueRepository;
import com.example.mcp.tools.WebIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ScheduledIngestDrain {

    private static final Logger log = LoggerFactory.getLogger(ScheduledIngestDrain.class);
    private static final int BATCH_SIZE = 50;

    private final IngestQueueRepository ingestQueue;
    private final WebIngestService ingestService;

    public ScheduledIngestDrain(
            @Autowired(required = false) IngestQueueRepository ingestQueue,
            @Autowired(required = false) WebIngestService ingestService) {
        this.ingestQueue = ingestQueue;
        this.ingestService = ingestService;
        if (ingestQueue != null && ingestService != null) {
            log.info("ScheduledIngestDrain: active (IngestQueueRepository + WebIngestService present)");
        } else {
            log.info("ScheduledIngestDrain: disabled (missing dependencies)");
        }
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void drainIngestQueue() {
        if (ingestQueue == null || ingestService == null) {
            log.debug("Ingest drain skipped: dependencies not available");
            return;
        }

        int pending = ingestQueue.countPending();
        if (pending == 0) {
            log.info("Ingest drain: no pending items");
            return;
        }

        log.info("Ingest drain started: {} pending items (processing max {})", pending, BATCH_SIZE);

        List<Map<String, Object>> items = ingestQueue.drainPending(BATCH_SIZE);
        int success = 0, errors = 0;

        for (Map<String, Object> item : items) {
            int id = ((Number) item.get("id")).intValue();
            String json = (String) item.get("extracted_json");

            try {
                String result = ingestService.ingestFromExtract(json);
                if (result.contains("\"status\":\"ok\"") || result.contains("\"status\": \"ok\"")) {
                    ingestQueue.markDone(id);
                    success++;
                } else {
                    ingestQueue.markError(id, result);
                    errors++;
                }
            } catch (Exception e) {
                ingestQueue.markError(id, e.getMessage());
                errors++;
                log.warn("Ingest drain error for id {}: {}", id, e.getMessage());
            }
        }

        log.info("Ingest drain completed: {} processed, {} success, {} errors, {} remaining",
                items.size(), success, errors, Math.max(0, pending - items.size()));
    }
}
