package com.example.mcp.tools;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import io.github.massimilianopili.mcp.playwright.PlaywrightProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Service
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 3;

    // URL host → query param name, per fallback SearXNG science search su 429
    private static final Map<String, String> ACADEMIC_API_QUERY_PARAMS = Map.of(
            "api.semanticscholar.org", "query",
            "api.crossref.org", "query",
            "export.arxiv.org", "search_query",
            "api.openalex.org", "search"
    );

    private final WebClient searxngClient;

    private final WebClient httpClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .defaultHeader("User-Agent", USER_AGENT)
            .defaultHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
            .defaultHeader("Accept-Language", "en-US,en;q=0.9,it;q=0.8")
            .build();

    @Autowired(required = false)
    private ChunkedFetchService chunkedFetchService;

    @Autowired(required = false)
    private PlaywrightProvider playwrightProvider;

    @Autowired(required = false)
    private FetchCacheService fetchCache;

    @Autowired(required = false)
    private IngestQueueRepository ingestQueue;

    @Autowired(required = false)
    private KoreLookupService koreLookup;

    public WebSearchTools(
            @Value("${mcp.websearch.url:http://searxng:8080}") String searxngUrl) {
        this.searxngClient = WebClient.builder()
                .baseUrl(searxngUrl)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @ReactiveTool(
            name = "web_search",
            description = "Performs a web search via self-hosted SearXNG (meta-engine: Google, Bing, DuckDuckGo, Brave, Wikipedia). " +
                          "Category 'science': aggregates Semantic Scholar, CrossRef, arXiv, OpenAlex, PubMed, Google Scholar, Springer. " +
                          "Returns structured JSON results with title, URL, snippet and metadata. " +
                          "Available categories: general, science, it, news. " +
                          "More resilient than built-in WebSearch: failures are isolated per individual call."
    )
    public Mono<String> webSearch(
            @ToolParam(description = "Search query") String query,
            @ToolParam(description = "Maximum number of results (default 10, max 30)") int maxResults,
            @ToolParam(description = "Comma-separated categories, e.g.: 'general,science'. Default: 'general'") String categories,
            @ToolParam(description = "Result language, e.g.: 'it', 'en', 'auto'. Default: 'auto'") String language) {

        int limit = maxResults > 0 ? Math.min(maxResults, 30) : 10;
        String cats = (categories != null && !categories.isBlank()) ? categories : "general";
        String lang = (language != null && !language.isBlank()) ? language : "auto";

        // KORE semantic prepend (pgvector lookup)
        String korePrefix = "";
        if (koreLookup != null && koreLookup.isAvailable()) {
            try {
                String koreResults = koreLookup.searchSemantic(query, 3);
                if (koreResults != null) {
                    korePrefix = "--- From KORE (cached knowledge) ---\n" + koreResults + "\n--- Web results ---\n";
                }
            } catch (Exception e) {
                log.debug("KORE lookup skipped for '{}': {}", query, e.getMessage());
            }
        }
        final String prefix = korePrefix;

        return searxngClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search")
                        .queryParam("q", query)
                        .queryParam("format", "json")
                        .queryParam("categories", cats)
                        .queryParam("language", lang)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(FETCH_TIMEOUT)
                .map(result -> prefix.isEmpty() ? result : prefix + result)
                .onErrorResume(e -> Mono.just(
                        (prefix.isEmpty() ? "" : prefix) +
                        "Errore nella ricerca web per '" + query + "': " + e.getMessage()));
    }

    @ReactiveTool(
            name = "web_fetch",
            description = "Downloads the content of a URL and returns the body as text. " +
                          "4-level resilience: (1) browser-like headers, (2) retry x3 with backoff on 429/5xx, " +
                          "(3) SearXNG science search fallback for rate-limited academic APIs, " +
                          "(4) headless Playwright fallback for sites with Cloudflare/bot protection. " +
                          "For large responses (>6KB), content is split into chunks on Redis (TTL 10min). " +
                          "Returns the first chunk + metadata (fetch_id, total_chunks). " +
                          "Use web_fetch_chunk(fetch_id, index) for subsequent chunks. " +
                          "Small responses are returned directly. " +
                          "Parameter 'extract': 'semantic_scholar', 'arxiv', 'openalex' for smart extraction " +
                          "of key fields (compresses API responses from ~168KB to ~3-5KB avoiding chunking). " +
                          "Limit: 2MB."
    )
    public Mono<String> webFetch(
            @ToolParam(description = "Full URL to download, e.g.: 'https://example.com/page'") String url,
            @ToolParam(description = "Smart extraction: 'semantic_scholar', 'arxiv', 'openalex', or null/empty for raw",
                       required = false) String extract) {

        // Redis cache lookup for extracted content (skip for raw fetches)
        if (extract != null && !extract.isBlank() && fetchCache != null) {
            try {
                String cached = fetchCache.getCached(url).block();
                if (cached != null) {
                    log.info("web_fetch cache hit for '{}'", url);
                    return Mono.just(cached);
                }
            } catch (Exception e) {
                log.debug("Cache lookup failed for '{}': {}", url, e.getMessage());
            }
        }

        return httpClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(FETCH_TIMEOUT)
                .retryWhen(Retry.backoff(MAX_RETRIES, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(10))
                        .jitter(0.3)
                        .filter(WebSearchTools::isRetryable)
                        .doBeforeRetry(s -> log.warn("web_fetch retry #{} per '{}': {}",
                                s.totalRetries() + 1, url, s.failure().getMessage())))
                .flatMap(body -> processResponse(body, url, extract))
                .onErrorResume(e -> {
                    log.error("web_fetch fallito per '{}': {}", url, e.getMessage());
                    // Livello 3: SearXNG per API accademiche rate-limitate
                    String scienceQuery = extractAcademicQuery(url);
                    if (scienceQuery != null && is429(e)) {
                        log.info("web_fetch fallback SearXNG science per '{}'", scienceQuery);
                        return webSearch(scienceQuery, 10, "science", "en")
                                .flatMap(body -> processResponse(body, url, null));
                    }
                    // Livello 4: Playwright per 429/403 su URL non accademici
                    if (shouldFallbackToPlaywright(e)) {
                        return fetchWithPlaywright(url, extract);
                    }
                    return Mono.just("Errore nel fetch di '" + url + "': " + e.getMessage());
                });
    }

    @ReactiveTool(
            name = "web_fetch_chunk",
            description = "Retrieves a specific chunk from a previous fetch (web_fetch with response >6KB). " +
                          "Use the fetch_id and chunk_index returned by web_fetch in the metadata field. " +
                          "Each chunk is ~6KB. Chunks expire after 10 minutes on Redis. " +
                          "Example: if web_fetch returns total_chunks=15, call web_fetch_chunk with index 0 to 14."
    )
    public Mono<String> webFetchChunk(
            @ToolParam(description = "Fetch ID (UUID returned by web_fetch)") String fetchId,
            @ToolParam(description = "Chunk index (0-based)") int chunkIndex) {

        if (chunkedFetchService == null) {
            return Mono.just("{\"error\": \"Chunking non disponibile: Redis non configurato\"}");
        }
        return chunkedFetchService.getChunk(fetchId, chunkIndex);
    }

    // --- Fallback helpers ---

    private static boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return t instanceof java.util.concurrent.TimeoutException
                || t instanceof java.net.ConnectException;
    }

    private static boolean is429(Throwable t) {
        // After retryWhen exhaustion, the cause is wrapped in RetryExhaustedException
        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
        return cause instanceof WebClientResponseException wcre
                && wcre.getStatusCode().value() == 429;
    }

    private static boolean shouldFallbackToPlaywright(Throwable t) {
        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
        if (cause instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 429 || status == 403;
        }
        return false;
    }

    private static String extractAcademicQuery(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String queryParamName = ACADEMIC_API_QUERY_PARAMS.entrySet().stream()
                    .filter(e -> host != null && host.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (queryParamName == null || uri.getQuery() == null) return null;
            for (String param : uri.getQuery().split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && kv[0].equals(queryParamName)) {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Mono<String> fetchWithPlaywright(String url, String extract) {
        if (playwrightProvider == null || !playwrightProvider.isAvailable()) {
            return Mono.just("Errore nel fetch di '" + url + "': fallback non disponibili");
        }
        return Mono.fromCallable(() -> {
                    log.info("web_fetch fallback Playwright per '{}'", url);
                    var page = playwrightProvider.getPage();
                    page.navigate(url, new com.microsoft.playwright.Page.NavigateOptions()
                            .setTimeout(FETCH_TIMEOUT.toMillis()));
                    return page.content();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(body -> processResponse(body, url, extract))
                .onErrorResume(e -> {
                    log.error("web_fetch Playwright fallito per '{}': {}", url, e.getMessage());
                    return Mono.just("Errore nel fetch di '" + url + "' (tutti i fallback esauriti): " + e.getMessage());
                });
    }

    // --- Response processing ---

    private Mono<String> processResponse(String body, String url, String extract) {
        // Smart extraction
        if (extract != null && !extract.isBlank()) {
            String extracted = switch (extract.toLowerCase().trim()) {
                case "semantic_scholar" -> ApiExtractors.extractSemanticScholar(body);
                case "arxiv" -> ApiExtractors.extractArxiv(body);
                case "openalex" -> ApiExtractors.extractOpenAlex(body);
                default -> null;
            };
            if (extracted != null) {
                // Fire-and-forget: Redis cache + PG ingest queue
                if (fetchCache != null) {
                    fetchCache.putCache(url, extracted).subscribe();
                }
                if (ingestQueue != null) {
                    ingestQueue.enqueue(url, extracted, extract.toLowerCase().trim());
                }
                return Mono.just(extracted);
            }
            // Extraction failed, fall through to chunking
        }

        // Small response: return inline
        if (body.length() <= WebFetchChunkConfig.CHUNK_SIZE) {
            return Mono.just(body);
        }

        // Large response: chunk if Redis available
        if (chunkedFetchService != null) {
            String contentType = guessContentType(body);
            return chunkedFetchService.storeAndReturnFirst(body, url, contentType);
        }

        // Fallback: truncate with warning
        return Mono.just(body.substring(0, WebFetchChunkConfig.CHUNK_SIZE)
                + "\n\n[TRONCATO: " + body.length() + " bytes totali. Redis non disponibile per chunking]");
    }

    private String guessContentType(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "application/json";
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<")) return "text/xml";
        return "text/plain";
    }
}
