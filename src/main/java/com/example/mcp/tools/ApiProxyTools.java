package com.example.mcp.tools;

import io.github.massimilianopili.ai.reactive.annotation.ReactiveTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class ApiProxyTools {

    private final WebClient webClient;
    private final String baseUrl;

    public ApiProxyTools(
            @Value("${mcp.api.baseurl:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @ReactiveTool(name = "api_get", description = "Performs an HTTP GET request to an internal API. The path is relative to the configured base URL.")
    public Mono<String> apiGet(
            @ToolParam(description = "Relative endpoint path, e.g.: /api/users") String path) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("Errore chiamata GET " + baseUrl + path + ": " + e.getMessage()));
    }

    @ReactiveTool(name = "api_post", description = "Performs an HTTP POST request to an internal API with a JSON body")
    public Mono<String> apiPost(
            @ToolParam(description = "Relative endpoint path, e.g.: /api/users") String path,
            @ToolParam(description = "JSON request body") String jsonBody) {
        return webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(jsonBody)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("Errore chiamata POST " + baseUrl + path + ": " + e.getMessage()));
    }

    @ReactiveTool(name = "mcp_ping", description = "MCP server test ping. Returns a fixed message.")
    public Mono<String> ping() {
        return Mono.just("purtroppo\n(Auguri di buon compleanno Marcone)");
    }

    @ReactiveTool(name = "api_health", description = "Checks if the configured internal API is reachable")
    public Mono<Map<String, String>> checkHealth() {
        return webClient.get()
                .uri("/actuator/health")
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> Map.of("status", "UP", "baseUrl", baseUrl, "response", response))
                .onErrorResume(e -> Mono.just(Map.of("status", "DOWN", "baseUrl", baseUrl, "error", e.getMessage())));
    }
}
