package io.github.massimilianopili.mcp.sql;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@Import({MultiDataSourceConfig.class, DatabaseTools.class})
public class SqlToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "sqlToolCallbackProvider")
    public ToolCallbackProvider sqlToolCallbackProvider(DatabaseTools dbTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(dbTools)
                .build();
    }
}
