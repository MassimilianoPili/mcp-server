package io.github.massimilianopili.mcp.devops;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(name = "mcp.devops.pat")
@EnableConfigurationProperties(DevOpsProperties.class)
@Import({DevOpsConfig.class,
         DevOpsWorkItemTools.class, DevOpsGitTools.class,
         DevOpsPipelineTools.class, DevOpsBoardTools.class,
         DevOpsReleaseTools.class})
public class DevOpsToolsAutoConfiguration {
    // Nessun ToolCallbackProvider bean necessario.
    // I tool @ReactiveTool vengono auto-registrati da
    // ReactiveToolAutoConfiguration di spring-ai-reactive-tools.
}
