package com.github.shikejing.codecompletionservicejava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers configuration properties for the completion service.
 */
@Configuration
@EnableConfigurationProperties(CompletionProperties.class)
public class ServiceConfig {
}
