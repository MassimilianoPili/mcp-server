package com.example.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(exclude = {
        MongoAutoConfiguration.class,
        MongoDataAutoConfiguration.class
})
public class McpServerApplication {

    private static final Logger log = LoggerFactory.getLogger(McpServerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public CommandLineRunner startupLog() {
        return args -> {
            log.info("=== MCP Server avviato con successo ===");
            log.info("Server in ascolto su STDIO (stdin/stdout)");
        };
    }
}
