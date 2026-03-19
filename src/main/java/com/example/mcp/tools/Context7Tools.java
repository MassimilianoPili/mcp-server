package com.example.mcp.tools;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class Context7Tools {

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://context7.com/api/v1")
            .defaultHeader("Accept", "application/json")
            .build();

    @ReactiveTool(
            name = "resolve_library_id",
            description = "Resolves a library name to its Context7 ID required by query_docs. " +
                          "Returns a list of matching libraries with ID, description and snippet count. " +
                          "Choose the most relevant ID from the list and pass it to query_docs."
    )
    public Mono<String> resolveLibraryId(
            @ToolParam(description = "Library name to search, e.g.: 'spring boot', 'langchain4j', 'react'") String libraryName) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/search").queryParam("q", libraryName).build())
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("Errore nella ricerca della libreria '" + libraryName + "': " + e.getMessage()));
    }

    @ReactiveTool(
            name = "query_docs",
            description = "Retrieves library documentation from Context7 given its ID. " +
                          "Use resolve_library_id first to get the correct ID (e.g.: '/vercel/next.js'). " +
                          "Returns documentation snippets in markdown format."
    )
    public Mono<String> queryDocs(
            @ToolParam(description = "Context7 library ID, e.g.: '/spring-projects/spring-boot', '/vercel/next.js'") String libraryId,
            @ToolParam(description = "Specific topic to search in documentation, e.g.: 'authentication', 'routing', 'configuration'") String topic,
            @ToolParam(description = "Maximum number of tokens to return (default 5000, max 10000)") int maxTokens) {
        int tokens = maxTokens > 0 ? Math.min(maxTokens, 10000) : 5000;
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(libraryId)
                        .queryParam("tokens", tokens)
                        .queryParam("topic", topic)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("Errore nel recupero della documentazione per '" + libraryId + "': " + e.getMessage()));
    }
}
