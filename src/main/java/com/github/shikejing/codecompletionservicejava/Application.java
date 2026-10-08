package com.github.shikejing.codecompletionservicejava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.github.shikejing.codecompletionservicejava.client.DemoScenario;

/**
 * Entry point.
 *
 * <p>By default serves the local-loopback HTTP API on a random free port
 * ({@code server.port=0}). With the {@code --demo} argument the process also
 * runs {@link DemoScenario} against that port once the server is ready, then
 * exits, providing a fully local, reproducible smoke run.</p>
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        boolean demo = false;
        for (String arg : args) {
            if ("--demo".equals(arg)) {
                demo = true;
            }
        }
        ConfigurableApplicationContext context = SpringApplication.run(Application.class, args);
        if (demo) {
            Integer port = context.getEnvironment().getProperty("local.server.port",
                    Integer.class);
            if (port != null) {
                DemoScenario.run(port);
            }
            SpringApplication.exit(context, () -> 0);
        }
    }
}
