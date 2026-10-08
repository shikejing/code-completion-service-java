package com.github.shikejing.codecompletionservicejava.web;

import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.service.CompletionService;
import com.github.shikejing.codecompletionservicejava.service.ServiceFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the process-local {@link CompletionService} as a Spring bean. */
@Configuration
public class ServiceBeanConfig {

    private CompletionService service;

    @Bean
    public CompletionService completionService(CompletionProperties properties) {
        this.service = ServiceFactory.create(properties);
        return service;
    }

    @PreDestroy
    public void shutdown() {
        if (service != null) {
            service.close();
        }
    }
}
