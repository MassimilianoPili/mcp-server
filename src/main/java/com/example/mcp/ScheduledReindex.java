package com.example.mcp;

import io.github.massimilianopili.mcp.vector.ingest.ChunkingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ScheduledReindex {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReindex.class);

    private final Optional<ChunkingService> chunkingService;

    public ScheduledReindex(Optional<ChunkingService> chunkingService) {
        this.chunkingService = chunkingService;
        if (chunkingService.isPresent()) {
            log.info("ScheduledReindex: attivo (ChunkingService presente)");
        } else {
            log.warn("ScheduledReindex: ChunkingService non disponibile, reindex disabilitato");
        }
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void dailyReindex() {
        chunkingService.ifPresentOrElse(
            svc -> {
                log.info("Scheduled reindex avviato (04:00 daily)");
                svc.reindexAsync("all");
            },
            () -> log.warn("Scheduled reindex skipped: ChunkingService non disponibile")
        );
    }
}
