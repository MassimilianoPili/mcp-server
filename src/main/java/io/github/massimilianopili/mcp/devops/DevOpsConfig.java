package io.github.massimilianopili.mcp.devops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;

@Configuration
@ConditionalOnProperty(name = "mcp.devops.pat")
public class DevOpsConfig {

    @Bean(name = "devOpsWebClient")
    public WebClient devOpsWebClient(DevOpsProperties props) {
        String credentials = Base64.getEncoder()
                .encodeToString((":" + props.getPat()).getBytes());

        return WebClient.builder()
                .defaultHeader("Authorization", "Basic " + credentials)
                .defaultHeader("Accept", "application/json")
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                        .build())
                .build();
    }
}
