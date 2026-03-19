package com.example.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true", matchIfMissing = false)
public class ChunkedFetchService {

    private static final Logger log = LoggerFactory.getLogger(ChunkedFetchService.class);
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChunkedFetchService(
            @Qualifier("fetchChunkRedisTemplate") ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<String> storeAndReturnFirst(String content, String url, String contentType) {
        String fetchId = UUID.randomUUID().toString();
        int chunkSize = WebFetchChunkConfig.CHUNK_SIZE;
        List<String> chunks = splitIntoChunks(content, chunkSize);
        int totalChunks = chunks.size();

        // Store all chunks in parallel
        List<Mono<Boolean>> stores = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            String key = "fetch:" + fetchId + ":" + i;
            String chunk = chunks.get(i);
            stores.add(redis.opsForValue()
                    .set(key, chunk, WebFetchChunkConfig.CHUNK_TTL));
        }

        // Store metadata
        ObjectNode meta = mapper.createObjectNode();
        meta.put("url", url);
        meta.put("total_chunks", totalChunks);
        meta.put("total_size_bytes", content.length());
        meta.put("content_type", contentType != null ? contentType : "text/plain");
        String metaKey = "fetch:" + fetchId + ":meta";
        stores.add(redis.opsForValue()
                .set(metaKey, meta.toString(), WebFetchChunkConfig.CHUNK_TTL));

        return Flux.merge(stores)
                .then(Mono.fromCallable(() -> {
                    ObjectNode envelope = mapper.createObjectNode();
                    envelope.put("fetch_id", fetchId);
                    envelope.put("chunk_index", 0);
                    envelope.put("total_chunks", totalChunks);
                    envelope.put("total_size_bytes", content.length());
                    envelope.put("content_type", contentType != null ? contentType : "text/plain");
                    envelope.put("url", url);
                    envelope.put("ttl_seconds", WebFetchChunkConfig.CHUNK_TTL.toSeconds());
                    envelope.put("content", chunks.get(0));
                    if (totalChunks > 1) {
                        envelope.put("next_step",
                                "Per leggere i chunk successivi, chiamare web_fetch_chunk(fetch_id=\"" + fetchId
                                + "\", chunk_index=N) con N da 1 a " + (totalChunks - 1)
                                + ". I chunk scadono tra " + WebFetchChunkConfig.CHUNK_TTL.toSeconds() + " secondi.");
                    }
                    log.info("Chunked fetch stored: {} ({} chunks, {} bytes)", fetchId, totalChunks, content.length());
                    return envelope.toString();
                }));
    }

    public Mono<String> getChunk(String fetchId, int chunkIndex) {
        String metaKey = "fetch:" + fetchId + ":meta";
        return redis.opsForValue().get(metaKey)
                .switchIfEmpty(Mono.just(""))
                .flatMap(metaJson -> {
                    if (metaJson.isEmpty()) {
                        return Mono.just("{\"error\": \"Fetch scaduto o non trovato: " + fetchId + "\"}");
                    }
                    try {
                        ObjectNode meta = (ObjectNode) mapper.readTree(metaJson);
                        int totalChunks = meta.get("total_chunks").asInt();
                        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                            return Mono.just("{\"error\": \"chunk_index " + chunkIndex
                                    + " fuori range [0, " + (totalChunks - 1) + "]\"}");
                        }
                        String chunkKey = "fetch:" + fetchId + ":" + chunkIndex;
                        return redis.opsForValue().get(chunkKey)
                                .map(chunkContent -> {
                                    ObjectNode envelope = mapper.createObjectNode();
                                    envelope.put("fetch_id", fetchId);
                                    envelope.put("chunk_index", chunkIndex);
                                    envelope.put("total_chunks", totalChunks);
                                    envelope.put("total_size_bytes", meta.get("total_size_bytes").asInt());
                                    envelope.put("content", chunkContent);
                                    return envelope.toString();
                                })
                                .switchIfEmpty(Mono.just("{\"error\": \"Chunk " + chunkIndex + " scaduto\"}"));
                    } catch (Exception e) {
                        return Mono.just("{\"error\": \"Errore parsing metadata: " + e.getMessage() + "\"}");
                    }
                });
    }

    private List<String> splitIntoChunks(String content, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < content.length(); i += chunkSize) {
            chunks.add(content.substring(i, Math.min(i + chunkSize, content.length())));
        }
        return chunks;
    }
}
