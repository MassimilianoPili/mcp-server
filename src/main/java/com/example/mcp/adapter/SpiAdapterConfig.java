package com.example.mcp.adapter;

import com.example.mcp.tools.ChunkedFetchService;
import com.example.mcp.tools.FetchCacheService;
import com.example.mcp.tools.KoreLookupService;
import com.example.mcp.tools.WebIngestService;
import io.github.massimilianopili.mcp.research.spi.IngestQueue;
import io.github.massimilianopili.mcp.research.spi.ValidationCache;
import io.github.massimilianopili.mcp.search.spi.ChunkStore;
import io.github.massimilianopili.mcp.search.spi.ContentIngester;
import io.github.massimilianopili.mcp.search.spi.FetchCache;
import io.github.massimilianopili.mcp.search.spi.SemanticLookup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adapter beans that bridge server-local services to library SPI interfaces.
 * Each bean delegates to an existing service, created only when that service exists.
 */
@Configuration
public class SpiAdapterConfig {

    @Bean
    @ConditionalOnBean(FetchCacheService.class)
    public FetchCache fetchCacheAdapter(FetchCacheService svc) {
        return new FetchCache() {
            @Override public reactor.core.publisher.Mono<String> get(String url) { return svc.getCached(url); }
            @Override public reactor.core.publisher.Mono<Boolean> put(String url, String json) { return svc.putCache(url, json); }
        };
    }

    @Bean
    @ConditionalOnBean(FetchCacheService.class)
    public ValidationCache validationCacheAdapter(FetchCacheService svc) {
        return new ValidationCache() {
            @Override public reactor.core.publisher.Mono<String> get(String title) { return svc.getValidationCached(title); }
            @Override public reactor.core.publisher.Mono<Boolean> put(String title, String json) { return svc.putValidationCache(title, json); }
        };
    }

    @Bean
    @ConditionalOnBean(ChunkedFetchService.class)
    public ChunkStore chunkStoreAdapter(ChunkedFetchService svc) {
        return new ChunkStore() {
            @Override public reactor.core.publisher.Mono<String> storeAndReturnFirst(String content, String url, String contentType) {
                return svc.storeAndReturnFirst(content, url, contentType);
            }
            @Override public reactor.core.publisher.Mono<String> getChunk(String fetchId, int chunkIndex) {
                return svc.getChunk(fetchId, chunkIndex);
            }
        };
    }

    @Bean
    @ConditionalOnBean(KoreLookupService.class)
    public SemanticLookup semanticLookupAdapter(KoreLookupService svc) {
        return new SemanticLookup() {
            @Override public boolean isAvailable() { return svc.isAvailable(); }
            @Override public String search(String query, int limit) { return svc.searchSemantic(query, limit); }
            @Override public String searchWithGraphExpansion(String query, int vectorLimit, int graphDepth) {
                return svc.searchGraphAugmented(query, vectorLimit, graphDepth);
            }
        };
    }

    @Bean
    @ConditionalOnBean(WebIngestService.class)
    public ContentIngester contentIngesterAdapter(WebIngestService svc) {
        return new ContentIngester() {
            @Override public String ingest(String url, String title, String contentType, String body,
                                           java.util.List<String> authors, Integer year, String venue,
                                           java.util.List<String> concepts, java.util.Map<String, Object> extraMeta) {
                return svc.ingest(url, title, contentType, body, authors, year, venue, concepts, extraMeta);
            }
            @Override public String ingestFromExtract(String extractedJson) {
                return svc.ingestFromExtract(extractedJson);
            }
        };
    }

    @Bean
    @ConditionalOnBean(name = "ingestQueueRepository")
    public io.github.massimilianopili.mcp.search.spi.IngestQueue searchIngestQueueAdapter(
            io.github.massimilianopili.mcp.queue.IngestQueueRepository repo) {
        return repo::enqueue;
    }

    @Bean
    @ConditionalOnBean(name = "ingestQueueRepository")
    public IngestQueue researchIngestQueueAdapter(
            io.github.massimilianopili.mcp.queue.IngestQueueRepository repo) {
        return repo::enqueue;
    }
}
