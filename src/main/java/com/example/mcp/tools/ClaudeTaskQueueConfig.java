package com.example.mcp.tools;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "mcp.taskqueue.enabled", havingValue = "true", matchIfMissing = false)
public class ClaudeTaskQueueConfig {

    @Value("${mcp.taskqueue.db-url:jdbc:postgresql://postgres:5432/embeddings}")
    private String dbUrl;

    @Value("${mcp.taskqueue.db-username:postgres}")
    private String dbUsername;

    @Value("${mcp.taskqueue.db-credential:#{null}}")
    private String dbCredential;

    @Bean(name = "taskQueueDataSource")
    public HikariDataSource taskQueueDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(dbUrl);
        ds.setUsername(dbUsername);
        if (dbCredential != null) {
            ds.setPassword(dbCredential);
        }
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(0);
        ds.setPoolName("taskqueue-pool");
        return ds;
    }
}
