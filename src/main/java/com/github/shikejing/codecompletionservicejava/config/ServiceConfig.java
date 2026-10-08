package com.github.shikejing.codecompletionservicejava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables typed {@link ServiceProperties} binding. */
@Configuration
@EnableConfigurationProperties(ServiceProperties.class)
public class ServiceConfig {
}
