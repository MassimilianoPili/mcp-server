package com.example.mcp.config;

import io.github.massimilianopili.ai.reactive.callback.ReactiveToolCallbackAdapter;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces Spring AI's default ToolCallbackConverterAutoConfiguration to produce
 * AsyncToolSpecification objects that include MCP ToolAnnotations.
 *
 * <p>Spring AI's McpToolUtils.toAsyncToolSpecification() builds McpSchema.Tool
 * but never calls Tool.Builder.annotations(). This config uses McpToolUtils for
 * the base conversion, then rebuilds the Tool with annotations read from
 * {@link ReactiveToolCallbackAdapter}.</p>
 *
 * <p>Requires excluding the default converter:
 * {@code spring.autoconfigure.exclude=org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration}</p>
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class AnnotatedToolConverterConfig {

    private static final Logger log = LoggerFactory.getLogger(AnnotatedToolConverterConfig.class);

    @Bean
    public List<McpServerFeatures.AsyncToolSpecification> asyncToolSpecifications(
            List<ToolCallbackProvider> providers) {

        List<McpServerFeatures.AsyncToolSpecification> specs = new ArrayList<>();
        int annotatedCount = 0;

        for (ToolCallbackProvider provider : providers) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                // Use Spring AI's standard conversion (handles call handler, schema, etc.)
                McpServerFeatures.AsyncToolSpecification baseSpec =
                        McpToolUtils.toAsyncToolSpecification(callback);

                // Check if callback carries annotation hints
                if (callback instanceof ReactiveToolCallbackAdapter rta
                        && hasAnyHint(rta)) {

                    McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(
                            null,                               // title
                            rta.isReadOnly() ? true : null,     // readOnlyHint (null = unset)
                            rta.isDestructive() ? true : null,  // destructiveHint
                            rta.isIdempotent() ? true : null,   // idempotentHint
                            null,                               // openWorldHint
                            null                                // returnDirect
                    );

                    // Rebuild Tool with annotations
                    McpSchema.Tool originalTool = baseSpec.tool();
                    McpSchema.Tool annotatedTool = McpSchema.Tool.builder()
                            .name(originalTool.name())
                            .description(originalTool.description())
                            .inputSchema(originalTool.inputSchema())
                            .annotations(annotations)
                            .build();

                    // Rebuild spec with annotated tool, keeping the original call handler
                    specs.add(new McpServerFeatures.AsyncToolSpecification(
                            annotatedTool,
                            baseSpec.call(),
                            baseSpec.callHandler()
                    ));
                    annotatedCount++;
                } else {
                    specs.add(baseSpec);
                }
            }
        }

        log.info("Registered {} async tool specifications ({} with annotations)",
                specs.size(), annotatedCount);
        return specs;
    }

    private static boolean hasAnyHint(ReactiveToolCallbackAdapter rta) {
        return rta.isReadOnly() || rta.isDestructive() || rta.isIdempotent();
    }
}
