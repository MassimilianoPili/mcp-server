package com.example.mcp.prompts;

import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class WorkflowPrompts {

    @McpPrompt(
            name = "research",
            description = "Academic research workflow: systematic literature search and synthesis using KORE, OpenAlex, and web search"
    )
    public Mono<McpSchema.GetPromptResult> researchPrompt(
            @McpArg(name = "topic", description = "Research topic or question", required = true) String topic,
            @McpArg(name = "depth", description = "Research depth: 'quick' (5min) or 'deep' (thorough)", required = false) String depth) {

        String d = depth != null && !depth.isBlank() ? depth : "quick";

        String system = """
                You are a research assistant with access to a knowledge graph (KORE) and academic databases.
                Follow this systematic methodology:

                1. **KORE first** — Search existing knowledge: `graph_query` for concepts, papers, authors already indexed
                2. **OpenAlex** — Search academic literature: `openalex_search` for papers, citations, venues
                3. **Web search** — Search for recent results: `web_search` for blog posts, news, preprints
                4. **Fetch & validate** — Use `web_fetch` with extract modes for key papers
                5. **Synthesize** — Produce structured findings with:
                   - Confidence levels (high/medium/low) per claim
                   - Source tiers: Tier 1 (peer-reviewed), Tier 2 (preprint/reputable blog), Tier 3 (other)
                   - Key open questions and research gaps

                Available tools: graph_query, openalex_search, openalex_neighborhood, web_search, web_fetch, research_validate_paper
                """;

        String user = String.format("Research topic: %s\nDepth: %s", topic, d);

        return Mono.just(new McpSchema.GetPromptResult(
                "Research: " + topic,
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        ));
    }

    @McpPrompt(
            name = "deploy",
            description = "Service deployment checklist with pre-flight checks, zero-downtime deploy, and health verification"
    )
    public Mono<McpSchema.GetPromptResult> deployPrompt(
            @McpArg(name = "service", description = "Service name to deploy (e.g. 'nginx', 'mcp', 'keycloak')", required = true) String service) {

        String system = """
                You are a deployment assistant for Server SOL infrastructure.
                Follow this deployment checklist for the service:

                **Pre-flight:**
                1. `infra_get_service("%s")` — verify current status and image
                2. `docker_get_container_logs` — check for recent errors
                3. `infra_get_dependencies` — verify all dependencies are healthy

                **Deploy:**
                4. Run the appropriate deploy command:
                   - Docker services: `docker compose up -d --force-recreate`
                   - Use zero-downtime scale trick if service supports it

                **Post-deploy:**
                5. Wait for healthcheck to pass (check container status)
                6. `docker_get_container_logs` — verify clean startup
                7. Verify the service responds correctly
                8. Update KORE if infrastructure changed: `graph_write`

                Available tools: infra_get_service, docker_list_containers, docker_get_container_logs, infra_get_dependencies
                """.formatted(service);

        String user = "Deploy service: " + service;

        return Mono.just(new McpSchema.GetPromptResult(
                "Deploy: " + service,
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        ));
    }

    @McpPrompt(
            name = "troubleshoot",
            description = "Diagnostic workflow: check status, logs, metrics, recent changes, and suggest fixes"
    )
    public Mono<McpSchema.GetPromptResult> troubleshootPrompt(
            @McpArg(name = "service", description = "Service to diagnose (e.g. 'nginx', 'keycloak', 'postgres')", required = true) String service) {

        String system = """
                You are a diagnostic assistant for Server SOL infrastructure.
                Follow this troubleshooting workflow for the service:

                **Gather information:**
                1. `infra_get_service("%s")` — current config, ports, auth pattern
                2. `docker_inspect_container` — container state, restarts, exit code
                3. `docker_get_container_logs` — recent logs (look for errors, warnings)
                4. `ops_troubleshoot("%s")` — known issues and solutions for this service

                **Analyze:**
                5. Check dependencies: `infra_get_dependencies`
                6. Check network: `net_get_endpoint`, verify nginx routes
                7. Check resources: container stats, disk usage

                **Diagnose & fix:**
                8. Identify root cause from gathered data
                9. Suggest specific fix with exact commands
                10. If fix requires restart: follow deploy checklist

                Available tools: infra_get_service, docker_inspect_container, docker_get_container_logs,
                ops_troubleshoot, infra_get_dependencies, net_get_endpoint, docker_get_container_stats
                """.formatted(service, service);

        String user = "Troubleshoot service: " + service;

        return Mono.just(new McpSchema.GetPromptResult(
                "Troubleshoot: " + service,
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(user))
                )
        ));
    }

    @McpPrompt(
            name = "server-health",
            description = "Full server health check: all containers, disk, memory, alerts, recent errors"
    )
    public Mono<McpSchema.GetPromptResult> serverHealthPrompt() {

        String system = """
                You are a health monitoring assistant for Server SOL.
                Perform a comprehensive server health check:

                **Container health:**
                1. `docker_list_containers` — list all running/stopped containers
                2. Check for containers with restart count > 0
                3. Check for containers in unhealthy state

                **System resources:**
                4. Check disk usage, memory, CPU via available metrics

                **Recent issues:**
                5. Check for error logs in key services (nginx, keycloak, postgres)
                6. Check systemd timer status for scheduled jobs

                **Report format:**
                - Overall status: HEALTHY / DEGRADED / CRITICAL
                - List any issues found with severity
                - Recommended actions (if any)

                Available tools: docker_list_containers, docker_get_container_stats, docker_get_container_logs,
                infra_search, ops_list_systemd
                """;

        return Mono.just(new McpSchema.GetPromptResult(
                "Server Health Check",
                List.of(
                        new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(system)),
                        new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent("Run a full server health check."))
                )
        ));
    }
}
