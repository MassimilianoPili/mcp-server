package com.example.mcp.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class InfraResources {

    private final WebClient dockerClient;
    private final ObjectMapper objectMapper;

    public InfraResources(
            @Value("${mcp.docker.host:}") String dockerHost,
            ObjectMapper objectMapper) {
        String baseUrl = dockerHost.isBlank()
                ? "http://localhost"
                : dockerHost.replace("unix://", "http://");
        this.dockerClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/json")
                .build();
        this.objectMapper = objectMapper;
    }

    @McpResource(
            uri = "sol://services",
            name = "Docker Services",
            description = "All running Docker services with name, image, status, state, and ports. "
                    + "Use this resource for a quick overview of the infrastructure.",
            mimeType = "application/json"
    )
    public Mono<McpSchema.ReadResourceResult> getServices() {
        return dockerClient.get()
                .uri("/v1.45/containers/json?all=true")
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sol://services", "application/json", json)
                )))
                .onErrorResume(e -> Mono.just(new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sol://services", "application/json",
                                "{\"error\": \"Failed to list Docker services: " + e.getMessage() + "\"}")
                ))));
    }

    @McpResource(
            uri = "sol://health",
            name = "System Health",
            description = "Current system health summary: container count (running/stopped/unhealthy), "
                    + "basic resource utilization. Quick check for overall server status.",
            mimeType = "application/json"
    )
    public Mono<McpSchema.ReadResourceResult> getHealth() {
        return dockerClient.get()
                .uri("/v1.45/containers/json?all=true")
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(containersJson -> Mono.fromCallable(() -> {
                    var containers = objectMapper.readTree(containersJson);
                    int total = containers.size();
                    int running = 0, stopped = 0, unhealthy = 0;
                    for (var c : containers) {
                        String state = c.path("State").asText("");
                        String status = c.path("Status").asText("");
                        if ("running".equals(state)) running++;
                        else stopped++;
                        if (status.contains("unhealthy")) unhealthy++;
                    }

                    Map<String, Object> health = new LinkedHashMap<>();
                    health.put("status", unhealthy > 0 ? "DEGRADED" : stopped > 3 ? "WARNING" : "HEALTHY");
                    health.put("containers", Map.of(
                            "total", total,
                            "running", running,
                            "stopped", stopped,
                            "unhealthy", unhealthy
                    ));
                    health.put("timestamp", Instant.now().toString());

                    String healthJson = objectMapper.writeValueAsString(health);
                    return new McpSchema.ReadResourceResult(List.of(
                            new McpSchema.TextResourceContents("sol://health", "application/json", healthJson)
                    ));
                }).subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(e -> Mono.just(new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sol://health", "application/json",
                                "{\"error\": \"Health check failed: " + e.getMessage() + "\"}")
                ))));
    }
}
