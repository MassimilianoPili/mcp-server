package com.example.mcp;

import io.github.massimilianopili.mcp.vector.ingest.ChunkingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(name = "mcp.vector.enabled", havingValue = "true")
public class AdminController {

    private final ChunkingService chunkingService;

    public AdminController(ChunkingService chunkingService) {
        this.chunkingService = chunkingService;
    }

    @PostMapping("/reindex/{type}")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable String type) {
        return ResponseEntity.ok(chunkingService.reindexAsync(type));
    }

    @GetMapping("/reindex/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(chunkingService.getReindexStatus());
    }
}
