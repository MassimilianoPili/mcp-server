package io.github.massimilianopili.mcp.mongo;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

@AutoConfiguration
@ConditionalOnClass(MongoTemplate.class)
@ConditionalOnProperty(name = "mcp.mongo.enabled", havingValue = "true")
@Import({MongoConfig.class, MongoDbTools.class})
public class MongoToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "mongoToolCallbackProvider")
    public ToolCallbackProvider mongoToolCallbackProvider(MongoDbTools mongoDbTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(mongoDbTools)
                .build();
    }
}
